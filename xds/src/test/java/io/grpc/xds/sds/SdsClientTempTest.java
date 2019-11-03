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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

/** Unit tests for {@link SdsClientTemp}. */
@RunWith(JUnit4.class)
public class SdsClientTempTest {

  SdsClientTemp sdsClient;

  @Test
  public void configSourceUdsTarget() {
    ConfigSource configSource = buildConfigSource("unix:/tmp/uds_path");
    SdsClientTemp sdsClientTemp = new SdsClientTemp(configSource);
    assertThat(sdsClientTemp.udsTarget).isEqualTo("unix:/tmp/uds_path");
  }

  private static ConfigSource buildConfigSource(String targetUri) {
    return ConfigSource.newBuilder()
        .setApiConfigSource(ApiConfigSource.newBuilder()
                .setApiType(ApiConfigSource.ApiType.GRPC)
                .addGrpcServices(GrpcService.newBuilder()
                        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                                .setTargetUri(targetUri)
                                .build())
                        .build())
                .build())
        .build();
  }

  /*
  private void buildInProcesschannel(String name) {
    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    sdsClient = new SdsClientTemp();
    sdsClient.startUsingChannel(channel);
  } */


  @Test
  public void testSecretWatcher() throws IOException {
    DummySdsServer server = new DummySdsServer("inproc");
    server.runServer();
    ConfigSource configSource = buildConfigSource("inproc");
    SdsClientTemp client = new SdsClientTemp(configSource);
    client.startUsingChannel();
    SdsClientTemp.SecretWatcher mockWatcher = mock(SdsClientTemp.SecretWatcher.class);

    SdsSecretConfig sdsSecretConfig = SdsSecretConfig.newBuilder()
            .setSdsConfig(configSource)
            .setName("name1")
            .build();
    client.watchSecret(sdsSecretConfig, mockWatcher);
    ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
    verify(mockWatcher, times(1)).onSecretChanged(secretCaptor.capture());
    Secret secret = secretCaptor.getValue();
    assertThat(secret.getName()).isEqualTo("name1");
  }

}
