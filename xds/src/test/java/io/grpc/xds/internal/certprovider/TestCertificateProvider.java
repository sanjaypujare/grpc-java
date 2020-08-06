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

import io.grpc.xds.Bootstrapper;

import java.io.IOException;

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

  // TODO: move elsewhere
  public static Bootstrapper.BootstrapInfo getTestBootstrapInfo() throws IOException {
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
