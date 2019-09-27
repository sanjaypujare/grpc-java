/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.netty;

import java.io.InputStream;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

public class SdsX509TrustManager extends X509ExtendedTrustManager implements X509TrustManager {
  final InputStream certChain;
  X509Certificate[] x509Certs;

  /**
   * Construct mgr.
   */
  SdsX509TrustManager(InputStream certChain) {
    this.certChain = certChain;
    try {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      Collection<? extends Certificate> certs = cf.generateCertificates(certChain);
      x509Certs = certs.toArray(new X509Certificate[0]);
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket)
      throws CertificateException {
    System.out.println("checkClientTrusted 1 called");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine)
          throws CertificateException {
    System.out.println("checkClientTrusted 2 called");
  }

  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
          throws CertificateException {
    System.out.println("checkClientTrusted 3 called");
  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket)
      throws CertificateException {
    System.out.println("checkServerTrusted 1 called");
  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine)
      throws CertificateException {
    System.out.println("checkServerTrusted 2 called: " + s);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
      throws CertificateException {
    System.out.println("checkServerTrusted 3 called");
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    System.out.println("getAcceptedIssuers called");
    return x509Certs;
  }
}
