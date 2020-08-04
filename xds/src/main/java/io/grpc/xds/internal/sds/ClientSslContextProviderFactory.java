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

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.internal.sds.ReferenceCountingMap.ValueFactory;
import java.io.IOException;
import java.util.concurrent.Executors;

/** Factory to create client-side SslContextProvider from UpstreamTlsContext. */
final class ClientSslContextProviderFactory
    implements ValueFactory<UpstreamTlsContext, SslContextProvider> {

  /** Creates an SslContextProvider from the given UpstreamTlsContext. */
  @Override
  public SslContextProvider create(UpstreamTlsContext upstreamTlsContext) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    checkNotNull(
        upstreamTlsContext.getCommonTlsContext(),
        "upstreamTlsContext should have CommonTlsContext");
    if (CommonTlsContextUtil.hasAllSecretsUsingFilename(upstreamTlsContext.getCommonTlsContext())) {
      return SecretVolumeClientSslContextProvider.getProvider(upstreamTlsContext);
    } else if (CommonTlsContextUtil.hasAllSecretsUsingSds(
        upstreamTlsContext.getCommonTlsContext())) {
      try {
        return SdsClientSslContextProvider.getProvider(
            upstreamTlsContext,
            Bootstrapper.getInstance().readBootstrap().getNode().toEnvoyProtoNodeV2(),
            Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setNameFormat("client-sds-sslcontext-provider-%d")
                .setDaemon(true)
                .build()),
            /* channelExecutor= */ null);
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    } else if (CommonTlsContextUtil.hasCertProviderInstance(
            upstreamTlsContext.getCommonTlsContext())) {
      try {
        return CertProviderClientSslContextProvider.getProvider(
              upstreamTlsContext,
              Bootstrapper.getInstance().readBootstrap().getNode().toEnvoyProtoNode(),
              Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                      .setNameFormat("client-certprovider-sslcontext-provider-%d")
                      .setDaemon(true)
                      .build()),
              /* channelExecutor= */ null);
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
    throw new UnsupportedOperationException(
        "UpstreamTlsContext to have all filenames or all SdsConfig");
  }
}
