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

package io.grpc.xds.tls;

import io.netty.handler.ssl.util.SimpleTrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;

public final class SdsTrustManagerFactory extends SimpleTrustManagerFactory {

  private TrustManager tm;

  public SdsTrustManagerFactory(InputStream certChain) {
    this.tm = new SdsX509TrustManager(certChain);
  }

  @Override
  protected void engineInit(KeyStore keyStore) throws Exception {}

  @Override
  protected void engineInit(ManagerFactoryParameters managerFactoryParameters) throws Exception {}

  @Override
  protected TrustManager[] engineGetTrustManagers() {
    return new TrustManager[] {tm};
  }
}
