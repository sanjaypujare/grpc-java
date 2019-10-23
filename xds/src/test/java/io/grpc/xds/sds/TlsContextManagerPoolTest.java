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
import java.lang.reflect.Field;
import java.util.logging.Logger;
import org.junit.Before;
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
  private static final Logger logger = Logger.getLogger(TlsContextManagerPoolTest.class.getName());

  private static final String SERVER_1_PEM_FILE = "server1.pem";
  private static final String SERVER_1_KEY_FILE = "server1.key";
  private static final String CA_PEM_FILE = "ca.pem";

  @Before
  public void clearInstance() throws NoSuchFieldException, IllegalAccessException {
    Field field = TlsContextManager.class.getDeclaredField("instance");
    field.setAccessible(true);
    field.set(null, null);
  }

  @Test
  public void createServerSslContextProvider() throws InterruptedException {
    //TlsContextManager.clearInstance();
    DownstreamTlsContext downstreamTlsContext =
        SslContextSecretVolumeSecretProviderTest.buildDownstreamTlsContextFromFilenames(
            SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);

    SecretProvider<SslContext> serverSecretProvider =
            TlsContextManager.getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext);
    assertThat(serverSecretProvider).isNotNull();
    logger.info("get#1 to serverSecretProvider");


    SecretProvider<SslContext> serverSecretProvider1 =
            TlsContextManager.getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext);
    logger.info("get#2 to serverSecretProvider1");
    assertThat(serverSecretProvider).isSameInstanceAs(serverSecretProvider1);

    DownstreamTlsContext downstreamTlsContext1 =
            SslContextSecretVolumeSecretProviderTest.buildDownstreamTlsContextFromFilenames(
                    SERVER_1_KEY_FILE, SERVER_1_PEM_FILE, /* trustCa= */ null);
    SecretProvider<SslContext> serverSecretProvider2 =
            TlsContextManager.getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext1);
    logger.info("get#3 to serverSecretProvider2, at this point refCount should be 3");
    assertThat(serverSecretProvider).isSameInstanceAs(serverSecretProvider2);
    // at this point refCount is 3
    assertThat(serverSecretProvider.returnObject()).isNull();  // refCount is 2 after this
    assertThat(serverSecretProvider.returnObject()).isNull();  // refCount is 1 after this
    assertThat(serverSecretProvider.returnObject()).isNull();  // refCount is 0 after this
    // wait for > SdsSharedResourceHolder.DESTROY_DELAY_SECONDS
    logger.info("before delay: at this point refcount is expected to be 0");
    Thread.sleep(4500L);
    logger.info("after delay");
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
