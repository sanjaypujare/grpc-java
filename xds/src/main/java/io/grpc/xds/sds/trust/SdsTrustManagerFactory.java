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

package io.grpc.xds.sds.trust;

import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.grpc.Internal;
import io.netty.handler.ssl.util.SimpleTrustManagerFactory;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

@Internal
public final class SdsTrustManagerFactory extends SimpleTrustManagerFactory {

  private static final Logger logger = Logger.getLogger(SdsTrustManagerFactory.class.getName());

  private SdsX509TrustManager sdsX509TrustManager;

  /** Constructor that loads certs from a file. */
  public SdsTrustManagerFactory(
      String certsFile, @Nullable CertificateValidationContext certContext)
      throws CertificateException, IOException {
    this(CertificateUtils.toX509Certificates(certsFile), certContext);
  }

  /** Constructor that takes array of certs. */
  public SdsTrustManagerFactory(
      X509Certificate[] certs, @Nullable CertificateValidationContext certContext) {
    createSdsX509TrustManager(certs, certContext);
  }

  private void createSdsX509TrustManager(
      X509Certificate[] certs, CertificateValidationContext certContext) {
    TrustManagerFactory tmf = null;
    try {
      tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      KeyStore ks = KeyStore.getInstance("PKCS12");
      // perform a load to initialize KeyStore
      ks.load(/* stream= */ null, /* password= */ null);
      int i = 1;
      for (X509Certificate cert : certs) {
        // note: alias lookup uses toLowerCase(Locale.ENGLISH)
        // so our alias needs to be all lower-case and unique
        ks.setCertificateEntry("alias" + Integer.toString(i), cert);
        i++;
      }
      tmf.init(ks);
    } catch (NoSuchAlgorithmException | KeyStoreException | IOException | CertificateException e) {
      logger.log(Level.SEVERE, "createSdsX509TrustManager", e);
    }
    TrustManager[] tms = tmf.getTrustManagers();
    X509ExtendedTrustManager myDelegate = null;
    if (tms != null) {
      for (TrustManager tm : tms) {
        if (tm instanceof X509ExtendedTrustManager) {
          myDelegate = (X509ExtendedTrustManager) tm;
          break;
        }
      }
    }
    sdsX509TrustManager = new SdsX509TrustManager(certContext, myDelegate);
  }

  @Override
  protected void engineInit(KeyStore keyStore) throws Exception {}

  @Override
  protected void engineInit(ManagerFactoryParameters managerFactoryParameters) throws Exception {}

  @Override
  protected TrustManager[] engineGetTrustManagers() {
    return new TrustManager[] {sdsX509TrustManager};
  }
}
