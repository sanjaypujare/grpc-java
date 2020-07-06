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

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Status;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.internal.certprovider.CertificateProvider.Watcher;
import io.grpc.xds.internal.sds.ReferenceCountingMap;
import io.grpc.xds.internal.sds.SslContextProvider;

import javax.annotation.concurrent.ThreadSafe;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Global map for ref-counted CertificateProvider instances. */
@ThreadSafe
public final class CertificateProviderStore {
  private static CertificateProviderStore instance;
  private final CertificateProviderRegistry certificateProviderRegistry;
  private final ReferenceCountingMap<CertProviderKey, CertificateProvider> certProviderMap;

  // an opaque Handle returned by createOrGetProvider
  static interface Handle extends java.io.Closeable {
    // user of CertificateProvider calls close() to release the
    // CertificateProvider which removes the associated Watcher from the list,
    // decrements the ref-count and if 0 closes the provider by calling close()
    // on that CertificateProvider
    @Override
    public void close();
  }

  private static class CertProviderKey {
    String certName;
    String pluginName;
    boolean notifyCertUpdates;
    Object config;

    public CertProviderKey(String certName, String pluginName, boolean notifyCertUpdates, Object config) {
      this.certName = certName;
      this.pluginName = pluginName;
      this.notifyCertUpdates = notifyCertUpdates;
      this.config = config;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof CertProviderKey)) {
        return false;
      }
      CertProviderKey that = (CertProviderKey) o;
      return notifyCertUpdates == that.notifyCertUpdates
          && Objects.equals(certName, that.certName)
          && Objects.equals(pluginName, that.pluginName)
          && Objects.equals(config, that.config);
    }

    @Override
    public int hashCode() {
      return Objects.hash(certName, pluginName, notifyCertUpdates, config);
    }

    @Override
    public String toString() {
      return "CertProviderKey{" +
              "certName='" + certName + '\'' +
              ", pluginName='" + pluginName + '\'' +
              ", notifyCertUpdates=" + notifyCertUpdates +
              ", config=" + config +
              '}';
    }
  }

  @VisibleForTesting
  static class DistributorWatcher implements CertificateProvider.Watcher {
    @VisibleForTesting
    ArrayList<Watcher> downsstreamWatchers = new ArrayList<>();

    synchronized void addWatcher(Watcher watcher) {
      downsstreamWatchers.add(watcher);
    }

    synchronized void removeWatcher(Watcher watcher) {
      downsstreamWatchers.remove(watcher);
    }

    @Override
    public void updateCertificate(PrivateKey key, List<X509Certificate> certChain) {
      for (Watcher watcher : downsstreamWatchers) {
        watcher.updateCertificate(key, certChain);
      }
    }

    @Override
    public void updateTrustedRoots(List<X509Certificate> trustedRoots) {
      for (Watcher watcher : downsstreamWatchers) {
        watcher.updateTrustedRoots(trustedRoots);
      }
    }

    @Override
    public void onError(Status errorStatus) {
      for (Watcher watcher : downsstreamWatchers) {
        watcher.onError(errorStatus);
      }
    }
  }

  private class CertProviderFactory
      implements ReferenceCountingMap.ValueFactory<CertProviderKey, CertificateProvider> {

    private CertProviderFactory() {
    }

    @Override
    public CertificateProvider create(CertProviderKey key) {
      CertificateProviderProvider certProviderProvider =
          certificateProviderRegistry.getProvider(key.pluginName);
      if (certProviderProvider == null) {
        throw new IllegalArgumentException("Provider not found.");
      }
      return certProviderProvider.createCertificateProvider(
          key.config, new DistributorWatcher(), key.notifyCertUpdates);
    }
  }

  @VisibleForTesting
  CertificateProviderStore(CertificateProviderRegistry certificateProviderRegistry) {
    this.certificateProviderRegistry = certificateProviderRegistry;
    certProviderMap = new ReferenceCountingMap<>(new CertProviderFactory());
  }

  @VisibleForTesting
  final class HandleImpl implements Handle {
    private final CertProviderKey key;
    private final Watcher watcher;
    @VisibleForTesting
    final CertificateProvider certProvider;

    private HandleImpl(CertProviderKey key, Watcher watcher, CertificateProvider certProvider) {
      this.key = key;
      this.watcher = watcher;
      this.certProvider = certProvider;
    }

    // user of CertificateProvider calls close() to release the
    // CertificateProvider which removes the associated Watcher from the list,
    // decrements the ref-count and if 0 closes the provider by calling close()
    // on that CertificateProvider
    @Override
    public synchronized void close() {
      DistributorWatcher distWatcher = (DistributorWatcher)certProvider.getWatcher();
      distWatcher.downsstreamWatchers.remove(watcher);
      certProviderMap.release(key, certProvider);
    }
  }

  /**
   * creates or uses an existing CertificateProvider instance, increments its // ref-count and
   * registers the watcher passed. Returns a Handle // When notifyCertUpdates is false the caller
   * cannot depend on receiving // updateCertificate callbacks on the Watcher. However even with //
   * notifyCertUpdates == false, the Store may call updateCertificate on // the Watcher which should
   * be ignored.
   */
  public synchronized Handle createOrGetProvider(
      String certName,
      String pluginName,
      Object config,
      Watcher watcher,
      boolean notifyCertUpdates) {
    CertProviderKey key = new CertProviderKey(certName, pluginName, notifyCertUpdates, config);
    CertificateProvider provider = certProviderMap.get(key);
    DistributorWatcher distWatcher = (DistributorWatcher)provider.getWatcher();
    distWatcher.addWatcher(watcher);
    return new HandleImpl(key, watcher, provider);
  }

  /** gets the CertificateProviderStore singleton instance. */
  public static synchronized CertificateProviderStore getInstance() {
    if (instance == null) {
      instance = new CertificateProviderStore(CertificateProviderRegistry.getInstance());
    }
    return instance;
  }
}
