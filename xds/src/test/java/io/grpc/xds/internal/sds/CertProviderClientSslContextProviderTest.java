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

import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.MoreExecutors;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.grpc.internal.testing.TestUtils;
import io.grpc.xds.Bootstrapper;
import io.grpc.xds.EnvoyServerProtoData;
import io.grpc.xds.internal.certprovider.CertificateProvider;
import io.grpc.xds.internal.certprovider.CertificateProviderRegistry;
import io.grpc.xds.internal.certprovider.CertificateProviderStore;
import io.grpc.xds.internal.certprovider.TestCertificateProvider;
import io.grpc.xds.internal.sds.trust.CertificateUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.util.CharsetUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.crypto.*;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.*;
import static io.grpc.xds.internal.sds.SecretVolumeSslContextProviderTest.doChecksOnSslContext;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;

/** Unit tests for {@link CertProviderClientSslContextProvider}. */
@RunWith(JUnit4.class)
public class CertProviderClientSslContextProviderTest {
  private static final Logger logger = Logger.getLogger(CertProviderClientSslContextProviderTest.class.getName());

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
                                                                     Bootstrapper.BootstrapInfo bootstrapInfo,
                                                                     Iterable<String> alpnProtocols) {
    EnvoyServerProtoData.UpstreamTlsContext upstreamTlsContext =
            CommonTlsContextTestsUtil.buildUpstreamTlsContextForCertProviderInstance(
                    certInstanceName, "cert-default", rootInstanceName,
                    "root-default", alpnProtocols);
    return certProviderClientSslContextProviderFactory.getProvider(upstreamTlsContext, bootstrapInfo.getNode().toEnvoyProtoNode(),
        bootstrapInfo.getCertProviders());
  }

  // copied from SdsSslContextProviderTest.testProviderForClient
  @Test
  public void testProviderForClient_mtls() throws Exception {
    final CertificateProvider.DistributorWatcher[] watcherCaptor = new CertificateProvider.DistributorWatcher[1];
    ClientSslContextProviderFactoryTest.createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    CertProviderClientSslContextProvider provider =
            getSslContextProvider("gcp_id", "gcp_id",
            TestCertificateProvider.getTestBootstrapInfo(), null);

    assertThat(provider.lastKey).isNull();
    assertThat(provider.lastCertChain).isNull();
    assertThat(provider.lastTrustedRoots).isNull();
    assertThat(provider.sslContext).isNull();

    // now generate cert update
    watcherCaptor[0].updateCertificate(getPrivateKey(CLIENT_KEY_FILE), ImmutableList.of(getCertFromResourceName(CLIENT_PEM_FILE)));
    assertThat(provider.lastKey).isNotNull();
    assertThat(provider.lastCertChain).isNotNull();
    assertThat(provider.sslContext).isNull();

    // now generate root cert update
    watcherCaptor[0].updateTrustedRoots(ImmutableList.of(getCertFromResourceName(CA_PEM_FILE)));
    assertThat(provider.sslContext).isNotNull();
    assertThat(provider.lastKey).isNull();
    assertThat(provider.lastCertChain).isNull();
    assertThat(provider.lastTrustedRoots).isNull();

    SecretVolumeSslContextProviderTest.TestCallback testCallback =
        SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
    SecretVolumeSslContextProviderTest.TestCallback testCallback1 =
            SecretVolumeSslContextProviderTest.getValueThruCallback(provider);
    assertThat(testCallback1.updatedSslContext).isSameInstanceAs(testCallback.updatedSslContext);

    // just do root cert update: sslContext should still be the same
    watcherCaptor[0].updateTrustedRoots(ImmutableList.of(getCertFromResourceName(SERVER_0_PEM_FILE)));
    assertThat(provider.lastKey).isNull();
    assertThat(provider.lastCertChain).isNull();
    assertThat(provider.lastTrustedRoots).isNotNull();
    testCallback1 = SecretVolumeSslContextProviderTest.getValueThruCallback(provider);
    assertThat(testCallback1.updatedSslContext).isSameInstanceAs(testCallback.updatedSslContext);

    // now update id cert: sslContext should be updated i.e.different from the previous one
    watcherCaptor[0].updateCertificate(getPrivateKey(SERVER_1_KEY_FILE), ImmutableList.of(getCertFromResourceName(SERVER_1_PEM_FILE)));
    assertThat(provider.lastKey).isNull();
    assertThat(provider.lastCertChain).isNull();
    assertThat(provider.lastTrustedRoots).isNull();
    assertThat(provider.sslContext).isNotNull();
    testCallback1 = SecretVolumeSslContextProviderTest.getValueThruCallback(provider);
    assertThat(testCallback1.updatedSslContext).isNotSameInstanceAs(testCallback.updatedSslContext);
  }

  @Test
  public void testProviderForClient_queueExecutor() throws Exception {
    final CertificateProvider.DistributorWatcher[] watcherCaptor = new CertificateProvider.DistributorWatcher[1];
    ClientSslContextProviderFactoryTest.createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    CertProviderClientSslContextProvider provider =
            getSslContextProvider("gcp_id", "gcp_id",
                    TestCertificateProvider.getTestBootstrapInfo(), null);
    QueuedExecutor queuedExecutor = new QueuedExecutor();

    SecretVolumeSslContextProviderTest.TestCallback testCallback =
            SecretVolumeSslContextProviderTest.getValueThruCallback(provider, queuedExecutor);
    assertThat(queuedExecutor.runQueue).isEmpty();

    // now generate cert update
    watcherCaptor[0].updateCertificate(getPrivateKey(CLIENT_KEY_FILE), ImmutableList.of(getCertFromResourceName(CLIENT_PEM_FILE)));
    assertThat(queuedExecutor.runQueue).isEmpty(); // still empty

    // now generate root cert update
    watcherCaptor[0].updateTrustedRoots(ImmutableList.of(getCertFromResourceName(CA_PEM_FILE)));
    assertThat(queuedExecutor.runQueue).hasSize(1);
    queuedExecutor.drain();

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }

  @Test
  public void testProviderForClient_tls() throws Exception {
    final CertificateProvider.DistributorWatcher[] watcherCaptor = new CertificateProvider.DistributorWatcher[1];
    ClientSslContextProviderFactoryTest.createAndRegisterProviderProvider(certificateProviderRegistry, watcherCaptor, "testca", 0);
    CertProviderClientSslContextProvider provider =
            getSslContextProvider(/* certInstanceName= */ null, "gcp_id",
                    TestCertificateProvider.getTestBootstrapInfo(), null);

    assertThat(provider.lastKey).isNull();
    assertThat(provider.lastCertChain).isNull();
    assertThat(provider.lastTrustedRoots).isNull();
    assertThat(provider.sslContext).isNull();

    // now generate root cert update
    watcherCaptor[0].updateTrustedRoots(ImmutableList.of(getCertFromResourceName(CA_PEM_FILE)));
    assertThat(provider.sslContext).isNotNull();
    assertThat(provider.lastKey).isNull();
    assertThat(provider.lastCertChain).isNull();
    assertThat(provider.lastTrustedRoots).isNull();

    SecretVolumeSslContextProviderTest.TestCallback testCallback =
            SecretVolumeSslContextProviderTest.getValueThruCallback(provider);

    doChecksOnSslContext(false, testCallback.updatedSslContext, /* expectedApnProtos= */ null);
  }


  static class QueuedExecutor implements Executor {

    /** A list of Runnables to be run in order. */
    private final Queue<Runnable> runQueue = new ConcurrentLinkedQueue<>();

    @Override
    public synchronized void execute(Runnable r) {
      runQueue.add(checkNotNull(r, "'r' must not be null."));
    }

    public synchronized void drain() {
      Runnable r;
      while ((r = runQueue.poll()) != null) {
        try {
          r.run();
        } catch (RuntimeException e) {
          // Log it and keep going.
          logger.log(Level.SEVERE, "Exception while executing runnable " + r, e);
        }
      }
    }
  }

  // copy remaining methods from SdsSslContextProviderTest

  //TODO use the common utils one
  private static PrivateKey getPrivateKey(String resourceName)
          throws Exception {
    InputStream inputStream = TestUtils.class.getResourceAsStream("/certs/" + resourceName);
    /*byte[] keyBytes = ByteStreams.toByteArray(inputStream);
    PKCS8EncodedKeySpec spec =
            new PKCS8EncodedKeySpec(keyBytes);
    //KeyFactory kf = KeyFactory.getInstance("RSA");
    //return kf.generatePrivate(spec);
*/
    ByteBuf encodedKeyBuf = readPrivateKey(inputStream);

    byte[] encodedKey = new byte[encodedKeyBuf.readableBytes()];
    encodedKeyBuf.readBytes(encodedKey).release();

    PKCS8EncodedKeySpec spec = generateKeySpec(
            null, encodedKey);

    try {
      return KeyFactory.getInstance("RSA").generatePrivate(spec);
    } catch (InvalidKeySpecException ignore) {
      try {
        return KeyFactory.getInstance("DSA").generatePrivate(spec);
      } catch (InvalidKeySpecException ignore2) {
        try {
          return KeyFactory.getInstance("EC").generatePrivate(spec);
        } catch (InvalidKeySpecException e) {
          throw new InvalidKeySpecException("Neither RSA, DSA nor EC worked", e);
        }
      }
    }
  }

  protected static PKCS8EncodedKeySpec generateKeySpec(char[] password, byte[] key)
          throws IOException, NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeySpecException,
          InvalidKeyException, InvalidAlgorithmParameterException {

    if (password == null) {
      return new PKCS8EncodedKeySpec(key);
    }

    EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = new EncryptedPrivateKeyInfo(key);
    SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(encryptedPrivateKeyInfo.getAlgName());
    PBEKeySpec pbeKeySpec = new PBEKeySpec(password);
    SecretKey pbeKey = keyFactory.generateSecret(pbeKeySpec);

    Cipher cipher = Cipher.getInstance(encryptedPrivateKeyInfo.getAlgName());
    cipher.init(Cipher.DECRYPT_MODE, pbeKey, encryptedPrivateKeyInfo.getAlgParameters());

    return encryptedPrivateKeyInfo.getKeySpec(cipher);
  }


  static ByteBuf readPrivateKey(InputStream in) throws KeyException {
    String content;
    try {
      content = readContent(in);
    } catch (IOException e) {
      throw new KeyException("failed to read key input stream", e);
    }

    Matcher m = KEY_PATTERN.matcher(content);
    if (!m.find()) {
      throw new KeyException("could not find a PKCS #8 private key in input stream" +
              " (see https://netty.io/wiki/sslcontextbuilder-and-private-key.html for more information)");
    }

    ByteBuf base64 = Unpooled.copiedBuffer(m.group(1), CharsetUtil.US_ASCII);
    ByteBuf der = Base64.decode(base64);
    base64.release();
    return der;
  }

  private static String readContent(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      byte[] buf = new byte[8192];
      for (;;) {
        int ret = in.read(buf);
        if (ret < 0) {
          break;
        }
        out.write(buf, 0, ret);
      }
      return out.toString(CharsetUtil.US_ASCII.name());
    } finally {
      safeClose(out);
    }
  }

  private static void safeClose(InputStream in) {
    try {
      in.close();
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to close a stream.", e);
    }
  }

  private static void safeClose(OutputStream out) {
    try {
      out.close();
    } catch (IOException e) {
      logger.log(Level.WARNING, "Failed to close a stream.", e);
    }
  }

  private static final Pattern KEY_PATTERN = Pattern.compile(
          "-+BEGIN\\s+.*PRIVATE\\s+KEY[^-]*-+(?:\\s|\\r|\\n)+" + // Header
                  "([a-z0-9+/=\\r\\n]+)" +                       // Base64 text
                  "-+END\\s+.*PRIVATE\\s+KEY[^-]*-+",            // Footer
          Pattern.CASE_INSENSITIVE);

  private static X509Certificate getCertFromResourceName(String resourceName)
          throws IOException, CertificateException {
    return CertificateUtils.toX509Certificate(
            new ByteArrayInputStream(getResourceContents(resourceName).getBytes(UTF_8)));
  }

  private static String getResourceContents(String resourceName) throws IOException {
    InputStream inputStream = TestUtils.class.getResourceAsStream("/certs/" + resourceName);
    String text = null;
    try (Reader reader = new InputStreamReader(inputStream, UTF_8)) {
      text = CharStreams.toString(reader);
    }
    return text;
  }

}
