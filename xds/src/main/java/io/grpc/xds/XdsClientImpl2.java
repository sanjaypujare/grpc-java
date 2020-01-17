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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Code;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.SynchronizationContext;
import io.grpc.SynchronizationContext.ScheduledHandle;
import io.grpc.internal.BackoffPolicy;
import io.grpc.stub.StreamObserver;
import io.grpc.xds.Bootstrapper.ServerInfo;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

final class XdsClientImpl2 extends XdsClient {
  private static final Logger logger = Logger.getLogger(XdsClientImpl2.class.getName());

  @VisibleForTesting
  static final String ADS_TYPE_URL_LDS = "type.googleapis.com/envoy.api.v2.Listener";
  @VisibleForTesting
  static final String ADS_TYPE_URL_RDS =
      "type.googleapis.com/envoy.api.v2.RouteConfiguration";
  @VisibleForTesting
  static final String ADS_TYPE_URL_CDS = "type.googleapis.com/envoy.api.v2.Cluster";
  @VisibleForTesting
  static final String ADS_TYPE_URL_EDS =
      "type.googleapis.com/envoy.api.v2.ClusterLoadAssignment";

  private final ManagedChannel channel;
  private final SynchronizationContext syncContext;
  private final ScheduledExecutorService timeService;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final Supplier<Stopwatch> stopwatchSupplier;
  private final Stopwatch adsStreamRetryStopwatch;
  // The node identifier to be included in xDS requests. Management server only requires the
  // first request to carry the node identifier on a stream. It should be identical if present
  // more than once.
  private final Node node;

  // Cached data for CDS responses, keyed by cluster names.
  // Optimization: cache ClusterUpdate, which contains only information needed by gRPC, instead
  // of whole Cluster messages to reduce memory usage.
  private final Map<String, ClusterUpdate> clusterNamesToClusterUpdates = new HashMap<>();

  // Cached data for EDS responses, keyed by cluster names.
  // CDS responses indicate absence of clusters and EDS responses indicate presence of clusters.
  // Optimization: cache EndpointUpdate, which contains only information needed by gRPC, instead
  // of whole ClusterLoadAssignment messages to reduce memory usage.
  private final Map<String, EndpointUpdate> clusterNamesToEndpointUpdates = new HashMap<>();

  // Cluster watchers waiting for cluster information updates. Multiple cluster watchers
  // can watch on information for the same cluster.
  private final Map<String, Set<ClusterWatcher>> clusterWatchers = new HashMap<>();

  // Endpoint watchers waiting for endpoint updates for each cluster. Multiple endpoint
  // watchers can watch endpoints in the same cluster.
  private final Map<String, Set<EndpointWatcher>> endpointWatchers = new HashMap<>();

  // Load reporting clients, with each responsible for reporting loads of a single cluster.
  private final Map<String, LoadReportClientImpl> lrsClients = new HashMap<>();

  @Nullable
  private AdsStream adsStream;
  @Nullable
  private BackoffPolicy retryBackoffPolicy;
  @Nullable
  private ScheduledHandle rpcRetryTimer;

  // Following fields are set only after the ConfigWatcher registered. Once set, they should
  // never change.
  @Nullable
  private ConfigWatcher configWatcher;
  // The host name portion of "xds:" URI that the gRPC client targets for.
  @Nullable
  private String hostName;
  // The "xds:" URI (including port suffix if present) that the gRPC client targets for.
  @Nullable
  private String ldsResourceName;

  XdsClientImpl2(
      List<ServerInfo> servers,  // list of management servers
      XdsChannelFactory channelFactory,
      Node node,
      SynchronizationContext syncContext,
      ScheduledExecutorService timeService,
      BackoffPolicy.Provider backoffPolicyProvider,
      Supplier<Stopwatch> stopwatchSupplier) {
    this.channel =
        checkNotNull(channelFactory, "channelFactory")
            .createChannel(checkNotNull(servers, "servers"));
    this.node = checkNotNull(node, "node");
    this.syncContext = checkNotNull(syncContext, "syncContext");
    this.timeService = checkNotNull(timeService, "timeService");
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    this.stopwatchSupplier = checkNotNull(stopwatchSupplier, "stopwatch");
    adsStreamRetryStopwatch = stopwatchSupplier.get();
    logger.info("inside XdsClientImpl2 ctor");
  }

  @Override
  void shutdown() {
    logger.log(Level.INFO, "Shutting down XdsClient2");
    channel.shutdown();
    if (adsStream != null) {
      adsStream.close(Status.CANCELLED.withDescription("shutdown").asException());
    }
    for (LoadReportClientImpl lrsClient : lrsClients.values()) {
      lrsClient.stopLoadReporting();
    }
    if (rpcRetryTimer != null) {
      rpcRetryTimer.cancel();
    }
  }

  @Override
  void watchConfigData(String hostName, int port, ConfigWatcher watcher) {
    logger.info("inside XdsClientImpl2.watchConfigData");
    checkState(configWatcher == null, "ConfigWatcher is already registered");
    configWatcher = checkNotNull(watcher, "watcher");
    this.hostName = checkNotNull(hostName, "hostName");
    if (port == -1) {
      ldsResourceName = hostName;
    } else {
      ldsResourceName = hostName + ":" + port;
    }
    if (rpcRetryTimer != null && rpcRetryTimer.isPending()) {
      // Currently in retry backoff.
      return;
    }
    if (adsStream == null) {
      startRpcStream();
    }
    logger.log(Level.INFO, "sendXdsRequest ldsResourceName {0}", ldsResourceName);
    adsStream.sendXdsRequest(ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName));
  }

  /**
   * Establishes the RPC connection by creating a new RPC stream on the given channel for
   * xDS protocol communication.
   */
  private void startRpcStream() {
    checkState(adsStream == null, "Previous adsStream has not been cleared yet");
    AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub =
        AggregatedDiscoveryServiceGrpc.newStub(channel);
    adsStream = new AdsStream(stub);
    adsStream.start();
    adsStreamRetryStopwatch.reset().start();
  }

  private void handleLdsResponse(DiscoveryResponse ldsResponse) {
    logger.log(Level.FINE, "Received an LDS response: {0}", ldsResponse);
    checkState(ldsResourceName != null && configWatcher != null,
        "No LDS request was ever sent. Management server is doing something wrong");
    // Unpack Listener messages.
    Listener requestedListener = null;
    logger.log(Level.FINE, "Listener count: {0}", ldsResponse.getResourcesCount());
    try {
      for (com.google.protobuf.Any res : ldsResponse.getResourcesList()) {
        Listener listener = res.unpack(Listener.class);
        logger.log(Level.FINE, "Adding listener to list: {0}", listener.toString());
        if (listener.getName().equals(ldsResourceName)) {
          requestedListener = listener;
          logger.log(Level.FINE, "Requested listener found: {0}", listener.toString());
        }
      }
    } catch (InvalidProtocolBufferException e) {
      adsStream.sendNackRequest(ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName),
          "Broken LDS response.");
      return;
    }
    adsStream.sendAckRequest(ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName),
        ldsResponse.getVersionInfo());
    if (requestedListener != null) {
      // Found requestedListener
      ConfigUpdate configUpdate = ConfigUpdate.newBuilder().setClusterName(null).build();
      configUpdate.listener = requestedListener;
      configWatcher.onConfigChanged(configUpdate);
    }
  }

  @VisibleForTesting
  final class RpcRetryTask implements Runnable {
    @Override
    public void run() {
      startRpcStream();
      if (configWatcher != null) {
        adsStream.sendXdsRequest(ADS_TYPE_URL_LDS, ImmutableList.of(ldsResourceName));
      }
      if (!clusterWatchers.isEmpty()) {
        adsStream.sendXdsRequest(ADS_TYPE_URL_CDS, clusterWatchers.keySet());
      }
      if (!endpointWatchers.isEmpty()) {
        adsStream.sendXdsRequest(ADS_TYPE_URL_EDS, endpointWatchers.keySet());
      }
    }
  }

  private final class AdsStream implements StreamObserver<DiscoveryResponse> {
    private final AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub;

    private StreamObserver<DiscoveryRequest> requestWriter;
    private boolean responseReceived;
    private boolean closed;

    // Last successfully applied version_info for each resource type. Starts with empty string.
    // A version_info is used to update management server with client's most recent knowledge of
    // resources.
    private String ldsVersion = "";
    private String rdsVersion = "";
    private String cdsVersion = "";
    private String edsVersion = "";

    // Response nonce for the most recently received discovery responses of each resource type.
    // Client initiated requests start response nonce with empty string.
    // A nonce is used to indicate the specific DiscoveryResponse each DiscoveryRequest
    // corresponds to.
    // A nonce becomes stale following a newer nonce being presented to the client in a
    // DiscoveryResponse.
    private String ldsRespNonce = "";
    private String rdsRespNonce = "";
    private String cdsRespNonce = "";
    private String edsRespNonce = "";

    // Most recently requested RDS resource name, which is an intermediate resource name for
    // resolving service config.
    // LDS request always use the same resource name, which is the "xds:" URI.
    // Resource names for EDS requests are always represented by the cluster names that
    // watchers are interested in.
    @Nullable
    private String rdsResourceName;
    // Most recently requested CDS resource names.
    // Due to CDS protocol limitation, client does not send a CDS request for empty resource
    // names when unsubscribing the last resource. Management server assumes it is still
    // subscribing to the last resource, client also need to behave so to avoid data lose.
    // Therefore, cluster names that watchers interested in cannot always represent resource names
    // in most recently sent CDS requests.
    @Nullable
    private Collection<String> cdsResourceNames;

    private AdsStream(AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub stub) {
      this.stub = checkNotNull(stub, "stub");
    }

    private void start() {
      requestWriter = stub.withWaitForReady().streamAggregatedResources(this);
    }

    @Override
    public void onNext(final DiscoveryResponse response) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          if (closed) {
            return;
          }
          responseReceived = true;
          String typeUrl = response.getTypeUrl();
          // Nonce in each response is echoed back in the following ACK/NACK request. It is
          // used for management server to identify which response the client is ACKing/NACking.
          // To avoid confusion, client-initiated requests will always use the nonce in
          // most recently received responses of each resource type.
          if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
            ldsRespNonce = response.getNonce();
            handleLdsResponse(response);
          } else {
            logger.log(Level.FINE, "Received unexpected DiscoveryResponse {0}",
                response);
          }
        }
      });
    }

    @Override
    public void onError(final Throwable t) {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(
              Status.fromThrowable(t).augmentDescription("ADS stream [" + this + "] had an error"));
        }
      });
    }

    @Override
    public void onCompleted() {
      syncContext.execute(new Runnable() {
        @Override
        public void run() {
          handleStreamClosed(
              Status.UNAVAILABLE.withDescription("ADS stream [" + this + "] was closed by server"));
        }
      });
    }

    private void handleStreamClosed(Status error) {
      checkArgument(!error.isOk(), "unexpected OK status");
      if (closed) {
        return;
      }
      logger.log(Level.FINE, error.getDescription(), error.getCause());
      closed = true;
      cleanUp();
      if (responseReceived || retryBackoffPolicy == null) {
        // Reset the backoff sequence if had received a response, or backoff sequence
        // has never been initialized.
        retryBackoffPolicy = backoffPolicyProvider.get();
      }
      long delayNanos = 0;
      if (!responseReceived) {
        delayNanos =
            Math.max(
                0,
                retryBackoffPolicy.nextBackoffNanos()
                    - adsStreamRetryStopwatch.elapsed(TimeUnit.NANOSECONDS));
      }
      logger.log(Level.FINE, "{0} stream closed, retry in {1} ns", new Object[]{this, delayNanos});
      rpcRetryTimer =
          syncContext.schedule(
              new RpcRetryTask(), delayNanos, TimeUnit.NANOSECONDS, timeService);
    }

    private void close(Exception error) {
      if (closed) {
        return;
      }
      closed = true;
      cleanUp();
      requestWriter.onError(error);
    }

    private void cleanUp() {
      if (adsStream == this) {
        adsStream = null;
      }
    }

    /**
     * Sends a DiscoveryRequest for the given resource name to management server. Memories the
     * requested resource name (except for LDS as we always request for the singleton Listener)
     * as we need it to find resources in responses.
     */
    private void sendXdsRequest(String typeUrl, Collection<String> resourceNames) {
      checkState(requestWriter != null, "ADS stream has not been started");
      String version = "";
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        version = ldsVersion;
        nonce = ldsRespNonce;
      } else {
        logger.severe("unexpected typeUrl:" + typeUrl);
        return;
      }
      DiscoveryRequest request =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(version)
              .setNode(node)
              .addAllResourceNames(resourceNames)
              .setTypeUrl(typeUrl)
              .setResponseNonce(nonce)
              .build();
      requestWriter.onNext(request);
      logger.log(Level.FINE, "Sent DiscoveryRequest {0}", request);
    }

    /**
     * Sends a DiscoveryRequest with the given information as an ACK. Updates the latest accepted
     * version for the corresponding resource type.
     */
    private void sendAckRequest(String typeUrl, Collection<String> resourceNames,
        String versionInfo) {
      checkState(requestWriter != null, "ADS stream has not been started");
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        ldsVersion = versionInfo;
        nonce = ldsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_RDS)) {
        rdsVersion = versionInfo;
        nonce = rdsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_CDS)) {
        cdsVersion = versionInfo;
        nonce = cdsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_EDS)) {
        edsVersion = versionInfo;
        nonce = edsRespNonce;
      }
      DiscoveryRequest request =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node)
              .addAllResourceNames(resourceNames)
              .setTypeUrl(typeUrl)
              .setResponseNonce(nonce)
              .build();
      requestWriter.onNext(request);
      logger.log(Level.FINE, "Sent ACK request {0}", request);
    }

    /**
     * Sends a DiscoveryRequest with the given information as an NACK. NACK takes the previous
     * accepted version.
     */
    private void sendNackRequest(String typeUrl, Collection<String> resourceNames,
        String message) {
      checkState(requestWriter != null, "ADS stream has not been started");
      String versionInfo = "";
      String nonce = "";
      if (typeUrl.equals(ADS_TYPE_URL_LDS)) {
        versionInfo = ldsVersion;
        nonce = ldsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_RDS)) {
        versionInfo = rdsVersion;
        nonce = rdsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_CDS)) {
        versionInfo = cdsVersion;
        nonce = cdsRespNonce;
      } else if (typeUrl.equals(ADS_TYPE_URL_EDS)) {
        versionInfo = edsVersion;
        nonce = edsRespNonce;
      }
      DiscoveryRequest request =
          DiscoveryRequest
              .newBuilder()
              .setVersionInfo(versionInfo)
              .setNode(node)
              .addAllResourceNames(resourceNames)
              .setTypeUrl(typeUrl)
              .setResponseNonce(nonce)
              .setErrorDetail(
                  com.google.rpc.Status.newBuilder()
                      .setCode(Code.INVALID_ARGUMENT_VALUE)
                      .setMessage(message))
              .build();
      requestWriter.onNext(request);
      logger.log(Level.FINE, "Sent NACK request {0}", request);
    }
  }

}
