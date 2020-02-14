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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext.CombinedCertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.type.FractionalPercent;
import io.envoyproxy.envoy.type.FractionalPercent.DenominatorType;
import io.grpc.EquivalentAddressGroup;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defines gRPC data types for Envoy protobuf messages used in xDS protocol. Each data type has
 * the same name as Envoy's corresponding protobuf message, but only with fields used by gRPC.
 *
 * <p>Each data type should define a {@code fromEnvoyProtoXXX} static method to convert an Envoy
 * proto message to an instance of that data type.
 *
 * <p>For data types that need to be sent as protobuf messages, a {@code toEnvoyProtoXXX} instance
 * method is defined to convert an instance to Envoy proto message.
 */
final class EnvoyProtoData {

  // Prevent instantiation.
  private EnvoyProtoData() {
  }

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.core.Locality}.
   */
  static final class Locality {
    private final String region;
    private final String zone;
    private final String subzone;

    /** Must only be used for testing. */
    @VisibleForTesting
    Locality(String region, String zone, String subzone) {
      this.region = region;
      this.zone = zone;
      this.subzone = subzone;
    }

    static Locality fromEnvoyProtoLocality(io.envoyproxy.envoy.api.v2.core.Locality locality) {
      return new Locality(
          /* region = */ locality.getRegion(),
          /* zone = */ locality.getZone(),
          /* subzone = */ locality.getSubZone());
    }

    io.envoyproxy.envoy.api.v2.core.Locality toEnvoyProtoLocality() {
      return io.envoyproxy.envoy.api.v2.core.Locality.newBuilder()
          .setRegion(region)
          .setZone(zone)
          .setSubZone(subzone)
          .build();
    }

    String getRegion() {
      return region;
    }

    String getZone() {
      return zone;
    }

    String getSubzone() {
      return subzone;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Locality locality = (Locality) o;
      return Objects.equal(region, locality.region)
          && Objects.equal(zone, locality.zone)
          && Objects.equal(subzone, locality.subzone);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(region, zone, subzone);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("region", region)
          .add("zone", zone)
          .add("subzone", subzone)
          .toString();
    }
  }

  /**
   * See corresponding Envoy proto message {@link
   * io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints}.
   */
  static final class LocalityLbEndpoints {
    private final List<LbEndpoint> endpoints;
    private final int localityWeight;
    private final int priority;

    /** Must only be used for testing. */
    @VisibleForTesting
    LocalityLbEndpoints(List<LbEndpoint> endpoints, int localityWeight, int priority) {
      this.endpoints = endpoints;
      this.localityWeight = localityWeight;
      this.priority = priority;
    }

    static LocalityLbEndpoints fromEnvoyProtoLocalityLbEndpoints(
        io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints proto) {
      List<LbEndpoint> endpoints = new ArrayList<>(proto.getLbEndpointsCount());
      for (io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint endpoint : proto.getLbEndpointsList()) {
        endpoints.add(LbEndpoint.fromEnvoyProtoLbEndpoint(endpoint));
      }
      return
          new LocalityLbEndpoints(
              endpoints,
              proto.getLoadBalancingWeight().getValue(),
              proto.getPriority());
    }

    List<LbEndpoint> getEndpoints() {
      return Collections.unmodifiableList(endpoints);
    }

    int getLocalityWeight() {
      return localityWeight;
    }

    int getPriority() {
      return priority;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LocalityLbEndpoints that = (LocalityLbEndpoints) o;
      return localityWeight == that.localityWeight
          && priority == that.priority
          && Objects.equal(endpoints, that.endpoints);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(endpoints, localityWeight, priority);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("endpoints", endpoints)
          .add("localityWeight", localityWeight)
          .add("priority", priority)
          .toString();
    }
  }

  /**
   * See corresponding Envoy proto message {@link io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint}.
   */
  static final class LbEndpoint {
    private final EquivalentAddressGroup eag;
    private final int loadBalancingWeight;
    private final boolean isHealthy;

    @VisibleForTesting
    LbEndpoint(String address, int port, int loadBalancingWeight, boolean isHealthy) {
      this(
          new EquivalentAddressGroup(
              new InetSocketAddress(address, port)),
          loadBalancingWeight, isHealthy);
    }

    @VisibleForTesting
    LbEndpoint(EquivalentAddressGroup eag, int loadBalancingWeight, boolean isHealthy) {
      this.eag = eag;
      this.loadBalancingWeight = loadBalancingWeight;
      this.isHealthy = isHealthy;
    }

    static LbEndpoint fromEnvoyProtoLbEndpoint(
        io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint proto) {
      io.envoyproxy.envoy.api.v2.core.SocketAddress socketAddress =
          proto.getEndpoint().getAddress().getSocketAddress();
      InetSocketAddress addr =
          new InetSocketAddress(socketAddress.getAddress(), socketAddress.getPortValue());
      return
          new LbEndpoint(
              new EquivalentAddressGroup(ImmutableList.<java.net.SocketAddress>of(addr)),
              proto.getLoadBalancingWeight().getValue(),
              proto.getHealthStatus() == io.envoyproxy.envoy.api.v2.core.HealthStatus.HEALTHY
                  || proto.getHealthStatus() == io.envoyproxy.envoy.api.v2.core.HealthStatus.UNKNOWN
              );
    }

    EquivalentAddressGroup getAddress() {
      return eag;
    }

    int getLoadBalancingWeight() {
      return loadBalancingWeight;
    }

    boolean isHealthy() {
      return isHealthy;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      LbEndpoint that = (LbEndpoint) o;
      return loadBalancingWeight == that.loadBalancingWeight
          && Objects.equal(eag, that.eag)
          && isHealthy == that.isHealthy;
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(eag, loadBalancingWeight, isHealthy);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("eag", eag)
          .add("loadBalancingWeight", loadBalancingWeight)
          .add("isHealthy", isHealthy)
          .toString();
    }
  }

  /**
   * See corresponding Enovy proto message {@link
   * io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload}.
   */
  static final class DropOverload {
    private final String category;
    private final int dropsPerMillion;

    /** Must only be used for testing. */
    @VisibleForTesting
    DropOverload(String category, int dropsPerMillion) {
      this.category = category;
      this.dropsPerMillion = dropsPerMillion;
    }

    static DropOverload fromEnvoyProtoDropOverload(
        io.envoyproxy.envoy.api.v2.ClusterLoadAssignment.Policy.DropOverload proto) {
      FractionalPercent percent = proto.getDropPercentage();
      int numerator = percent.getNumerator();
      DenominatorType type = percent.getDenominator();
      switch (type) {
        case TEN_THOUSAND:
          numerator *= 100;
          break;
        case HUNDRED:
          numerator *= 100_00;
          break;
        case MILLION:
          break;
        default:
          throw new IllegalArgumentException("Unknown denominator type of " + percent);
      }

      if (numerator > 1_000_000) {
        numerator = 1_000_000;
      }

      return new DropOverload(proto.getCategory(), numerator);
    }

    String getCategory() {
      return category;
    }

    int getDropsPerMillion() {
      return dropsPerMillion;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DropOverload that = (DropOverload) o;
      return dropsPerMillion == that.dropsPerMillion && Objects.equal(category, that.category);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(category, dropsPerMillion);
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(this)
          .add("category", category)
          .add("dropsPerMillion", dropsPerMillion)
          .toString();
    }
  }

  static final class CidrRange {
    private final String addressPrefix;
    private final int prefixLen;

    public CidrRange(String addressPrefix, int prefixLen) {
      this.addressPrefix = addressPrefix;
      this.prefixLen = prefixLen;
    }

    public String getAddressPrefix() {
      return addressPrefix;
    }

    public int getPrefixLen() {
      return prefixLen;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CidrRange cidrRange = (CidrRange) o;
      return prefixLen == cidrRange.prefixLen
          && java.util.Objects.equals(addressPrefix, cidrRange.addressPrefix);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(addressPrefix, prefixLen);
    }

    @Override
    public String toString() {
      return "CidrRange{"
          + "addressPrefix='" + addressPrefix + '\''
          + ", prefixLen=" + prefixLen
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto message
   * {@link io.envoyproxy.envoy.api.v2.listener.FilterChainMatch}.
   */
  static final class FilterChainMatch {
    private final int destinationPort;
    private final List<CidrRange> prefixRanges;
    private final List<String> applicationProtocols;

    public FilterChainMatch(int destinationPort,
        List<CidrRange> prefixRanges, List<String> applicationProtocols) {
      this.destinationPort = destinationPort;
      this.prefixRanges = prefixRanges;
      this.applicationProtocols = applicationProtocols;
    }

    static FilterChainMatch fromEnvoyProtoFilterChainMatch(
        io.envoyproxy.envoy.api.v2.listener.FilterChainMatch proto) {
      return null;
    }

    public int getDestinationPort() {
      return destinationPort;
    }

    public List<CidrRange> getPrefixRanges() {
      return prefixRanges;
    }

    public List<String> getApplicationProtocols() {
      return applicationProtocols;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FilterChainMatch that = (FilterChainMatch) o;
      return destinationPort == that.destinationPort
          && java.util.Objects.equals(prefixRanges, that.prefixRanges)
          && java.util.Objects.equals(applicationProtocols, that.applicationProtocols);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(destinationPort, prefixRanges, applicationProtocols);
    }

    @Override
    public String toString() {
      return "FilterChainMatch{"
          + "destinationPort=" + destinationPort
          + ", prefixRanges=" + prefixRanges
          + ", applicationProtocols=" + applicationProtocols
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto
   * {@link io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc.CallCredentials}.
   */
  static final class CallCredentials {
    // fields of MetadataCredentialsFromPlugin start here
    private final String name;
    private final String headerKey;
    private final String secretDataFilename;

    public CallCredentials(String name, String headerKey, String secretDataFilename) {
      this.name = name;
      this.headerKey = headerKey;
      this.secretDataFilename = secretDataFilename;
    }

    public String getName() {
      return name;
    }

    public String getHeaderKey() {
      return headerKey;
    }

    public String getSecretDataFilename() {
      return secretDataFilename;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      CallCredentials that = (CallCredentials) o;
      return java.util.Objects.equals(name, that.name)
          && java.util.Objects.equals(headerKey, that.headerKey)
          && java.util.Objects.equals(secretDataFilename, that.secretDataFilename);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(name, headerKey, secretDataFilename);
    }

    @Override
    public String toString() {
      return "CallCredentials{"
          + "name='" + name + '\''
          + ", headerKey='" + headerKey + '\''
          + ", secretDataFilename='" + secretDataFilename + '\''
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto {@link io.envoyproxy.envoy.api.v2.core.GrpcService}.
   */
  static final class GrpcService {
    // fields of GoogleGrpc start here
    private final String targetUri;
    private final List<CallCredentials> callCredentials;
    private final String statPrefix;
    private final String credentialsFactoryName;

    public GrpcService(String targetUri,
        List<CallCredentials> callCredentials, String statPrefix,
        String credentialsFactoryName) {
      this.targetUri = targetUri;
      this.callCredentials = callCredentials;
      this.statPrefix = statPrefix;
      this.credentialsFactoryName = credentialsFactoryName;
    }

    static GrpcService fromEnvoyProtoGrpcService(
        io.envoyproxy.envoy.api.v2.core.GrpcService grpcService) {
      return null;
    }

    public String getTargetUri() {
      return targetUri;
    }

    public List<CallCredentials> getCallCredentials() {
      return callCredentials;
    }

    public String getStatPrefix() {
      return statPrefix;
    }

    public String getCredentialsFactoryName() {
      return credentialsFactoryName;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      GrpcService that = (GrpcService) o;
      return java.util.Objects.equals(targetUri, that.targetUri)
          && java.util.Objects.equals(callCredentials, that.callCredentials)
          && java.util.Objects.equals(statPrefix, that.statPrefix)
          && java.util.Objects.equals(credentialsFactoryName, that.credentialsFactoryName);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(targetUri, callCredentials, statPrefix, credentialsFactoryName);
    }

    @Override
    public String toString() {
      return "GrpcService{"
          + "targetUri='" + targetUri + '\''
          + ", callCredentials=" + callCredentials
          + ", statPrefix='" + statPrefix + '\''
          + ", credentialsFactoryName='" + credentialsFactoryName + '\''
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto {@link io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType}.
   */
  enum ApiType {
    UNSUPPORTED_REST_LEGACY(0),
    REST(1),
    GRPC(2),
    DELTA_GRPC(3);

    private final int value;

    ApiType(int value) {
      this.value = value;
    }
  }

  /**
   * Corresponds to Envoy proto {@link io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig}.
   */
  static final class SdsSecretConfig {
    private final String name;

    // fields of ConfigSource start here
    // fields of ApiConfigSource start here
    private final ApiType apiType;
    private final List<GrpcService> grpcServices;

    public SdsSecretConfig(String name, ApiType apiType,
        List<GrpcService> grpcServices) {
      this.name = name;
      this.apiType = apiType;
      this.grpcServices = grpcServices;
    }

    static SdsSecretConfig fromEnvoyProtoSdsSecretConfig(
        io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig proto) {
      ApiType apiType = null;
      List<GrpcService> grpcServices = new ArrayList<>();
      if (proto.getSdsConfig().hasApiConfigSource()) {
        ApiConfigSource apiConfigSource = proto.getSdsConfig().getApiConfigSource();
        apiType = ApiType.valueOf(apiConfigSource.getApiType().name());
        for (io.envoyproxy.envoy.api.v2.core.GrpcService grpcService :
            apiConfigSource.getGrpcServicesList()) {
          grpcServices.add(GrpcService.fromEnvoyProtoGrpcService(grpcService));
        }
      }
      return new SdsSecretConfig(proto.getName(), apiType, grpcServices);
    }

    public String getName() {
      return name;
    }

    public ApiType getApiType() {
      return apiType;
    }

    public List<GrpcService> getGrpcServices() {
      return grpcServices;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SdsSecretConfig that = (SdsSecretConfig) o;
      return java.util.Objects.equals(name, that.name)
          && apiType == that.apiType
          && java.util.Objects.equals(grpcServices, that.grpcServices);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(name, apiType, grpcServices);
    }

    @Override
    public String toString() {
      return "SdsSecretConfig{"
          + "name='" + name + '\''
          + ", apiType=" + apiType
          + ", grpcServices=" + grpcServices
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto message {@link io.envoyproxy.envoy.api.v2.listener.FilterChain}.
   */
  static final class FilterChain {
    private final FilterChainMatch filterChainMatch;

    // following are fields of DownstreamTlsContext
    private final List<SdsSecretConfig> tlsCertificateSdsSecretConfigs;

    // fields of CombinedCertificateValidationContext start
    // fields of CertificateValidationContext start
    private final List<String> verifySubjectAltName;
    private final SdsSecretConfig validationContextSdsSecretConfig;
    private final boolean requireClientCertificate;
    private final boolean requireSni;

    public FilterChain(FilterChainMatch filterChainMatch,
        List<SdsSecretConfig> tlsCertificateSdsSecretConfigs,
        List<String> verifySubjectAltName,
        SdsSecretConfig validationContextSdsSecretConfig, boolean requireClientCertificate,
        boolean requireSni) {
      this.filterChainMatch = filterChainMatch;
      this.tlsCertificateSdsSecretConfigs = tlsCertificateSdsSecretConfigs;
      this.verifySubjectAltName = verifySubjectAltName;
      this.validationContextSdsSecretConfig = validationContextSdsSecretConfig;
      this.requireClientCertificate = requireClientCertificate;
      this.requireSni = requireSni;
    }

    static FilterChain fromEnvoyProtoFilterChain(
        io.envoyproxy.envoy.api.v2.listener.FilterChain proto) {
      List<SdsSecretConfig> tlsCertificateSdsSecretConfigs = new ArrayList<>();
      List<String> verifySubjectAltName = new ArrayList<>();
      SdsSecretConfig validationContextSdsSecretConfig = null;
      boolean requireClientCertificate = false;
      boolean requireSni = false;
      if (proto.hasTlsContext()) {
        DownstreamTlsContext downstreamTlsContext = proto.getTlsContext();
        if (downstreamTlsContext.hasCommonTlsContext()) {
          final CommonTlsContext commonTlsContext = proto.getTlsContext().getCommonTlsContext();
          for (io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig sdsSecretConfig :
              commonTlsContext.getTlsCertificateSdsSecretConfigsList()) {
            tlsCertificateSdsSecretConfigs.add(SdsSecretConfig.fromEnvoyProtoSdsSecretConfig(
                sdsSecretConfig));
          }
          if (commonTlsContext.hasCombinedValidationContext()) {
            CombinedCertificateValidationContext combinedContext =
                commonTlsContext.getCombinedValidationContext();
            if (combinedContext.hasDefaultValidationContext()) {
              verifySubjectAltName =
                  commonTlsContext.getCombinedValidationContext().getDefaultValidationContext()
                      .getVerifySubjectAltNameList();
            }
            validationContextSdsSecretConfig =
                SdsSecretConfig.fromEnvoyProtoSdsSecretConfig(
                    combinedContext.getValidationContextSdsSecretConfig());
          }
        }
        requireClientCertificate = downstreamTlsContext.getRequireClientCertificate().getValue();
        requireSni = downstreamTlsContext.getRequireSni().getValue();
      }
      return new FilterChain(
          FilterChainMatch.fromEnvoyProtoFilterChainMatch(proto.getFilterChainMatch()),
          tlsCertificateSdsSecretConfigs,
          verifySubjectAltName,
          validationContextSdsSecretConfig,
          requireClientCertificate,
          requireSni
          );
    }

    public FilterChainMatch getFilterChainMatch() {
      return filterChainMatch;
    }

    public List<SdsSecretConfig> getTlsCertificateSdsSecretConfigs() {
      return tlsCertificateSdsSecretConfigs;
    }

    public List<String> getVerifySubjectAltName() {
      return verifySubjectAltName;
    }

    public SdsSecretConfig getValidationContextSdsSecretConfig() {
      return validationContextSdsSecretConfig;
    }

    public boolean isRequireClientCertificate() {
      return requireClientCertificate;
    }

    public boolean isRequireSni() {
      return requireSni;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      FilterChain that = (FilterChain) o;
      return requireClientCertificate == that.requireClientCertificate
          && requireSni == that.requireSni
          && java.util.Objects.equals(filterChainMatch, that.filterChainMatch)
          && java.util.Objects
              .equals(tlsCertificateSdsSecretConfigs, that.tlsCertificateSdsSecretConfigs)
          && java.util.Objects.equals(verifySubjectAltName, that.verifySubjectAltName)
          && java.util.Objects
              .equals(validationContextSdsSecretConfig, that.validationContextSdsSecretConfig);
    }

    @Override
    public int hashCode() {
      return java.util.Objects
          .hash(filterChainMatch, tlsCertificateSdsSecretConfigs, verifySubjectAltName,
              validationContextSdsSecretConfig, requireClientCertificate, requireSni);
    }

    @Override
    public String toString() {
      return "FilterChain{"
          + "filterChainMatch=" + filterChainMatch
          + ", tlsCertificateSdsSecretConfigs=" + tlsCertificateSdsSecretConfigs
          + ", verifySubjectAltName=" + verifySubjectAltName
          + ", validationContextSdsSecretConfig=" + validationContextSdsSecretConfig
          + ", requireClientCertificate=" + requireClientCertificate
          + ", requireSni=" + requireSni
          + '}';
    }
  }

  /**
   * Corresponds to Envoy proto message {@link io.envoyproxy.envoy.api.v2.Listener} & related
   * classes.
   */
  static final class Listener {
    private final String name;
    private final String address;
    private final List<FilterChain> filterChains;

    public Listener(String name, String address,
        List<FilterChain> filterChains) {
      this.name = name;
      this.address = address;
      this.filterChains = filterChains;
    }

    static String fromEnvoyProtoAddress(io.envoyproxy.envoy.api.v2.core.Address proto) {
      return proto.hasSocketAddress() ? proto.getSocketAddress().getAddress() : null;
    }

    static Listener fromEnvoyProtoListener(io.envoyproxy.envoy.api.v2.Listener proto) {
      List<FilterChain> filterChains = new ArrayList<>(proto.getFilterChainsCount());
      for (io.envoyproxy.envoy.api.v2.listener.FilterChain filterChain :
          proto.getFilterChainsList()) {
        filterChains.add(FilterChain.fromEnvoyProtoFilterChain(filterChain));
      }
      return new Listener(
          proto.getName(),
          fromEnvoyProtoAddress(proto.getAddress()),
          filterChains);
    }

    public String getName() {
      return name;
    }

    public String getAddress() {
      return address;
    }

    public List<FilterChain> getFilterChains() {
      return filterChains;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      Listener listener = (Listener) o;
      return java.util.Objects.equals(name, listener.name)
          &&  java.util.Objects.equals(address, listener.address)
          &&  java.util.Objects.equals(filterChains, listener.filterChains);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(name, address, filterChains);
    }

    @Override
    public String toString() {
      return "Listener{"
          + "name='" + name + '\''
          + ", address='" + address + '\''
          + ", filterChains=" + filterChains
          + '}';
    }
  }
}
