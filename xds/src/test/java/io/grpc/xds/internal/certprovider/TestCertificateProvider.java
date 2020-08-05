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

public class TestCertificateProvider extends CertificateProvider {
  Object config;
  CertificateProviderProvider certProviderProvider;
  int closeCalled = 0;
  int startCalled = 0;

  public TestCertificateProvider(
          DistributorWatcher watcher,
          boolean notifyCertUpdates,
          Object config,
          CertificateProviderProvider certificateProviderProvider,
          boolean throwExceptionForCertUpdates) {
    super(watcher, notifyCertUpdates);
    if (throwExceptionForCertUpdates && notifyCertUpdates) {
      throw new UnsupportedOperationException("Provider does not support Certificate Updates.");
    }
    this.config = config;
    this.certProviderProvider = certificateProviderProvider;
  }

  @Override
  public void close() {
    closeCalled++;
  }

  @Override
  public void start() {
    startCalled++;
  }
}
