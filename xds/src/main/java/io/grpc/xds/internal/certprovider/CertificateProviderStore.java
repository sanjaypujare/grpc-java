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

import io.grpc.xds.internal.certprovider.CertificateProvider.Watcher;
import javax.annotation.concurrent.ThreadSafe;

/** Global map for ref-counted CertificateProvider instances. */
@ThreadSafe
public class CertificateProviderStore {
  private static CertificateProviderStore instance;

  // an opaque Handle returned by createOrGetProvider
  static interface Handle extends java.io.Closeable {
    // user of CertificateProvider calls close() to release the
    // CertificateProvider which removes the associated Watcher from the list,
    // decrements the ref-count and if 0 closes the provider by calling close()
    // on that CertificateProvider
    @Override
    public void close();
  }

  /** creates or uses an existing CertificateProvider instance, increments its
  // ref-count and registers the watcher passed. Returns a Handle
  // When notifyCertUpdates is false the caller cannot depend on receiving
  // updateCertificate callbacks on the Watcher. However even with
  // notifyCertUpdates == false, the Store may call updateCertificate on
  // the Watcher which should be ignored.
  */
  public Handle createOrGetProvider(
      String name, Object config, Watcher watcher, boolean notifyCertUpdates) {
    return null;
  }

  /** gets the CertificateProviderStore singleton instance. */
  public static synchronized CertificateProviderStore getInstance() {
    if (instance == null) {
      instance = new CertificateProviderStore();
    }
    return instance;
  }
}
