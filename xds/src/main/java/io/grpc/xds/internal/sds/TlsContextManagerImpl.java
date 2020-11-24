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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class to manage {@link SslContextProvider} objects created from inputs we get from xDS. Used by
 * gRPC-xds to access the SslContext's and is not public API. This manager manages the life-cycle of
 * {@link SslContextProvider} objects as shared resources via ref-counting as described in {@link
 * ReferenceCountingMap}.
 */
public final class TlsContextManagerImpl implements TlsContextManager {
  private static final Logger logger = Logger.getLogger(TlsContextManagerImpl.class.getName());

  public static final String GOOGLE_CLOUD_PRIVATE_SPIFFE = "google_cloud_private_spiffe";
  private static TlsContextManagerImpl instance;

  private static final boolean CERT_INSTANCE_OVERRIDE =
      Boolean.parseBoolean(System.getenv("GRPC_XDS_CERT_INSTANCE_OVERRIDE"));

  private final ReferenceCountingMap<UpstreamTlsContext, SslContextProvider> mapForClients;
  private final ReferenceCountingMap<DownstreamTlsContext, SslContextProvider> mapForServers;
  private final boolean hasCertInstanceOverride;

  /** Create a TlsContextManagerImpl instance using the passed in {@link Bootstrapper}. */
  @VisibleForTesting public TlsContextManagerImpl(Bootstrapper bootstrapper) {
    this(
        new ClientSslContextProviderFactory(bootstrapper),
        new ServerSslContextProviderFactory(bootstrapper), CERT_INSTANCE_OVERRIDE);
  }

  @VisibleForTesting
  TlsContextManagerImpl(
      ValueFactory<UpstreamTlsContext, SslContextProvider> clientFactory,
      ValueFactory<DownstreamTlsContext, SslContextProvider> serverFactory,
      boolean certInstanceOverride) {
    checkNotNull(clientFactory, "clientFactory");
    checkNotNull(serverFactory, "serverFactory");
    mapForClients = new ReferenceCountingMap<>(clientFactory);
    mapForServers = new ReferenceCountingMap<>(serverFactory);
    this.hasCertInstanceOverride = certInstanceOverride;
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
    logger.log(Level.INFO, "downstreamTlsContext=" + downstreamTlsContext);
    CommonTlsContext.Builder builder = downstreamTlsContext.getCommonTlsContext().toBuilder();
    builder = performCertInstanceOverride(builder);
    downstreamTlsContext =
        new DownstreamTlsContext(
            builder.build(), downstreamTlsContext.isRequireClientCertificate());
    SslContextProvider tmp = mapForServers.get(downstreamTlsContext);
    logger.log(Level.INFO, "returning server SslContextProvider=" + tmp);
    return tmp;
  }

  @Override
  public SslContextProvider findOrCreateClientSslContextProvider(
      UpstreamTlsContext upstreamTlsContext) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    logger.log(Level.INFO, "upstreamTlsContext=" + upstreamTlsContext);
    CommonTlsContext.Builder builder = upstreamTlsContext.getCommonTlsContext().toBuilder();
    builder = performCertInstanceOverride(builder);
    upstreamTlsContext = new UpstreamTlsContext(builder.build());
    SslContextProvider tmp = mapForClients.get(upstreamTlsContext);
    logger.log(Level.INFO, "returning client SslContextProvider=" + tmp);
    return tmp;
  }

  @VisibleForTesting
  CommonTlsContext.Builder performCertInstanceOverride(CommonTlsContext.Builder builder) {
    if (hasCertInstanceOverride) {
      if (builder.getTlsCertificateSdsSecretConfigsCount() > 0) {
        logger.log(Level.INFO, "Doing CertProviderInstanceOverride for tlsCert");
        builder.setTlsCertificateCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.newBuilder()
                .setInstanceName(GOOGLE_CLOUD_PRIVATE_SPIFFE));
      }
      if (builder.hasCombinedValidationContext()) {
        CommonTlsContext.CombinedCertificateValidationContext.Builder ccvcBuilder =
                builder.getCombinedValidationContextBuilder();
        if (ccvcBuilder.hasValidationContextSdsSecretConfig()) {
          logger
              .log(Level.INFO, "Doing CertProviderInstanceOverride for combinedValidationContext");
          ccvcBuilder =
              ccvcBuilder.setValidationContextCertificateProviderInstance(
                  CommonTlsContext.CertificateProviderInstance.newBuilder()
                      .setInstanceName(GOOGLE_CLOUD_PRIVATE_SPIFFE));
          builder.setCombinedValidationContext(ccvcBuilder);
        }
      } else if (builder.hasValidationContextSdsSecretConfig()) {
        logger.log(Level.INFO, "Doing CertProviderInstanceOverride for validationContext");
        builder.setValidationContextCertificateProviderInstance(
            CommonTlsContext.CertificateProviderInstance.newBuilder()
                .setInstanceName(GOOGLE_CLOUD_PRIVATE_SPIFFE));
      }
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
