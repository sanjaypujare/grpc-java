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

package io.grpc.xds.sds;

import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.auth.*;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.xds.Bootstrapper;
import io.netty.handler.ssl.SslContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.util.concurrent.Executor;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.sds.SdsClientTest.getOneCertificateValidationContextSecret;
import static io.grpc.xds.sds.SdsClientTest.getOneTlsCertSecret;
import static io.grpc.xds.sds.SecretVolumeSslContextProviderTest.doChecksOnSslContext;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link SdsSslContextProvider}. */
@RunWith(JUnit4.class)
public class SdsSslContextProviderTest {

  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CLIENT_PEM_FILE = "client.pem";
  private static final String CLIENT_KEY_FILE = "client.key";
  private static final String CA_PEM_FILE = "ca.pem";

  private TestSdsServer.ServerMock serverMock;
  private TestSdsServer server;
  private Node node;
  private Bootstrapper mockBootstrapper;

  @Before
  public void setUp() throws Exception {
    serverMock = mock(TestSdsServer.ServerMock.class);
    server = new TestSdsServer(serverMock);
    server.startServer("inproc", false);

    node = Node.newBuilder().setId("sds-client-temp-test1").build();
    mockBootstrapper = mock(Bootstrapper.class);
    Bootstrapper.BootstrapInfo bootstrapInfo = new Bootstrapper.BootstrapInfo(null, null, node);
    when(mockBootstrapper.readBootstrap()).thenReturn(bootstrapInfo);
  }

  @After
  public void teardown() throws InterruptedException {
    server.shutdown();
  }

  /** Helper method to build SdsSslContextProvider from given files. */
  private SdsSslContextProvider<?> getSdsSslContextProvider(
          boolean server, String certName, String validationContextName)
      throws IOException {

    CommonTlsContext commonTlsContext =
            ClientSslContextProviderFactoryTest.buildCommonTlsContextFromSdsConfigsForAll(
                    certName, "inproc", validationContextName,
                    "inproc", "inproc");

    return server
            ? SdsSslContextProvider.getProviderForServer(
            SecretVolumeSslContextProviderTest.buildDownstreamTlsContext(commonTlsContext), mockBootstrapper)
            : SdsSslContextProvider.getProviderForClient(
                    SecretVolumeSslContextProviderTest.buildUpstreamTlsContext(commonTlsContext), mockBootstrapper);
  }

  @Test
  public void testProviderForServer() throws IOException, CertificateException, CertStoreException {
    when(serverMock.getSecretFor("cert1"))
            .thenReturn(getOneTlsCertSecret("cert1", SERVER_1_KEY_FILE, SERVER_1_PEM_FILE));
    when(serverMock.getSecretFor("valid1"))
            .thenReturn(getOneCertificateValidationContextSecret("valid1", CA_PEM_FILE));


    SdsSslContextProvider<?> provider =
            getSdsSslContextProvider(true, "cert1", "valid1");
    SecretVolumeSslContextProviderTest.TestCallback testCallback =
      SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(true, testCallback.updatedSslContext);
  }

  /*
  @Test
  public void getProviderForClient() throws IOException, CertificateException, CertStoreException {
    sslContextForEitherWithBothCertAndTrust(false, CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE);
  }

  @Test
  public void getProviderForServer_onlyCert()
      throws IOException, CertificateException, CertStoreException {
    sslContextForEitherWithBothCertAndTrust(true, SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, null);
  }

  @Test
  public void getProviderForClient_onlyTrust()
      throws IOException, CertificateException, CertStoreException {
    sslContextForEitherWithBothCertAndTrust(false, null, null, CA_PEM_FILE);
  }

  @Test
  public void getProviderForServer_badFile_throwsException()
      throws IOException, CertificateException, CertStoreException {
    try {
      sslContextForEitherWithBothCertAndTrust(true, SERVER_1_PEM_FILE, SERVER_1_PEM_FILE, null);
      fail("no exception thrown");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("File does not contain valid private key");
    }
  }

  static class TestCallback implements SslContextProvider.Callback {

    SslContext updatedSslContext;
    Throwable updatedThrowable;

    @Override
    public void updateSecret(SslContext sslContext) {
      updatedSslContext = sslContext;
    }

    @Override
    public void onException(Throwable throwable) {
      updatedThrowable = throwable;
    }
  }

  @Test
  public void getProviderForServer_both_callsback() throws IOException {
    SecretVolumeSslContextProvider<?> provider =
        getSslContextSecretVolumeSecretProvider(
            true, SERVER_1_PEM_FILE, SERVER_1_KEY_FILE, CA_PEM_FILE);

    TestCallback testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(true, testCallback.updatedSslContext);
  }

  @Test
  public void getProviderForClient_both_callsback() throws IOException {
    SecretVolumeSslContextProvider<?> provider =
        getSslContextSecretVolumeSecretProvider(
            false, CLIENT_PEM_FILE, CLIENT_KEY_FILE, CA_PEM_FILE);

    TestCallback testCallback = getValueThruCallback(provider);
    doChecksOnSslContext(false, testCallback.updatedSslContext);
  }

  // note this test generates stack-trace but can be safely ignored
  @Test
  public void getProviderForClient_both_callsback_setException() throws IOException {
    SecretVolumeSslContextProvider<?> provider =
        getSslContextSecretVolumeSecretProvider(
            false, CLIENT_PEM_FILE, CLIENT_PEM_FILE, CA_PEM_FILE);
    TestCallback testCallback = getValueThruCallback(provider);
    assertThat(testCallback.updatedSslContext).isNull();
    assertThat(testCallback.updatedThrowable).isInstanceOf(IllegalArgumentException.class);
    assertThat(testCallback.updatedThrowable).hasMessageThat()
        .contains("File does not contain valid private key");
  }
  */
}
