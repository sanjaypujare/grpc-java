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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.sds.trust.SdsTrustManagerFactory;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An SslContext provider that uses SDS to fetch secrets. Used for both server and client
 * SslContexts
 */
final class SdsSslContextProvider<K> extends SslContextProvider<K> implements
    SdsClient.SecretWatcher {

  private static final Logger logger =
      Logger.getLogger(SdsSslContextProvider.class.getName());

  private final SdsClient certSdsClient;
  private final SdsClient validationContextSdsClient;
  private final SdsSecretConfig certSdsConfig;
  private final SdsSecretConfig validationContextSdsConfig;
  private final boolean server;
  ArrayList<Pair> pendingCallbacks = new ArrayList<>();
  private TlsCertificate tlsCertificate;
  private CertificateValidationContext certificateValidationContext;
  private SslContext sslContext;

  SdsSslContextProvider(
      Node node, SdsSecretConfig certSdsConfig, SdsSecretConfig validationContextSdsConfig,
      boolean server, K source) {
    super(source);
    this.server = server;
    this.certSdsConfig = certSdsConfig;
    this.validationContextSdsConfig = validationContextSdsConfig;
    if (certSdsConfig != null && certSdsConfig.isInitialized()) {
      certSdsClient = SdsClient.Factory.createSdsClient(certSdsConfig, node,
          Executors.newSingleThreadExecutor(), null);
      certSdsClient.start();
      certSdsClient.watchSecret(this);
    } else {
      certSdsClient = null;
    }
    if (validationContextSdsConfig != null && validationContextSdsConfig.isInitialized()) {
      validationContextSdsClient = SdsClient.Factory
          .createSdsClient(validationContextSdsConfig, node,
              Executors.newSingleThreadExecutor(), null);
      validationContextSdsClient.start();
      validationContextSdsClient.watchSecret(this);
    } else {
      validationContextSdsClient = null;
    }
  }

  static SdsSslContextProvider<UpstreamTlsContext> getProviderForClient(
      UpstreamTlsContext upstreamTlsContext, Bootstrapper bootstrapper) {
    checkNotNull(upstreamTlsContext, "upstreamTlsContext");
    CommonTlsContext commonTlsContext = upstreamTlsContext.getCommonTlsContext();
    SdsSecretConfig validationContextSdsConfig =
        commonTlsContext.getValidationContextSdsSecretConfig();

    // tlsCertificate exists in case of mTLS, else null for a client
    SdsSecretConfig certSdsConfig = null;
    if (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() > 0) {
      certSdsConfig = commonTlsContext.getTlsCertificateSdsSecretConfigs(0);
    }
    return new SdsSslContextProvider<>(getNodeFromBootstrap(bootstrapper), certSdsConfig,
        validationContextSdsConfig,
        false, upstreamTlsContext);
  }

  private static Node getNodeFromBootstrap(Bootstrapper bootstrapper) {
    Node node = null;
    try {
      node = bootstrapper.readBootstrap().getNode();
    } catch (Exception e) {
      logger.log(Level.SEVERE, "exception from Bootstrapper.readBootstrap()", e);
    }
    return node;
  }

  static SdsSslContextProvider<DownstreamTlsContext> getProviderForServer(
      DownstreamTlsContext downstreamTlsContext, Bootstrapper bootstrapper) {
    checkNotNull(downstreamTlsContext, "downstreamTlsContext");
    CommonTlsContext commonTlsContext = downstreamTlsContext.getCommonTlsContext();

    SdsSecretConfig certSdsConfig = null;
    if (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() > 0) {
      certSdsConfig = commonTlsContext.getTlsCertificateSdsSecretConfigs(0);
    }

    SdsSecretConfig validationContextSdsConfig = null;
    if (commonTlsContext.hasValidationContextSdsSecretConfig()) {
      validationContextSdsConfig = commonTlsContext.getValidationContextSdsSecretConfig();
    }
    return new SdsSslContextProvider<>(getNodeFromBootstrap(bootstrapper), certSdsConfig,
        validationContextSdsConfig,
        true, downstreamTlsContext);
  }

  @Override
  public void addCallback(Callback callback, Executor executor) {
    checkNotNull(callback, "callback");
    checkNotNull(executor, "executor");
    // if there is a computed sslContext just send it
    synchronized (this) {
      if (sslContext != null) {
        callPerformCallback(callback, executor);
      } else {
        pendingCallbacks.add(new Pair(callback, executor));
      }
    }
  }

  private void callPerformCallback(Callback callback, Executor executor) {
    performCallback(
        new SslContextGetter() {
          final SslContext sslContextCopy = sslContext;

          @Override
          public SslContext get() {
            return sslContextCopy;
          }
        },
        callback, executor);
  }

  void performCallback(final SslContextGetter sslContextGetter, final Callback callback,
      Executor executor) {
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            // as per the contract we will read the current secrets on disk
            // this involves I/O which can potentially block the executor or event loop
            SslContext sslContext = null;
            try {
              sslContext = sslContextGetter.get();
              try {
                callback.updateSecret(sslContext);
              } catch (Throwable t) {
                logger.log(Level.SEVERE, "Exception from callback.updateSecret", t);
              }
            } catch (Throwable e) {
              logger.log(Level.SEVERE, "Exception from buildSslContextFromSecrets", e);
              callback.onException(e);
            }
          }
        });
  }

  @Override
  public synchronized void onSecretChanged(Secret secretUpdate) {
    checkNotNull(secretUpdate);
    if (secretUpdate.hasTlsCertificate()) {
      checkState(secretUpdate.getName().equals(certSdsConfig.getName()),
          "tlsCert names don't match");
      tlsCertificate = secretUpdate.getTlsCertificate();
      if (certificateValidationContext != null || validationContextSdsConfig == null) {
        updateSslContext();
      }
    } else if (secretUpdate.hasValidationContext()) {
      checkState(secretUpdate.getName().equals(validationContextSdsConfig.getName()),
          "validationContext names don't match");
      certificateValidationContext = secretUpdate.getValidationContext();
      if (tlsCertificate != null || certSdsConfig == null) {
        updateSslContext();
      }
    } else {
      throw new UnsupportedOperationException(
          "Unexpected secret type:" + secretUpdate.getTypeCase());
    }

  }

  private void updateSslContext() {
    // both requested secrets are ready...
    try {
      SslContextBuilder sslContextBuilder;
      if (server) {
        sslContextBuilder =
            GrpcSslContexts.forServer(
                tlsCertificate.getCertificateChain().getInlineBytes().newInput(),
                tlsCertificate.getPrivateKey().getInlineBytes().newInput(),
                tlsCertificate.hasPassword() ? tlsCertificate.getPassword().getInlineString()
                    : null);
        if (certificateValidationContext != null) {
          sslContextBuilder.trustManager(new SdsTrustManagerFactory(certificateValidationContext));
        }
      } else {
        sslContextBuilder =
            GrpcSslContexts.forClient()
                .trustManager(new SdsTrustManagerFactory(certificateValidationContext));
        if (tlsCertificate != null) {
          sslContextBuilder.keyManager(
              tlsCertificate.getCertificateChain().getInlineBytes().newInput(),
              tlsCertificate.getPrivateKey().getInlineBytes().newInput(),
              tlsCertificate.hasPassword() ? tlsCertificate.getPassword().getInlineString() : null);
        }
      }
      sslContext = sslContextBuilder.build();
      makePendingCallbacks();
    } catch (CertificateException | IOException | CertStoreException e) {
      logger.log(Level.SEVERE, "", e);
    }
  }

  private void makePendingCallbacks() {
    for (Pair pair : pendingCallbacks) {
      callPerformCallback(pair.callback, pair.executor);
    }
    pendingCallbacks.clear();
  }

  @Override
  public void onError(Status error) {
    for (Pair pair : pendingCallbacks) {
      pair.callback.onException(error.asException());
    }
    pendingCallbacks.clear();
  }

  @Override
  void close() {
    if (certSdsClient != null) {
      certSdsClient.cancelSecretWatch(this);
      certSdsClient.shutdown();
    }
    if (validationContextSdsClient != null) {
      validationContextSdsClient.cancelSecretWatch(this);
      validationContextSdsClient.shutdown();
    }
  }

  interface SslContextGetter {

    SslContext get() throws Exception;
  }

  static class Pair {

    Callback callback;
    Executor executor;

    Pair(Callback callback, Executor executor) {
      this.callback = callback;
      this.executor = executor;
    }
  }
}
