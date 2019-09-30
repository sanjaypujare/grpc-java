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

package io.grpc.xds.tls;

import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.UnrecoverableKeyException;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManagerFactorySpi;
import javax.net.ssl.ManagerFactoryParameters;


public class SdsKeyManagerFactory extends KeyManagerFactory {

  private static final Provider PROVIDER = new Provider("", 0.0, "") {
    private static final long serialVersionUID = -2680540247105807895L;
  };

  protected SdsKeyManagerFactory(
      KeyManagerFactorySpi factorySpi, Provider provider, String algorithm) {
    super(factorySpi, provider, algorithm);
  }

  /**
   * Constructor for SdsKeyManagerFactory.
   *
   * @param privateKey  filename containing key
   * @param certChain   filename containing certchain
   */
  public SdsKeyManagerFactory(String privateKey, String certChain) {
    this(new SdsKeyManagerFactorySpi(privateKey, certChain), null,
        null);
    System.out.println("SdsKeyManagerFactory ctor");
  }

  public static final class SdsKeyManagerFactorySpi extends KeyManagerFactorySpi {

    KeyManager[] keyManagers = new KeyManager[1];

    public SdsKeyManagerFactorySpi(String privateKey, String certChain) {

      keyManagers[0] = new SdsX509ExtendedKeyManager(privateKey, certChain);
    }

    @Override
    protected void engineInit(KeyStore keyStore, char[] chars)
        throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
    }

    @Override
    protected void engineInit(ManagerFactoryParameters managerFactoryParameters)
        throws InvalidAlgorithmParameterException {
    }

    @Override
    protected KeyManager[] engineGetKeyManagers() {
      return keyManagers;
    }
  }
}
