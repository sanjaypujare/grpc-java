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

package io.grpc.xds.internal.certprovider;

import com.google.common.collect.ImmutableList;
import io.grpc.xds.internal.sds.ReferenceCountingMap;
import io.grpc.xds.internal.sds.ReferenceCountingMap.ValueFactory;
import io.grpc.xds.internal.sds.SslContextProvider;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

/** Unit tests for {@link CertificateProviderStore}. */
@RunWith(JUnit4.class)
public class CertificateProviderStoreTest {

  private CertificateProviderRegistry certificateProviderRegistry;
  private CertificateProviderStore certificateProviderStore;

  private static class TestCertificateProvider extends CertificateProvider {
    Object config;

    protected TestCertificateProvider(Watcher watcher, boolean notifyCertUpdates, Object config) {
      super(watcher, notifyCertUpdates);
      this.config = config;
    }

    @Override
    public void close() {

    }
  }

  @Before
  public void setUp() {
    certificateProviderRegistry = new CertificateProviderRegistry();
    certificateProviderStore = new CertificateProviderStore(certificateProviderRegistry);
  }

  @Test
  public void pluginNotRegistered_expectException() {
    CertificateProvider.Watcher mockWatcher = mock(CertificateProvider.Watcher.class);
    try {
      CertificateProviderStore.Handle unused =
        certificateProviderStore.createOrGetProvider(
            "cert-name1", "plugin1", "config", mockWatcher, true);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().isEqualTo("Provider not found.");
    }
  }

  @Test
  public void onePluginsameConfig_sameInstance() {
    CertificateProviderProvider certProviderProvider = mock(CertificateProviderProvider.class);
    when(certProviderProvider.getName()).thenReturn("plugin1");
    when(certProviderProvider.createCertificateProvider(
            any(Object.class), any(CertificateProvider.Watcher.class), any(Boolean.TYPE)))
        .then(
            new Answer<CertificateProvider>() {

              @Override
              public CertificateProvider answer(InvocationOnMock invocation) throws Throwable {
                Object[] args = invocation.getArguments();
                Object config = args[0];
                CertificateProvider.Watcher watcher = (CertificateProvider.Watcher)args[1];
                boolean notifyCertUpdates = (Boolean)args[2];
                return new TestCertificateProvider(watcher, notifyCertUpdates, config);
              }
            });
    certificateProviderRegistry.register(certProviderProvider);
    CertificateProvider.Watcher mockWatcher1 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle1 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher1, true);
    CertificateProvider.Watcher mockWatcher2 = mock(CertificateProvider.Watcher.class);
    CertificateProviderStore.HandleImpl handle2 =
        (CertificateProviderStore.HandleImpl)
            certificateProviderStore.createOrGetProvider(
                "cert-name1", "plugin1", "config", mockWatcher2, true);
    assertThat(handle1).isNotSameInstanceAs(handle2);
    assertThat(handle1.certProvider).isSameInstanceAs(handle2.certProvider);
    assertThat(handle1.certProvider).isInstanceOf(TestCertificateProvider.class);
    TestCertificateProvider testCertificateProvider = (TestCertificateProvider)handle1.certProvider;
    CertificateProviderStore.DistributorWatcher distWatcher = (CertificateProviderStore.DistributorWatcher)testCertificateProvider.watcher;
    assertThat(distWatcher.downsstreamWatchers.size()).isEqualTo(2);
    PrivateKey testKey = mock(PrivateKey.class);
    X509Certificate cert = mock(X509Certificate.class);
    List<X509Certificate> testList = ImmutableList.of(cert);
    testCertificateProvider.watcher.updateCertificate(testKey, testList);
    verify(mockWatcher1, times(1)).updateCertificate(eq(testKey), eq(testList));
    verify(mockWatcher2, times(1)).updateCertificate(eq(testKey), eq(testList));
  }
}
