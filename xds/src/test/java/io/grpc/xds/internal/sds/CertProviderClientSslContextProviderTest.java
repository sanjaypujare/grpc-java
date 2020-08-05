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

import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import io.grpc.Status.Code;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.internal.certprovider.CertificateProviderRegistry;
import io.grpc.xds.internal.certprovider.CertificateProviderStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.*;
import static io.grpc.xds.internal.sds.SdsClientTest.getOneCertificateValidationContextSecret;
import static io.grpc.xds.internal.sds.SdsClientTest.getOneTlsCertSecret;
import static io.grpc.xds.internal.sds.SecretVolumeSslContextProviderTest.doChecksOnSslContext;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Unit tests for {@link CertProviderClientSslContextProvider}. */
@RunWith(JUnit4.class)
public class CertProviderClientSslContextProviderTest {

  CertificateProviderRegistry certificateProviderRegistry;
  CertificateProviderStore certificateProviderStore;
  private CertProviderClientSslContextProvider.Factory certProviderClientSslContextProviderFactory;
  private TestSdsServer.ServerMock serverMock;
  private TestSdsServer server;
  private Node node;

  @Before
  public void setUp() throws Exception {
    certificateProviderRegistry = new CertificateProviderRegistry();
    certificateProviderStore = new CertificateProviderStore(certificateProviderRegistry);
    certProviderClientSslContextProviderFactory =
      new CertProviderClientSslContextProvider.Factory(certificateProviderStore);

    node = Node.newBuilder().setId("sds-client-temp-test1").build();
  }

  @After
  public void teardown() throws InterruptedException {
  }

  /** Helper method to build CertProviderClientSslContextProvider. */
  private CertProviderClientSslContextProvider getSslContextProvider(String certInstanceName, String rootInstanceName,
                                                                     Bootstrapper.BootstrapInfo bootstrapInfo) {
    EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext =
            CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
                    certInstanceName, "cert-default", rootInstanceName, "root-default");
    return certProviderClientSslContextProviderFactory.getProvider(upstreamTlsContext, bootstrapInfo.getNode().toEnvoyProtoNode(),
        bootstrapInfo.getCertProviders(), MoreExecutors.directExecutor(), MoreExecutors.directExecutor());
  }

  @Test
  public void testProviderForClient() throws IOException {
    when(serverMock.getSecretFor(/* name= */ "cert1"))
        .thenReturn(getOneTlsCertSecret(/* name= */ "cert1", CLIENT_KEY_FILE, CLIENT_PEM_FILE));
    when(serverMock.getSecretFor("valid1"))
        .thenReturn(getOneCertificateValidationContextSecret(/* name= */ "valid1", CA_PEM_FILE));

    CertProviderClientSslContextProvider provider =
            getSslContextProvider("testca", "testca", testBootstrapInfo());

    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  // copy remaining methods from SdsSslContextProviderTest


  private Bootstrapper.BootstrapInfo testBootstrapInfo() throws IOException {
    String rawData =
            "{\n"
                    + "  \"xds_servers\": [],\n"
                    + "  \"certificate_providers\": {\n"
                    + "    \"gcp_id\": {\n"
                    + "      \"plugin_name\": \"testca\",\n"
                    + "      \"config\": {\n"
                    + "        \"server\": {\n"
                    + "          \"api_type\": \"GRPC\",\n"
                    + "          \"grpc_services\": [{\n"
                    + "            \"google_grpc\": {\n"
                    + "              \"target_uri\": \"meshca.com\",\n"
                    + "              \"channel_credentials\": {\"google_default\": {}},\n"
                    + "              \"call_credentials\": [{\n"
                    + "                \"sts_service\": {\n"
                    + "                  \"token_exchange_service\": \"securetoken.googleapis.com\",\n"
                    + "                  \"subject_token_path\": \"/etc/secret/sajwt.token\"\n"
                    + "                }\n"
                    + "              }]\n" // end call_credentials
                    + "            },\n" // end google_grpc
                    + "            \"time_out\": {\"seconds\": 10}\n"
                    + "          }]\n" // end grpc_services
                    + "        },\n" // end server
                    + "        \"certificate_lifetime\": {\"seconds\": 86400},\n"
                    + "        \"renewal_grace_period\": {\"seconds\": 3600},\n"
                    + "        \"key_type\": \"RSA\",\n"
                    + "        \"key_size\": 2048,\n"
                    + "        \"location\": \"https://container.googleapis.com/v1/project/test-project1/locations/test-zone2/clusters/test-cluster3\"\n"
                    + "      }\n" // end config
                    + "    },\n" // end gcp_id
                    + "    \"file_provider\": {\n"
                    + "      \"plugin_name\": \"file_watcher\",\n"
                    + "      \"config\": {\"path\": \"/etc/secret/certs\"}\n"
                    + "    }\n"
                    + "  }\n"
                    + "}";
    return Bootstrapper.parseConfig(rawData);
  }

}
