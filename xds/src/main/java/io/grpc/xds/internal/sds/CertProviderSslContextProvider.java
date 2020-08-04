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

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.CertificateProviderInstance;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.grpc.Status;
import io.grpc.xds.EnvoyServerProtoData.BaseTlsContext;
import io.grpc.xds.internal.certprovider.CertificateProvider;
import io.grpc.xds.internal.certprovider.CertificateProviderStore;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.annotation.Nullable;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkNotNull;

/** Base class for  CertProviderSslContextProvider and CertProviderSslContextProvider. */
abstract class CertProviderSslContextProvider extends SslContextProvider implements CertificateProvider.Watcher {

  private static final Logger logger = Logger.getLogger(CertProviderSslContextProvider.class.getName());

  @Nullable private final CertificateProviderStore.Handle certHandle;
  @Nullable private final CertificateProviderStore.Handle rootCertHandle;
  @Nullable private final CertificateProviderInstance certInstance;
  @Nullable private final CertificateProviderInstance rootCertInstance;
  @Nullable protected final CertificateValidationContext staticCertificateValidationContext;
  private final List<CallbackPair> pendingCallbacks = new ArrayList<>();
  @Nullable protected SslContext sslContext;
  @Nullable protected PrivateKey lastKey;
  @Nullable protected List<X509Certificate> lastCertChain;
  @Nullable protected List<X509Certificate> lastTrustedRoots;

  CertProviderSslContextProvider(
      Node node,
      CertificateProviderInstance certInstance,
      CertificateProviderInstance rootCertInstance,
      CertificateValidationContext staticCertValidationContext,
      Executor watcherExecutor,
      Executor channelExecutor,
      BaseTlsContext tlsContext) {
    super(tlsContext);
    this.certInstance = certInstance;
    this.rootCertInstance = rootCertInstance;
    this.staticCertificateValidationContext = staticCertValidationContext;
    if (certInstance != null && certInstance.isInitialized()) {
      certHandle =
          CertificateProviderStore.getInstance()
              .createOrGetProvider(
                  certInstance.getCertificateName(),
                  certInstance.getInstanceName(),
                  getCertProviderConfig(node),
                  this,
                  true);
    } else {
      certHandle = null;
    }
    if (rootCertInstance != null && rootCertInstance.isInitialized()) {
      rootCertHandle =
          CertificateProviderStore.getInstance()
              .createOrGetProvider(
                  rootCertInstance.getCertificateName(),
                  rootCertInstance.getInstanceName(),
                  getCertProviderConfig(node),
                  this,
                  true);
    } else {
      rootCertHandle = null;
    }
  }

  protected Object getCertProviderConfig(Node node) {
    // TODO: implement
    return null;
  }

  @Override
  public void addCallback(Callback callback, Executor executor) {
    checkNotNull(callback, "callback");
    checkNotNull(executor, "executor");
    // if there is a computed sslContext just send it
    SslContext sslContextCopy = sslContext;
    if (sslContextCopy != null) {
      callPerformCallback(callback, executor, sslContextCopy);
    } else {
      synchronized (pendingCallbacks) {
        pendingCallbacks.add(new CallbackPair(callback, executor));
      }
    }
  }

  private void callPerformCallback(
      Callback callback, Executor executor, final SslContext sslContextCopy) {
    performCallback(
        new SslContextGetter() {
          @Override
          public SslContext get() {
            return sslContextCopy;
          }
        },
        callback,
        executor);
  }

  @Override
  public void updateCertificate(PrivateKey key, List<X509Certificate> certChain) {
    lastKey = key;
    lastCertChain = certChain;
    updateSslContextWhenReady();
  }

  @Override
  public void updateTrustedRoots(List<X509Certificate> trustedRoots) {
    lastTrustedRoots = trustedRoots;
    updateSslContextWhenReady();
  }

  @Override
  public void onError(Status error) {
    synchronized (pendingCallbacks) {
      for (CallbackPair callbackPair : pendingCallbacks) {
        callbackPair.callback.onException(error.asException());
      }
      pendingCallbacks.clear();
    }
  }

  /** Gets a server or client side SslContextBuilder. */
  abstract SslContextBuilder getSslContextBuilder()
      throws CertificateException, IOException, CertStoreException;

  private void updateSslContextWhenReady() {
    if (isMtls()) {
      if (lastKey != null && lastTrustedRoots != null) {
        updateSslContext();
      }
    } else if (isClientSideTls()) { // client side TLS
      if (lastTrustedRoots != null) {
        updateSslContext();
      }
    } else if (isServerSideTls()) {  // server side TLS
      if (lastKey != null) {
        updateSslContext();
      }
    } else {
      throw new IllegalStateException("Non mTLS, TLS state seen!");
    }
  }

  protected boolean isMtls() {
    return certInstance != null && rootCertInstance != null;
  }

  protected boolean isClientSideTls() {
    return rootCertInstance != null;
  }

  protected boolean isServerSideTls() {
    return certInstance != null;
  }

  // this gets called only when requested secrets are ready...
  private void updateSslContext() {
    try {
      SslContextBuilder sslContextBuilder = getSslContextBuilder();
      CommonTlsContext commonTlsContext = getCommonTlsContext();
      if (commonTlsContext != null && commonTlsContext.getAlpnProtocolsCount() > 0) {
        List<String> alpnList = commonTlsContext.getAlpnProtocolsList();
        ApplicationProtocolConfig apn = new ApplicationProtocolConfig(
            ApplicationProtocolConfig.Protocol.ALPN,
            ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
            ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
            alpnList);
        sslContextBuilder.applicationProtocolConfig(apn);
      }
      SslContext sslContextCopy = sslContextBuilder.build();
      sslContext = sslContextCopy;
      makePendingCallbacks(sslContextCopy);
      lastKey = null;
      lastTrustedRoots = null;
      lastCertChain = null;
    } catch (CertificateException | IOException | CertStoreException e) {
      logger.log(Level.SEVERE, "exception in updateSslContext", e);
    }
  }

  private void makePendingCallbacks(SslContext sslContextCopy) {
    synchronized (pendingCallbacks) {
      for (CallbackPair pair : pendingCallbacks) {
        callPerformCallback(pair.callback, pair.executor, sslContextCopy);
      }
      pendingCallbacks.clear();
    }
  }

  @Override
  public void close() {
    if (certHandle != null) {
      certHandle.close();
    }
    if (rootCertHandle != null) {
      rootCertHandle.close();
    }
  }

  private static class CallbackPair {
    private final Callback callback;
    private final Executor executor;

    private CallbackPair(Callback callback, Executor executor) {
      this.callback = callback;
      this.executor = executor;
    }
  }
}
