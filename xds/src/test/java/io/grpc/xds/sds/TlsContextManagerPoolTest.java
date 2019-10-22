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

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.netty.handler.ssl.SslContext;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

/** Unit tests for {@link TlsContextManager}. */
@RunWith(JUnit4.class)
public class TlsContextManagerPoolTest {

  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CA_PEM_FILE = "ca.pem";

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void createServerSslContextProvider() throws InterruptedException {
    DownstreamTlsContext downstreamTlsContext =
        SslContextSecretVolumeSecretProviderTest.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);

    SecretProvider<SslContext> serverSecretProvider =
            TlsContextManager.getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext);
    assertThat(serverSecretProvider).isNotNull();

    SecretProvider<SslContext> serverSecretProvider1 =
            TlsContextManager.getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext);
    assertThat(serverSecretProvider).isSameInstanceAs(serverSecretProvider1);

    DownstreamTlsContext downstreamTlsContext1 =
            SslContextSecretVolumeSecretProviderTest.buildDownstreamTlsContextFromFilenames(
                    SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);
    SecretProvider<SslContext> serverSecretProvider2 =
            TlsContextManager.getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext1);
    assertThat(serverSecretProvider).isSameInstanceAs(serverSecretProvider2);
    // at this point refCount is 3
    assertThat(serverSecretProvider.returnObject()).isNull();  // refCount is 2
    assertThat(serverSecretProvider.returnObject()).isNull();  // refCount is 1
    assertThat(serverSecretProvider.returnObject()).isNull();  // refCount is 0
    // wait for > SdsSharedResourceHolder.DESTROY_DELAY_SECONDS
    Thread.sleep(1500L);
    serverSecretProvider2 =
            TlsContextManager.getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext1);
    assertThat(serverSecretProvider).isNotSameInstanceAs(serverSecretProvider2); // refCount is 1 now for serverSecretProvider2
    assertThat(serverSecretProvider2.returnObject()).isNull();  // refCount is 0
    try {
      serverSecretProvider2.returnObject();
      fail("Exception expected");
    } catch (IllegalStateException expected) {
      assertThat(expected)
              .hasMessageThat()
              .contains("Refcount has already reached zero");
    }
  }
}
