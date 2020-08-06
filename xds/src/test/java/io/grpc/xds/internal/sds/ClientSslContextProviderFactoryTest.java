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

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CA_PEM_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_KEY_FILE;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.CLIENT_PEM_FILE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import io.grpc.xds.internal.certprovider.*;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;

/** Unit tests for {@link ClientSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ClientSslContextProviderFactoryTest {

  Bootstrapper bootstrapper;
  CertificateProviderRegistry certificateProviderRegistry;
  CertificateProviderStore certificateProviderStore;
  CertProviderClientSslContextProvider.Factory certProviderClientSslContextProviderFactory;
  ClientSslContextProviderFactory clientSslContextProviderFactory;

  @Before
  public void setUp() {
    bootstrapper = mock(Bootstrapper.class);
    certificateProviderRegistry = new CertificateProviderRegistry();
    certificateProviderStore = new CertificateProviderStore(certificateProviderRegistry);
    certProviderClientSslContextProviderFactory = new CertProviderClientSslContextProvider.Factory(certificateProviderStore);
    clientSslContextProviderFactory = new ClientSslContextProviderFactory(bootstrapper, certProviderClientSslContextProviderFactory);
  }

  @Test
  public void createSslContextProvider_allFilenames() {
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextFromFilenames(
            CLIENT_KEY_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);

    SslContextProvider sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider).isNotNull();
  }

  @Test
  public void createSslContextProvider_sdsConfigForTlsCert_expectException() {
    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextFromSdsConfigForTlsCertificate(
            /* name= */ "name", /* targetUri= */ "unix:/tmp/sds/path", CA_PEM_FILE);
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContext(commonTlsContext);

    try {
      SslContextProvider unused =
          clientSslContextProviderFactory.create(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("unexpected TlsCertificateSdsSecretConfigs");
    }
  }

  @Test
  public void createSslContextProvider_sdsConfigForCertValidationContext_expectException() {
    CommonTlsContext commonTlsContext =
        CommonTlsContextTestsUtil.buildCommonTlsContextFromSdsConfigForValidationContext(
            /* name= */ "name",
            /* targetUri= */ "unix:/tmp/sds/path",
            CLIENT_KEY_FILE,
            CLIENT_PEM_FILE);
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContext(commonTlsContext);

    try {
      SslContextProvider unused =
          clientSslContextProviderFactory.create(upstreamTlsContext);
      Assert.fail("no exception thrown");
    } catch (IllegalStateException expected) {
      assertThat(expected)
          .hasMessageThat()
          .isEqualTo("incorrect ValidationContextTypeCase");
    }
  }

  @Test
  public void createCertProviderClientSslContextProvider() throws IOException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor = new CertificateProvider.DistributorWatcher[1];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
            "gcp_id", "cert-default", "gcp_id", "root-default", null);

    Bootstrapper.BootstrapInfo bootstrapInfo = TestCertificateProvider.getTestBootstrapInfo();
    when(bootstrapper.readBootstrap()).thenReturn(bootstrapInfo);
    SslContextProvider sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider).isInstanceOf(CertProviderClientSslContextProvider.class);
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
  }

  @Test
  public void createCertProviderClientSslContextProvider_2providers() throws IOException {
    final CertificateProvider.DistributorWatcher[] watcherCaptor = new CertificateProvider.DistributorWatcher[2];
    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);

    createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "file_watcher", 1);

    UpstreamTlsContext upstreamTlsContext =
            CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
                    "gcp_id", "cert-default", "file_provider", "root-default", null);

    Bootstrapper.BootstrapInfo bootstrapInfo = TestCertificateProvider.getTestBootstrapInfo();
    when(bootstrapper.readBootstrap()).thenReturn(bootstrapInfo);
    SslContextProvider sslContextProvider =
            clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider).isInstanceOf(CertProviderClientSslContextProvider.class);
    verifyWatcher(sslContextProvider, watcherCaptor[0]);
    verifyWatcher(sslContextProvider, watcherCaptor[1]);
  }

  static void createAndRegisterProviderProvider(CertificateProviderRegistry certificateProviderRegistry,
  final CertificateProvider.DistributorWatcher[] watcherCaptor, String testca, final int i) {
    final CertificateProviderProvider mockProviderProviderTestCa = mock(CertificateProviderProvider.class);
    when(mockProviderProviderTestCa.getName()).thenReturn(testca);

    when(mockProviderProviderTestCa.createCertificateProvider(any(Object.class),
            any(CertificateProvider.DistributorWatcher.class), eq(true))).thenAnswer(new Answer<CertificateProvider>() {
      @Override
      public CertificateProvider answer(InvocationOnMock invocation) throws Throwable {
        Object[] args = invocation.getArguments();
        CertificateProvider.DistributorWatcher watcher = (CertificateProvider.DistributorWatcher) args[1];
        watcherCaptor[i] = watcher;
        return new TestCertificateProvider(watcher,
                true,
                args[0],
                mockProviderProviderTestCa,
                false);
      }
    });
    certificateProviderRegistry.register(mockProviderProviderTestCa);
  }

  private void verifyWatcher(SslContextProvider sslContextProvider, CertificateProvider.DistributorWatcher watcherCaptor) {
    assertThat(watcherCaptor).isNotNull();
    assertThat(watcherCaptor.getDownsstreamWatchers()).hasSize(1);
    assertThat(watcherCaptor.getDownsstreamWatchers().iterator().next()).isSameInstanceAs(sslContextProvider);
  }

}
