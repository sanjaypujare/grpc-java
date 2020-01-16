/*
 * Copyright 2020 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.grpc.InternalLogId;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.SharedResourceHolder.Resource;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.XdsClient.ConfigUpdate;
import io.grpc.xds.XdsClient.ConfigWatcher;
import io.grpc.xds.XdsClient.XdsChannelFactory;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GrpcServerXdsClient {

  static final Logger logger = Logger.getLogger(GrpcServerXdsClient.class.getName());

  private static final EventLoopGroupResource eventLoopGroupResource =
      Epoll.isAvailable() ? new EventLoopGroupResource("GrpcServerXdsClient") : null;
  private final DownstreamTlsContext staticDownstreamTlsContext;
  private final int port;
  private final Bootstrapper bootstrapper;
  private final InternalLogId logId;
  private String backendServiceName;
  private Listener myListener;
  // Must be accessed from the syncContext
  private boolean panicMode;
  final SynchronizationContext syncContext = new SynchronizationContext(
      new Thread.UncaughtExceptionHandler() {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
          logger.log(
              Level.SEVERE,
              "[" + getLogId() + "] Uncaught exception in the SynchronizationContext. Panic!",
              e);
          panic(e);
        }
      });
  private XdsClientImpl2 xdsClient;

  /** XdsClient used by GrpcServer side. */
  public GrpcServerXdsClient(DownstreamTlsContext downstreamTlsContext, int port,
      Bootstrapper bootstrapper) {
    this.staticDownstreamTlsContext = downstreamTlsContext;
    this.port = port;
    this.bootstrapper = bootstrapper;
    this.logId = InternalLogId.allocate("GrpcServer", Integer.toString(port));
    BootstrapInfo bootstrapInfo = null;
    try {
      bootstrapInfo = bootstrapper.readBootstrap();
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Error from readBootstrap", e);
      return;
    }
    final List<ServerInfo> serverList = bootstrapInfo.getServers();
    final Node node = bootstrapInfo.getNode();
    if (serverList.isEmpty()) {
      logger.log(Level.SEVERE, "No traffic director provided by bootstrap");
      return;
    }
    backendServiceName = getBackendServiceName(node.getMetadata());
    checkState(Epoll.isAvailable(), "Epoll is not available");
    EventLoopGroup timeService = SharedResourceHolder.get(eventLoopGroupResource);
    xdsClient = new XdsClientImpl2(
        serverList,
        XdsChannelFactory.getInstance(),
        node,
        syncContext,
        timeService,
        new ExponentialBackoffPolicy.Provider(),
        GrpcUtil.STOPWATCH_SUPPLIER);

    xdsClient.watchConfigData(backendServiceName, port, new ConfigWatcher() {
      @Override
      public void onConfigChanged(ConfigUpdate update) {
        logger.log(Level.INFO, "ConfigUpdate has listener in :{0}", update.listener.toString());
        myListener = update.listener;
      }

      @Override
      public void onError(Status error) {
        // In order to distinguish between IO error and resource not found, which trigger
        // different handling, return an empty resolution result to channel for resource not
        // found.
        // TODO(chengyuanzhang): Returning an empty resolution result based on status code is
        //  a temporary solution. More design discussion needs to be done.
        /*
        if (error.getCode().equals(Code.NOT_FOUND)) {
          listener.onResult(ResolutionResult.newBuilder().build());
        }
        listener.onError(error); */
        logger.log(Level.SEVERE, "ConfigWatcher in GrpcServerXdsClient:{0}", error);
      }
    });
  }

  private static String getBackendServiceName(Struct metadata) {
    Value value = metadata.getFieldsOrThrow("TRAFFICDIRECTOR_WORKLOAD_NAME");
    return value.getStringValue();
  }

  public InternalLogId getLogId() {
    return logId;
  }

  // Called from syncContext
  @VisibleForTesting
  void panic(final Throwable t) {
    if (panicMode) {
      // Preserve the first panic information
      return;
    }
    panicMode = true;
  }

  /** compute the DownstreamTlsContext for the given connection. */
  public DownstreamTlsContext getDownstreamTlsContext(Channel channel) {
    DownstreamTlsContext dynamic = getDownstreamTlsContextFromListener(channel);
    if (dynamic != null) {
      return dynamic;
    }
    return staticDownstreamTlsContext;
  }

  private DownstreamTlsContext getDownstreamTlsContextFromListener(
      Channel channel) {
    if (myListener != null) {
      List<FilterChain> filterChains = myListener.getFilterChainsList();
      for (FilterChain filterChain : filterChains) {
        DownstreamTlsContext cur = filterChain.getTlsContext();
        if (cur != null && filterChainMatches(filterChain, channel)) {
          return cur;
        }
      }
    }
    return null;
  }

  private boolean filterChainMatches(FilterChain filterChain,
      Channel channel) {
    logger.log(Level.INFO, "returning true for {0}", filterChain.toString());
    return true;
  }

  private static final class EventLoopGroupResource implements Resource<EventLoopGroup> {

    private final String name;

    EventLoopGroupResource(String name) {
      this.name = name;
    }

    @Override
    public EventLoopGroup create() {
      // Use Netty's DefaultThreadFactory in order to get the benefit of FastThreadLocal.
      ThreadFactory threadFactory = new DefaultThreadFactory(name, /* daemon= */ true);
      return new EpollEventLoopGroup(1, threadFactory);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @Override
    public void close(EventLoopGroup instance) {
      try {
        instance.shutdownGracefully(0, 0, TimeUnit.SECONDS).sync();
      } catch (InterruptedException e) {
        logger.log(Level.SEVERE, "from EventLoopGroup.shutdownGracefully", e);
        Thread.currentThread().interrupt(); // to not "swallow" the exception...
      }
    }
  }

}
