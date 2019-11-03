/*
 * Copyright 2019 The gRPC Authors
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

package io.grpc.xds.sds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Strings;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;



/**
 * temporary implementation of our SDS client. A more robust implementation will follow
 */
public class SdsClientTemp {
  private static final Logger logger = Logger.getLogger(SdsClientTemp.class.getName());

  // SecretTypeURL defines the type URL for Envoy secret proto.
  private static final String SECRET_TYPE_URL = "type.googleapis.com/envoy.api.v2.auth.Secret";

  private static SdsClientTemp instance;

  private ConfigSource configSource;
  String udsTarget;
  private SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub stub;
  ResponseObserver responseObserver;
  StreamObserver<DiscoveryRequest> requestObserver;
  DiscoveryResponse lastResponse;
  private final Node clientNode;

  /**
   * Starts resource discovery with SDS protocol. This should be the first method to be called in
   * this class. It should only be called once.
   */
  void start() {
    ManagedChannel channel = null;
    if (udsTarget.startsWith("unix:")) {
      EventLoopGroup elg = new EpollEventLoopGroup();

      channel =
          NettyChannelBuilder.forAddress(new DomainSocketAddress(udsTarget.substring(5)))
              .eventLoopGroup(elg)
              .channelType(EpollDomainSocketChannel.class)
              .build();
    } else {
      channel = InProcessChannelBuilder.forName(udsTarget).directExecutor().build();
    }
    startUsingChannel(channel);
  }

  private void startUsingChannel(ManagedChannel channel) {
    stub = SecretDiscoveryServiceGrpc.newStub(channel);
    responseObserver = new ResponseObserver();
    requestObserver = stub.streamSecrets(responseObserver);
    /*
    synchronized (responseObserver) {
      while (!responseObserver.completed) {
        try {
          responseObserver.wait();
        } catch (InterruptedException e) {
          logger.log(Level.SEVERE, "wait", e);
        }
      }
    }
     */
  }

  /**
   * Stops resource discovery. No method in this class should be called after this point.
   */
  void shutdown() {

  }


  static synchronized SdsClientTemp getInstance(ConfigSource configSource, Node node) {
    if (instance == null) {
      instance = new SdsClientTemp(configSource, node);
      return instance;
    }
    // check if apiConfigSource match
    if (instance.configSource.equals(configSource)) {
      return instance;
    }
    throw new UnsupportedOperationException(
            "Multiple SdsClientTemp with different ApiConfigSource not supported");
  }

  /**
   * create the client with this apiConfigSource.
   */
  SdsClientTemp(ConfigSource configSource, Node node) {
    checkNotNull(configSource, "configSource");
    checkNotNull(node, "node");
    extractUdsTarget(configSource);
    this.clientNode = node;
  }

  void extractUdsTarget(ConfigSource configSource) {
    checkArgument(
        configSource.hasApiConfigSource(), "only configSource with ApiConfigSource supported");
    ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
    checkArgument(
        ApiType.GRPC.equals(apiConfigSource.getApiType()),
        "only GRPC ApiConfigSource type supported");
    checkArgument(
        apiConfigSource.getGrpcServicesCount() == 1,
        "expecting exactly 1 GrpcService in ApiConfigSource");
    GrpcService grpcService = apiConfigSource.getGrpcServices(0);
    checkArgument(
        grpcService.hasGoogleGrpc() && !grpcService.hasEnvoyGrpc(),
        "only GoogleGrpc expected in GrpcService");
    GoogleGrpc googleGrpc = grpcService.getGoogleGrpc();
    // for now don't support any credentials
    checkArgument(
        !googleGrpc.hasChannelCredentials()
            && googleGrpc.getCallCredentialsCount() == 0
            && Strings.isNullOrEmpty(googleGrpc.getCredentialsFactoryName()),
        "No credentials supported in GoogleGrpc");
    String targetUri = googleGrpc.getTargetUri();
    checkArgument(!Strings.isNullOrEmpty(targetUri), "targetUri in GoogleGrpc is empty!");
    this.configSource = configSource;
    udsTarget = targetUri;
  }

  /**
   * Response observer for our client.
   */
  class ResponseObserver implements StreamObserver<DiscoveryResponse> {
    boolean completed = false;

    ResponseObserver() {
    }

    @Override
    public void onNext(DiscoveryResponse discoveryResponse) {
      try {
        processDiscoveryResponse(discoveryResponse);
      } catch (InvalidProtocolBufferException e) {
        logger.log(Level.SEVERE, "processDiscoveryResponse", e);
      }
    }

    @Override
    public void onError(Throwable t) {
      logger.log(Level.SEVERE, "onError", t);
    }

    @Override
    public void onCompleted() {
      synchronized (this) {
        completed = true;
        this.notifyAll();
      }
    }
  }

  private void processDiscoveryResponse(DiscoveryResponse response)
       throws InvalidProtocolBufferException {
    List<Any> resources = response.getResourcesList();
    ArrayList<String> resourceNames = new ArrayList<>();
    for (Any any : resources) {
      String unused = any.getTypeUrl();
      // todo: assert value of typeUrl
      Secret secret = Secret.parseFrom(any.getValue());
      resourceNames.add(secret.getName());
      processSecret(secret);
    }
    lastResponse = response;
    // send ACK
    sendDiscoveryRequestOnStream(resourceNames);
  }

  private void processSecret(Secret secret) {
    final HashSet<SecretWatcher> secretWatchers = watcherMap.get(secret.getName());
    if (secretWatchers != null) {
      for (SecretWatcher secretWatcher : secretWatchers) {
        secretWatcher.onSecretChanged(secret);
      }
    } else {
      // why are we getting responses for resource names we don't have watchers for?
    }
  }


  /**
   * Secret watcher interface.
   */
  interface SecretWatcher {

    void onSecretChanged(Secret secretUpdate);

    void onError(Status error);
  }

  static class SecretWatcherHandle {
    final String name;
    final SecretWatcher secretWatcher;

    SecretWatcherHandle(String name, SecretWatcher secretWatcher) {
      this.name = name;
      this.secretWatcher = secretWatcher;
    }
  }

  final HashMap<String, HashSet<SecretWatcher>> watcherMap =
          new HashMap<>();

  /**
   * Registers a secret watcher for the given SdsSecretConfig.
   */
  SecretWatcherHandle watchSecret(SdsSecretConfig sdsSecretConfig, SecretWatcher secretWatcher) {
    checkNotNull(sdsSecretConfig, "sdsSecretConfig");
    checkNotNull(secretWatcher, "secretWatcher");
    checkArgument(sdsSecretConfig.getSdsConfig().equals(this.configSource),
            "expected configSource" + this.configSource);
    String name = sdsSecretConfig.getName();
    synchronized (watcherMap) {
      HashSet<SecretWatcher> set = watcherMap.get(name);
      if (set == null) {
        set = new HashSet<>();
        watcherMap.put(name, set);
      }
      set.add(secretWatcher);
    }
    sendDiscoveryRequestOnStream(name);
    return new SecretWatcherHandle(name, secretWatcher);
  }

  private void sendDiscoveryRequestOnStream(String ... names) {
    sendDiscoveryRequestOnStream(Arrays.asList(names));
  }

  private void sendDiscoveryRequestOnStream(List<String> names) {
    String nonce = "";
    String versionInfo = "";

    if (lastResponse != null) {
      nonce = lastResponse.getNonce();
      versionInfo = lastResponse.getVersionInfo();
    }
    DiscoveryRequest.Builder builder =
        DiscoveryRequest.newBuilder()
            .setTypeUrl(SECRET_TYPE_URL)
            .setResponseNonce(nonce)
            .setVersionInfo(versionInfo)
            .setNode(clientNode);

    for (String name : names) {
      builder.addResourceNames(name);
    }
    DiscoveryRequest req = builder.build();
    requestObserver.onNext(req);
  }

  /**
   * Unregisters the given endpoints watcher.
   */
  void cancelSecretWatch(SecretWatcherHandle secretWatcherHandle) {
    checkNotNull(secretWatcherHandle, "secretWatcherHandle");
    synchronized (watcherMap) {
      HashSet<SecretWatcher> set = watcherMap.get(secretWatcherHandle.name);
      checkState(set != null, "watcher not found");
      checkState(set.remove(secretWatcherHandle.secretWatcher), "watcher not found");
    }
  }
}
