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
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.alts.GoogleDefaultChannelBuilder;
import io.grpc.internal.ObjectPool;
import io.grpc.xds.Bootstrapper.BootstrapInfo;
import io.grpc.xds.Bootstrapper.ChannelCreds;
import io.grpc.xds.Bootstrapper.ServerInfo;
import io.grpc.xds.EnvoyProtoData.DropOverload;
import io.grpc.xds.EnvoyProtoData.Locality;
import io.grpc.xds.EnvoyProtoData.LocalityLbEndpoints;
import io.grpc.xds.EnvoyProtoData.Route;
import io.grpc.xds.EnvoyServerProtoData.Listener;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.LoadStatsManager.LoadStatsStore;
import io.grpc.xds.XdsLogger.XdsLogLevel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

/**
 * An {@link XdsClient} instance encapsulates all of the logic for communicating with the xDS
 * server. It may create multiple RPC streams (or a single ADS stream) for a series of xDS
 * protocols (e.g., LDS, RDS, VHDS, CDS and EDS) over a single channel. Watch-based interfaces
 * are provided for each set of data needed by gRPC.
 */
abstract class XdsClient {

  /**
   * Data class containing the results of performing a series of resource discovery RPCs via
   * LDS/RDS/VHDS protocols. The results may include configurations for path/host rewriting,
   * traffic mirroring, retry or hedging, default timeouts and load balancing policy that will
   * be used to generate a service config.
   */
  static final class ConfigUpdate {
    private final List<Route> routes;

    private ConfigUpdate(List<Route> routes) {
      this.routes = routes;
    }

    List<Route> getRoutes() {
      return routes;
    }

    @Override
    public String toString() {
      return
          MoreObjects
              .toStringHelper(this)
              .add("routes", routes)
              .toString();
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private final List<Route> routes = new ArrayList<>();

      // Use ConfigUpdate.newBuilder().
      private Builder() {
      }


      Builder addRoutes(Collection<Route> route) {
        routes.addAll(route);
        return this;
      }

      ConfigUpdate build() {
        checkState(!routes.isEmpty(), "routes is empty");
        return new ConfigUpdate(Collections.unmodifiableList(routes));
      }
    }
  }

  /**
   * Data class containing the results of performing a resource discovery RPC via CDS protocol.
   * The results include configurations for a single upstream cluster, such as endpoint discovery
   * type, load balancing policy, connection timeout and etc.
   */
  static final class ClusterUpdate {
    private final String clusterName;
    @Nullable
    private final String edsServiceName;
    private final String lbPolicy;
    @Nullable
    private final String lrsServerName;
    private final UpstreamTlsContext upstreamTlsContext;

    private ClusterUpdate(
        String clusterName,
        @Nullable String edsServiceName,
        String lbPolicy,
        @Nullable String lrsServerName,
        @Nullable UpstreamTlsContext upstreamTlsContext) {
      this.clusterName = clusterName;
      this.edsServiceName = edsServiceName;
      this.lbPolicy = lbPolicy;
      this.lrsServerName = lrsServerName;
      this.upstreamTlsContext = upstreamTlsContext;
    }

    String getClusterName() {
      return clusterName;
    }

    /**
     * Returns the resource name for EDS requests.
     */
    @Nullable
    String getEdsServiceName() {
      return edsServiceName;
    }

    /**
     * Returns the policy of balancing loads to endpoints. Only "round_robin" is supported
     * as of now.
     */
    String getLbPolicy() {
      return lbPolicy;
    }

    /**
     * Returns the server name to send client load reports to if LRS is enabled. {@code null} if
     * load reporting is disabled for this cluster.
     */
    @Nullable
    String getLrsServerName() {
      return lrsServerName;
    }

    /** Returns the {@link UpstreamTlsContext} for this cluster if present, else null. */
    @Nullable
    UpstreamTlsContext getUpstreamTlsContext() {
      return upstreamTlsContext;
    }

    @Override
    public String toString() {
      return
          MoreObjects
              .toStringHelper(this)
              .add("clusterName", clusterName)
              .add("edsServiceName", edsServiceName)
              .add("lbPolicy", lbPolicy)
              .add("lrsServerName", lrsServerName)
              .add("upstreamTlsContext", upstreamTlsContext)
              .toString();
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          clusterName, edsServiceName, lbPolicy, lrsServerName, upstreamTlsContext);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ClusterUpdate that = (ClusterUpdate) o;
      return Objects.equals(clusterName, that.clusterName)
          && Objects.equals(edsServiceName, that.edsServiceName)
          && Objects.equals(lbPolicy, that.lbPolicy)
          && Objects.equals(lrsServerName, that.lrsServerName)
          && Objects.equals(upstreamTlsContext, that.upstreamTlsContext);
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private String clusterName;
      @Nullable
      private String edsServiceName;
      private String lbPolicy;
      @Nullable
      private String lrsServerName;
      @Nullable
      private UpstreamTlsContext upstreamTlsContext;

      // Use ClusterUpdate.newBuilder().
      private Builder() {
      }

      Builder setClusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
      }

      Builder setEdsServiceName(String edsServiceName) {
        this.edsServiceName = edsServiceName;
        return this;
      }

      Builder setLbPolicy(String lbPolicy) {
        this.lbPolicy = lbPolicy;
        return this;
      }

      Builder setLrsServerName(String lrsServerName) {
        this.lrsServerName = lrsServerName;
        return this;
      }

      Builder setUpstreamTlsContext(UpstreamTlsContext upstreamTlsContext) {
        this.upstreamTlsContext = upstreamTlsContext;
        return this;
      }

      ClusterUpdate build() {
        checkState(clusterName != null, "clusterName is not set");
        checkState(lbPolicy != null, "lbPolicy is not set");

        return
            new ClusterUpdate(
                clusterName, edsServiceName, lbPolicy, lrsServerName, upstreamTlsContext);
      }
    }
  }

  /**
   * Data class containing the results of performing a resource discovery RPC via EDS protocol.
   * The results include endpoint addresses running the requested service, as well as
   * configurations for traffic control such as drop overloads, inter-cluster load balancing
   * policy and etc.
   */
  static final class EndpointUpdate {
    private final String clusterName;
    private final Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap;
    private final List<DropOverload> dropPolicies;

    private EndpointUpdate(
        String clusterName,
        Map<Locality, LocalityLbEndpoints> localityLbEndpoints,
        List<DropOverload> dropPolicies) {
      this.clusterName = clusterName;
      this.localityLbEndpointsMap = localityLbEndpoints;
      this.dropPolicies = dropPolicies;
    }

    static Builder newBuilder() {
      return new Builder();
    }

    String getClusterName() {
      return clusterName;
    }

    /**
     * Returns a map of localities with endpoints load balancing information in each locality.
     */
    Map<Locality, LocalityLbEndpoints> getLocalityLbEndpointsMap() {
      return Collections.unmodifiableMap(localityLbEndpointsMap);
    }

    /**
     * Returns a list of drop policies to be applied to outgoing requests.
     */
    List<DropOverload> getDropPolicies() {
      return Collections.unmodifiableList(dropPolicies);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      EndpointUpdate that = (EndpointUpdate) o;
      return Objects.equals(clusterName, that.clusterName)
          && Objects.equals(localityLbEndpointsMap, that.localityLbEndpointsMap)
          && Objects.equals(dropPolicies, that.dropPolicies);
    }

    @Override
    public int hashCode() {
      return Objects.hash(clusterName, localityLbEndpointsMap, dropPolicies);
    }

    @Override
    public String toString() {
      return
          MoreObjects
              .toStringHelper(this)
              .add("clusterName", clusterName)
              .add("localityLbEndpointsMap", localityLbEndpointsMap)
              .add("dropPolicies", dropPolicies)
              .toString();
    }

    static final class Builder {
      private String clusterName;
      private Map<Locality, LocalityLbEndpoints> localityLbEndpointsMap = new LinkedHashMap<>();
      private List<DropOverload> dropPolicies = new ArrayList<>();

      // Use EndpointUpdate.newBuilder().
      private Builder() {
      }

      Builder setClusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
      }

      Builder addLocalityLbEndpoints(Locality locality, LocalityLbEndpoints info) {
        localityLbEndpointsMap.put(locality, info);
        return this;
      }

      Builder addDropPolicy(DropOverload policy) {
        dropPolicies.add(policy);
        return this;
      }

      EndpointUpdate build() {
        checkState(clusterName != null, "clusterName is not set");
        return
            new EndpointUpdate(
                clusterName,
                ImmutableMap.copyOf(localityLbEndpointsMap),
                ImmutableList.copyOf(dropPolicies));
      }
    }
  }

  /**
   * Updates via resource discovery RPCs using LDS. Includes {@link Listener} object containing
   * config for security, RBAC or other server side features such as rate limit.
   */
  static final class ListenerUpdate {
    // TODO(sanjaypujare): flatten structure by moving Listener class members here.
    private final Listener listener;

    private ListenerUpdate(Listener listener) {
      this.listener = listener;
    }

    public Listener getListener() {
      return listener;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("listener", listener)
          .toString();
    }

    static Builder newBuilder() {
      return new Builder();
    }

    static final class Builder {
      private Listener listener;

      // Use ListenerUpdate.newBuilder().
      private Builder() {
      }

      Builder setListener(Listener listener) {
        this.listener = listener;
        return this;
      }

      ListenerUpdate build() {
        checkState(listener != null, "listener is not set");
        return new ListenerUpdate(listener);
      }
    }
  }

  /**
   * Watcher interface for a single requested xDS resource.
   */
  private interface ResourceWatcher {

    /**
     * Called when the resource discovery RPC encounters some transient error.
     */
    void onError(Status error);

    /**
     * Called when the requested resource is not available.
     *
     * @param resourceName name of the resource requested in discovery request.
     */
    void onResourceDoesNotExist(String resourceName);
  }

  /**
   * Config watcher interface. To be implemented by the xDS resolver.
   */
  interface ConfigWatcher extends ResourceWatcher {

    /**
     * Called when receiving an update on virtual host configurations.
     */
    void onConfigChanged(ConfigUpdate update);
  }

  /**
   * Cluster watcher interface.
   */
  interface ClusterWatcher extends ResourceWatcher {

    void onClusterChanged(ClusterUpdate update);
  }

  /**
   * Endpoint watcher interface.
   */
  interface EndpointWatcher extends ResourceWatcher {

    void onEndpointChanged(EndpointUpdate update);
  }

  /**
   * Listener watcher interface. To be used by {@link io.grpc.xds.internal.sds.XdsServerBuilder}.
   */
  interface ListenerWatcher extends ResourceWatcher {

    /**
     * Called when receiving an update on Listener configuration.
     */
    void onListenerChanged(ListenerUpdate update);
  }

  /**
   * Shutdown this {@link XdsClient} and release resources.
   */
  abstract void shutdown();

  /**
   * Registers a watcher to receive {@link ConfigUpdate} for service with the given target
   * authority.
   *
   * <p>Unlike watchers for cluster data and endpoint data, at most one ConfigWatcher can be
   * registered. Once it is registered, it cannot be unregistered.
   *
   * @param targetAuthority authority of the "xds:" URI for the server name that the gRPC client
   *     targets for.
   * @param watcher the {@link ConfigWatcher} to receive {@link ConfigUpdate}.
   */
  void watchConfigData(String targetAuthority, ConfigWatcher watcher) {
  }

  /**
   * Registers a data watcher for the given cluster.
   */
  void watchClusterData(String clusterName, ClusterWatcher watcher) {
  }

  /**
   * Unregisters the given cluster watcher, which was registered to receive updates for the
   * given cluster.
   */
  void cancelClusterDataWatch(String clusterName, ClusterWatcher watcher) {
  }

  /**
   * Registers a data watcher for endpoints in the given cluster.
   */
  void watchEndpointData(String clusterName, EndpointWatcher watcher) {
  }

  /**
   * Unregisters the given endpoints watcher, which was registered to receive updates for
   * endpoints information in the given cluster.
   */
  void cancelEndpointDataWatch(String clusterName, EndpointWatcher watcher) {
  }

  /**
   * Registers a watcher for a Listener with the given port.
   */
  void watchListenerData(List<String> listenerResourceIds, int port, ListenerWatcher watcher) {
  }

  /**
   * Starts client side load reporting via LRS. All clusters report load through one LRS stream,
   * only the first call of this method effectively starts the LRS stream.
   */
  void reportClientStats() {
  }

  /**
   * Stops client side load reporting via LRS. All clusters report load through one LRS stream,
   * only the last call of this method effectively stops the LRS stream.
   */
  void cancelClientStatsReport() {
  }

  /**
   * Starts recording client load stats for the given cluster:cluster_service. Caller should use
   * the returned {@link LoadStatsStore} to record and aggregate stats for load sent to the given
   * cluster:cluster_service. Recorded stats may be reported to a load reporting server if enabled.
   */
  LoadStatsStore addClientStats(String clusterName, @Nullable String clusterServiceName) {
    throw new UnsupportedOperationException();
  }

  /**
   * Stops recording client load stats for the given cluster:cluster_service. The load reporting
   * server will no longer receive stats for the given cluster:cluster_service after this call.
   */
  void removeClientStats(String clusterName, @Nullable String clusterServiceName) {
    throw new UnsupportedOperationException();
  }

  // TODO(chengyuanzhang): eliminate this factory
  abstract static class XdsClientFactory {
    abstract XdsClient createXdsClient();
  }

  /**
   * An {@link ObjectPool} holding reference and ref-count of an {@link XdsClient} instance.
   * Initially the instance is null and the ref-count is zero. {@link #getObject()} will create a
   * new XdsClient instance if the ref-count is zero when calling the method. {@code #getObject()}
   * increments the ref-count and {@link #returnObject(Object)} decrements it. Anytime when the
   * ref-count gets back to zero, the XdsClient instance will be shutdown and de-referenced.
   */
  static final class RefCountedXdsClientObjectPool implements ObjectPool<XdsClient> {

    private final XdsClientFactory xdsClientFactory;

    @VisibleForTesting
    @Nullable
    XdsClient xdsClient;

    private int refCount;

    RefCountedXdsClientObjectPool(XdsClientFactory xdsClientFactory) {
      this.xdsClientFactory = Preconditions.checkNotNull(xdsClientFactory, "xdsClientFactory");
    }

    /**
     * See {@link RefCountedXdsClientObjectPool}.
     */
    @Override
    public synchronized XdsClient getObject() {
      if (xdsClient == null) {
        checkState(
            refCount == 0,
            "Bug: refCount should be zero while xdsClient is null");
        xdsClient = xdsClientFactory.createXdsClient();
      }
      refCount++;
      return xdsClient;
    }

    /**
     * See {@link RefCountedXdsClientObjectPool}.
     */
    @Override
    public synchronized XdsClient returnObject(Object object) {
      checkState(
          object == xdsClient,
          "Bug: the returned object '%s' does not match current XdsClient '%s'",
          object,
          xdsClient);

      refCount--;
      checkState(refCount >= 0, "Bug: refCount of XdsClient less than 0");
      if (refCount == 0) {
        xdsClient.shutdown();
        xdsClient = null;
      }

      return null;
    }
  }

  /**
   * Factory for creating channels to xDS severs.
   */
  abstract static class XdsChannelFactory {
    @VisibleForTesting
    static boolean experimentalV3SupportEnvVar = Boolean.parseBoolean(
        System.getenv("GRPC_XDS_EXPERIMENTAL_V3_SUPPORT"));

    private static final String XDS_V3_SERVER_FEATURE = "xds_v3";
    private static final XdsChannelFactory DEFAULT_INSTANCE = new XdsChannelFactory() {
      /**
       * Creates a channel to the first server in the given list.
       */
      @Override
      XdsChannel createChannel(List<ServerInfo> servers) {
        checkArgument(!servers.isEmpty(), "No management server provided.");
        XdsLogger logger = XdsLogger.withPrefix("xds-client-channel-factory");
        ServerInfo serverInfo = servers.get(0);
        String serverUri = serverInfo.getServerUri();
        logger.log(XdsLogLevel.INFO, "Creating channel to {0}", serverUri);
        List<ChannelCreds> channelCredsList = serverInfo.getChannelCredentials();
        ManagedChannelBuilder<?> channelBuilder = null;
        // Use the first supported channel credentials configuration.
        // Currently, only "google_default" is supported.
        for (ChannelCreds creds : channelCredsList) {
          if (creds.getType().equals("google_default")) {
            logger.log(XdsLogLevel.INFO, "Using channel credentials: google_default");
            channelBuilder = GoogleDefaultChannelBuilder.forTarget(serverUri);
            break;
          }
        }
        if (channelBuilder == null) {
          logger.log(XdsLogLevel.INFO, "Using default channel credentials");
          channelBuilder = ManagedChannelBuilder.forTarget(serverUri);
        }

        ManagedChannel channel = channelBuilder
            .keepAliveTime(5, TimeUnit.MINUTES)
            .build();
        boolean useProtocolV3 = experimentalV3SupportEnvVar
            && serverInfo.getServerFeatures().contains(XDS_V3_SERVER_FEATURE);

        return new XdsChannel(channel, useProtocolV3);
      }
    };

    static XdsChannelFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    /**
     * Creates a channel to one of the provided management servers.
     */
    abstract XdsChannel createChannel(List<ServerInfo> servers);
  }

  static final class XdsChannel {
    private final ManagedChannel managedChannel;
    private final boolean useProtocolV3;

    @VisibleForTesting
    XdsChannel(ManagedChannel managedChannel, boolean useProtocolV3) {
      this.managedChannel = managedChannel;
      this.useProtocolV3 = useProtocolV3;
    }

    ManagedChannel getManagedChannel() {
      return managedChannel;
    }

    boolean isUseProtocolV3() {
      return useProtocolV3;
    }
  }

  interface XdsClientPoolFactory {
    ObjectPool<XdsClient> newXdsClientObjectPool(BootstrapInfo bootstrapInfo);
  }
}
