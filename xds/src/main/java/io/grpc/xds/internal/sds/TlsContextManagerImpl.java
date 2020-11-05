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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.EnvoyServerProtoData.DownstreamTlsContext;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.internal.sds.ReferenceCountingMap.ValueFactory;

/**
 * Class to manage {@link SslContextProvider} objects created from inputs we get from xDS. Used by
 * gRPC-xds to access the SslContext's and is not public API. This manager manages the life-cycle of
 * {@link SslContextProvider} objects as shared resources via ref-counting as described in {@link
 * ReferenceCountingMap}.
 */
public final class TlsContextManagerImpl implements TlsContextManager {

  private static TlsContextManagerImpl instance;

  private final ReferenceCountingMap<UpstreamTlsContext, SslContextProvider> mapForClients;
  private final ReferenceCountingMap<DownstreamTlsContext, SslContextProvider> mapForServers;

  /** Create a TlsContextManagerImpl instance using the passed in {@link Bootstrapper}. */
  @VisibleForTesting public TlsContextManagerImpl(Bootstrapper bootstrapper) {
    this(
        new ClientSslContextProviderFactory(bootstrapper),
        new ServerSslContextProviderFactory(bootstrapper));
  }

  @VisibleForTesting
  TlsContextManagerImpl(
      ValueFactory<UpstreamTlsContext, SslContextProvider> clientFactory,
      ValueFactory<DownstreamTlsContext, SslContextProvider> serverFactory) {
    checkNotNull(clientFactory, "clientFactory");
    checkNotNull(serverFactory, "serverFactory");
    mapForClients = new ReferenceCountingMap<>(clientFactory);
    mapForServers = new ReferenceCountingMap<>(serverFactory);
  }

  /** Gets the TlsContextManagerImpl singleton. */
  public static synchronized TlsContextManagerImpl getInstance() {
    if (instance == null) {
      instance = new TlsContextManagerImpl(Bootstrapper.getInstance());
    }
    return instance;
  }

  @Override
  public SslContextProvider findOrCreateServerSslContextProvider(
      DownstreamTlsContext downstreamTlsContext) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    CommonTlsContext.Builder builder = downstreamTlsContext.getCommonTlsContext().toBuilder();
    builder = addCertProviderInstance(builder);
    downstreamTlsContext =
        new DownstreamTlsContext(
            builder.build(), downstreamTlsContext.isRequireClientCertificate());
    return mapForServers.get(downstreamTlsContext);
  }

  @Override
  public SslContextProvider findOrCreateClientSslContextProvider(
      UpstreamTlsContext upstreamTlsContext) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    CommonTlsContext.Builder builder = upstreamTlsContext.getCommonTlsContext().toBuilder();
    builder = addCertProviderInstance(builder);
    upstreamTlsContext = new UpstreamTlsContext(builder.build());
    return mapForClients.get(upstreamTlsContext);
  }

  private static CommonTlsContext.Builder addCertProviderInstance(
      CommonTlsContext.Builder builder) {
    builder.setTlsCertificateCertificateProviderInstance(
        CommonTlsContext.CertificateProviderInstance.newBuilder().setInstanceName("gke-cas-certs"));
    if (builder.hasCombinedValidationContext()) {
      CommonTlsContext.CombinedCertificateValidationContext.Builder ccvcBuilder =
          builder
              .getCombinedValidationContextBuilder()
              .setValidationContextCertificateProviderInstance(
                  CommonTlsContext.CertificateProviderInstance.newBuilder()
                      .setInstanceName("gke-cas-certs"));
      builder.setCombinedValidationContext(ccvcBuilder);
    } else if (builder.hasValidationContext()) {
      builder.setValidationContextCertificateProviderInstance(
          CommonTlsContext.CertificateProviderInstance.newBuilder()
              .setInstanceName("gke-cas-certs"));
    }
    return builder;
  }

  @Override
  public SslContextProvider releaseClientSslContextProvider(
      SslContextProvider clientSslContextProvider) {
    checkNotNull(clientSslContextProvider, "clientSslContextProvider");
    return mapForClients.release(clientSslContextProvider.getUpstreamTlsContext(),
        clientSslContextProvider);
  }

  @Override
  public SslContextProvider releaseServerSslContextProvider(
      SslContextProvider serverSslContextProvider) {
    checkNotNull(serverSslContextProvider, "serverSslContextProvider");
    return mapForServers.release(serverSslContextProvider.getDownstreamTlsContext(),
        serverSslContextProvider);
  }
}
