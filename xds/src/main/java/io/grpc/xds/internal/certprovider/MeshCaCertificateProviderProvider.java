/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.certprovider;

import io.grpc.internal.ExponentialBackoffPolicy;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provider of {@link CertificateProvider}s. Implemented by the implementer of the plugin. We may
 * move this out of the internal package and make this an official API in the future.
 */
final class MeshCaCertificateProviderProvider implements CertificateProviderProvider {
  private static final Logger logger = Logger.getLogger(MeshCaCertificateProviderProvider.class.getName());

  static {
    try {
      CertificateProviderRegistry.getInstance().register(new MeshCaCertificateProviderProvider());
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Registering MeshCaCertificateProviderProvider", t);
    }
  }

  @Override
  public String getName() {
    return "meshCA";
  }

  @Override
  public CertificateProvider createCertificateProvider(
          Object config, CertificateProvider.DistributorWatcher watcher, boolean notifyCertUpdates) {
    return new MeshCaCertificateProvider(watcher, notifyCertUpdates, "meshca.googleapis.com", null,
            TimeUnit.HOURS.toSeconds(9L), 2048, "RSA", "SHA256withRSA",
            MeshCaCertificateProvider.ChannelFactory.getInstance(), new ExponentialBackoffPolicy.Provider(),
            TimeUnit.HOURS.toSeconds(1L), 3);
  }
}
