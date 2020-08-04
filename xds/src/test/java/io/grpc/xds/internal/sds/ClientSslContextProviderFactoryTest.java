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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.EnvoyServerProtoData.UpstreamTlsContext;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;

/** Unit tests for {@link ClientSslContextProviderFactory}. */
@RunWith(JUnit4.class)
public class ClientSslContextProviderFactoryTest {

  Bootstrapper bootstrapper;
  ClientSslContextProviderFactory clientSslContextProviderFactory;

  @Before
  public void setUp() {
    bootstrapper = mock(Bootstrapper.class);
    clientSslContextProviderFactory = new ClientSslContextProviderFactory(bootstrapper);
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
    UpstreamTlsContext upstreamTlsContext =
        CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
            "gcp_id", "cert-default", "gcp_id", "root-default");

    String rawData = "{\n"
            + "  \"node\": {\n"
            + "    \"id\": \"ENVOY_NODE_ID\",\n"
            + "    \"cluster\": \"ENVOY_CLUSTER\",\n"
            + "    \"locality\": {\n"
            + "      \"region\": \"ENVOY_REGION\",\n"
            + "      \"zone\": \"ENVOY_ZONE\",\n"
            + "      \"sub_zone\": \"ENVOY_SUBZONE\"\n"
            + "    },\n"
            + "    \"metadata\": {\n"
            + "      \"TRAFFICDIRECTOR_INTERCEPTION_PORT\": \"ENVOY_PORT\",\n"
            + "      \"TRAFFICDIRECTOR_NETWORK_NAME\": \"VPC_NETWORK_NAME\"\n"
            + "    }\n"
            + "  },\n"
            + "  \"xds_servers\": [\n"
            + "    {\n"
            + "      \"server_uri\": \"trafficdirector-bar.googleapis.com:443\",\n"
            + "      \"channel_creds\": []\n"
            + "    }\n"
            + "  ]\n"
            + "}";
    Bootstrapper.BootstrapInfo bootstrapInfo = bootstrapper.parseConfig(rawData);
    when(bootstrapper.readBootstrap()).thenReturn(bootstrapInfo);
    SslContextProvider sslContextProvider =
        clientSslContextProviderFactory.create(upstreamTlsContext);
    assertThat(sslContextProvider).isNotNull();
  }

}
