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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.Node;

import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;



/** Unit tests for {@link SdsClientTemp}. */
@RunWith(JUnit4.class)
public class SdsClientTempTest {

  SdsClientTemp sdsClient;

  Node node = Node.newBuilder()
          .setId("sds-client-temp-test1")
          .build();

  @Test
  public void configSourceUdsTarget() {
    ConfigSource configSource = buildConfigSource("unix:/tmp/uds_path");
    SdsClientTemp sdsClientTemp = new SdsClientTemp(configSource, node);
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
    sdsClient.start(channel);
  } */

  @Test
  public void testSecretWatcher() throws IOException {
    DummySdsServer.SecretGetter secretGetter = mock(DummySdsServer.SecretGetter.class);
    DummySdsServer server = new DummySdsServer("inproc", secretGetter);
    server.runServer();
    ConfigSource configSource = buildConfigSource("inproc");
    SdsClientTemp client = new SdsClientTemp(configSource, node);
    client.start();
    SdsClientTemp.SecretWatcher mockWatcher = mock(SdsClientTemp.SecretWatcher.class);

    when(secretGetter.getFor("name1")).thenReturn(getOneTlsCertSecret("name1", 0));

    SdsSecretConfig sdsSecretConfig =
        SdsSecretConfig.newBuilder().setSdsConfig(configSource).setName("name1").build();
    SdsClientTemp.SecretWatcherHandle handle = client.watchSecret(sdsSecretConfig, mockWatcher);
    discoveryRequestVerification(server.discoveryService.lastGoodRequest,
            "[name1]", "", "");
    discoveryRequestVerification(server.discoveryService.lastRequestOnlyForAck,
            "[name1]", server.lastResponse.getVersionInfo(),
            server.lastResponse.getNonce());
    secretWatcherVerification(mockWatcher, "name1", 0);

    reset(mockWatcher);
    when(secretGetter.getFor("name1")).thenReturn(getOneTlsCertSecret("name1", 1));
    server.generateAsyncResponse("name1");
    secretWatcherVerification(mockWatcher, "name1", 1);

    //cancelSecretWatch test
    reset(mockWatcher);
    client.cancelSecretWatch(handle);
    server.generateAsyncResponse("name1");
    verify(mockWatcher, never()).onSecretChanged(any(Secret.class));
  }

  private void discoveryRequestVerification(DiscoveryRequest request,
                                            String resourceNames,
                                            String versionInfo, String responseNonce) {
    assertThat(request).isNotNull();
    assertThat(request.getNode()).isEqualTo(node);
    assertThat(Arrays.toString(request.getResourceNamesList().toArray())).isEqualTo("[name1]");
    assertThat(request.getTypeUrl()).isEqualTo("type.googleapis.com/envoy.api.v2.auth.Secret");
    if (versionInfo != null) {
      assertThat(request.getVersionInfo()).isEqualTo(versionInfo);
    }
    if (responseNonce != null) {
      assertThat(request.getResponseNonce()).isEqualTo(responseNonce);
    }
  }

  private void secretWatcherVerification(SdsClientTemp.SecretWatcher mockWatcher,
                                         String secretName, int index) {
    ArgumentCaptor<Secret> secretCaptor = ArgumentCaptor.forClass(Secret.class);
    verify(mockWatcher, times(1)).onSecretChanged(secretCaptor.capture());
    Secret secret = secretCaptor.getValue();
    assertThat(secret.getName()).isEqualTo(secretName);
    assertThat(secret.hasTlsCertificate()).isTrue();
    TlsCertificate tlsCertificate = secret.getTlsCertificate();
    assertThat(tlsCertificate.getPrivateKey().getInlineBytes().toStringUtf8())
        .isEqualTo(PRIVATE_KEYS[index]);
    assertThat(tlsCertificate.getCertificateChain().getInlineBytes().toStringUtf8())
        .isEqualTo(CERTIFICATE_CHAINS[index]);
  }

  private Secret getOneTlsCertSecret(String name, int index) {
    TlsCertificate tlsCertificate =
        TlsCertificate.newBuilder()
            .setPrivateKey(
                DataSource.newBuilder()
                    .setInlineBytes(ByteString.copyFromUtf8(PRIVATE_KEYS[index]))
                    .build())
            .setCertificateChain(
                DataSource.newBuilder()
                    .setInlineBytes(ByteString.copyFromUtf8(CERTIFICATE_CHAINS[index]))
                    .build())
            .build();
    return Secret.newBuilder().setName(name).setTlsCertificate(tlsCertificate).build();
  }

  private static final String[] PRIVATE_KEYS = {
      "-----BEGIN RSA PRIVATE KEY-----"
      + "MIIEowIBAAKCAQEAtqwOeCRGd9H91ieHQmDX0KR6RHEVHxN6X3VsL8RXu8GtaULP"
                  + "3IFmitbY2uLoVpdB4JxuDmuIDYbktqheLYD4g55klq13OInlEMtLk/u2H0Fvz70H"
                  + "RjDFAfOqY8OTIjs2+iM1H5OFVNrKxSHao/wiqbU3ZOZHu7ts6jcLrh8O+P17KRRE"
                  + "aP7mapH1cETDy/wA3qgE42ARfbO/0DPX2VQJuTewk1NJWQVdCkE7VWYR6F5PMTyB"
                  + "ChT3lsqHalrLEQCT5Ytcio+KPO6Y1qstrbFv++FAMQrthKlVcuPc6meOpszPjNqS"
                  + "QNCXpA99R6slAEzTSxmEpQrMUMPToHT6NRxs1wIDAQABAoIBADyw4YXNF5SLsjhK"
                  + "ncfSASIS44SFxayzff7lNnKQW03IRWMpjYIHhBgw1Y+zv9m1G3ASyQYFeAh2ftqp"
                  + "CdE4fljMcUMWkvu35OE1igC6qoGr7ggpF5eccHf7iurmeaXv4o4s0GOTUcMlhiUE"
                  + "4G2HQcT8rlDZqY+X79HJRBovu3vBvCktYMmzCXugrudwFkpbi5Dd3sFuPiKrXndY"
                  + "oDPtjU2cb7Cg9DO8PZwab7tGWaFjstwXhIOE636uLog9xM9EC3D2qp9QFOVkmCH4"
                  + "t4MzUCHcbIXRcunlil2+CYIFPDylJL6bFlpfVhtNubdgC35bsSql+h1QgZMezpAY"
                  + "ZK9p7nECgYEA4hMKOAac1pnlfsXjf3jzZuZLBPIV/WpNZNsAIL/4aErSL0C3woRx"
                  + "hj8q4onA0BD078r8n9zh3x/el17B/f43FoDydSkONlcUKaayHYlIhB1ULHPIEDVG"
                  + "zlXIpkSi4Qui+51sZLnxXcmPbCT4LUN5nkWkZRHRboaufBAx+SdDRdUCgYEAzto/"
                  + "cyEJ9p+e9chHSWv17pfeBu87XROwFS66hyWcnA5qDThbpvFakOGdCeKMKCS2ALW5"
                  + "LsLx+PvN/V94AoJaMDsR3b2CH+/+cLWMLKqAiZzkha/Jr9FRyFPFs2nkZVkeekc8"
                  + "FMXMwvs16hbBs3KHizJ5UswrGzOKWlPdpfxMofsCgYAoost/bpDacicyNlfCHfeC"
                  + "U3rAlNMnDeiDbGoFePwpoulM3REqwau2Obx3o9MokyOzxoTKJ2XiOVRFWR79jKhS"
                  + "PzNVo9+OHPDe27vAW2DRfoQWyWj4oNrtU7YRTN0KHpFZMN6+7D1aYlSJV8vUNwCx"
                  + "VktKb315pHPQkQiqhEgvUQKBgFYSTnCTgNfUV4qiCbetaqobG1H7XdI/DPfjd84g"
                  + "gmgVP1+84bY3m53Jo1SnpfZWQD1PYHzqtVELRg12GjPBFdIX4jlIT8sGS/OON4Om"
                  + "dtHMLPLL0LqN+N/Iq+0Z1OWvDZWH6qIiJC/F5AtB6NvIfkoXeJBRUGaDLcCkQQh+"
                  + "UUzdAoGBAKnmA0y3Up9QAowB1F7vvP9B4GzJ3qI/YNAkBE5keQePz/utetTStV+j"
                  + "xcvcLWv3ZSpjpXSNwOBfdjdQirYFZQZtcAf9JxBkr0HaQ7w7MLxLp06O0YglH1Su"
                  + "XyPkmABFTunZEBnpCd9NFXgzM3jQGvSZJOj1n0ZALZ1BM9k54e62"
                  + "-----END RSA PRIVATE KEY-----",
      "-----BEGIN RSA PRIVATE KEY-----"
                  + "MIICXQIBAAKBgQDARNUJMFkWF0E6mbdz/nkydVC4TU2SgR95vhJhWpG6xKkCNoXk"
                  + "JxNzXOmFUUIXQyq7FnIWACYuMrE2KXnomeCGP9A6M21lumNseYSLX3/b+ao4E6gi"
                  + "mm1/Gp8C3FaoAs8Ep7VE+o2DMIfTIPJhFf6RBFPundGhEm8/gv+QObVhKQIDAQAB"
                  + "AoGBAJM64kukC0QAUMHX/gRD5HkAHuzSvUknuXuXUincmeWEPMtmBwdb6OgZSPT+"
                  + "8XYwx+L14Cz6tkIALXWFM0YrtyKfVdELRRs8dw5nenzK3wOeo/N/7XL4kwim4kV3"
                  + "q817RO6NUN76vHOsvQMFsPlEfCZpOTIGJEJBI7eFLP0djOMlAkEA/yWEPfQoER/i"
                  + "X6uNyXrU51A6gxyZg7rPNP0cxxRhDedtsJPNY6Tlu90v9SiTgQLUTp7BINH24t9a"
                  + "MST1tmax+wJBAMDpeRy52q+sqLXI1C2oHPuXrXzeyp9pynV/9tsYL9+qoyP2XcEZ"
                  + "DaI0tfXDJXOdYIaDnSfB50eqQUnaTmQjtCsCQGUFGaLd9K8zDJIMforzUzByl3gp"
                  + "7q41XK0COk6oRvUWWFu9aWi2dS84mDBc7Gn8EMtAF/9CopmZDUC//XlGl9kCQQCr"
                  + "6yWw8PywFHohzwEwUyLJIKpOnyoKGTiBsHGpXYvEk4hiEzwISzB4PutuQuRMfZM5"
                  + "LW/Pr6FSn6shivjTi3ITAkACMTBczBQ+chMBcTXDqyqwccQOIhupxani9wfZhsrm"
                  + "ZXbTTxnUZioQ2l/7IWa+K2O2NrWWT7b3KpCAob0bJsQz"
                  + "-----END RSA PRIVATE KEY-----"
  };

  private static final String[] CERTIFICATE_CHAINS = {
      "-----BEGIN CERTIFICATE-----"
        + "MIIDnzCCAoegAwIBAgIJAI3dmBBDwTQCMA0GCSqGSIb3DQEBCwUAMIGLMQswCQYD"
        + "VQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTESMBAGA1UEBwwJU3Vubnl2YWxl"
        + "MQ4wDAYDVQQKDAVJc3RpbzENMAsGA1UECwwEVGVzdDEQMA4GA1UEAwwHUm9vdCBD"
        + "QTEiMCAGCSqGSIb3DQEJARYTdGVzdHJvb3RjYUBpc3Rpby5pbzAgFw0xODA1MDgx"
        + "OTQ5MjRaGA8yMTE4MDQxNDE5NDkyNFowWTELMAkGA1UEBhMCVVMxEzARBgNVBAgM"
        + "CkNhbGlmb3JuaWExEjAQBgNVBAcMCVN1bm55dmFsZTEOMAwGA1UECgwFSXN0aW8x"
        + "ETAPBgNVBAMMCElzdGlvIENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKC"
        + "AQEAtqwOeCRGd9H91ieHQmDX0KR6RHEVHxN6X3VsL8RXu8GtaULP3IFmitbY2uLo"
        + "VpdB4JxuDmuIDYbktqheLYD4g55klq13OInlEMtLk/u2H0Fvz70HRjDFAfOqY8OT"
        + "Ijs2+iM1H5OFVNrKxSHao/wiqbU3ZOZHu7ts6jcLrh8O+P17KRREaP7mapH1cETD"
        + "y/wA3qgE42ARfbO/0DPX2VQJuTewk1NJWQVdCkE7VWYR6F5PMTyBChT3lsqHalrL"
        + "EQCT5Ytcio+KPO6Y1qstrbFv++FAMQrthKlVcuPc6meOpszPjNqSQNCXpA99R6sl"
        + "AEzTSxmEpQrMUMPToHT6NRxs1wIDAQABozUwMzALBgNVHQ8EBAMCAgQwDAYDVR0T"
        + "BAUwAwEB/zAWBgNVHREEDzANggtjYS5pc3Rpby5pbzANBgkqhkiG9w0BAQsFAAOC"
        + "AQEAGpB9V2K7fEYYxmatjQLuNw0s+vKa5JkJrJO3H6Y1LAdKTJ3k7Cpr15zouM6d"
        + "5KogHfFHXPI6MU2ZKiiE38UPQ5Ha4D2XeuAwN64cDyN2emDnQ0UFNm+r4DY47jd3"
        + "jHq8I3reVSXeqoHcL0ViuGJRY3lrk8nmEo15vP1stmo5bBdnSlASDDjEjh1FHeXL"
        + "/Ha465WYESLcL4ps/xrcXN4JtV1nDGJVGy4WmusL+5D9nHC53/srZczZX3By48+Y"
        + "hhZwPFxt/EVB0YISgMOnMHzmWmnNWRiDuI6eZxUx0L9B9sD4s7zrQYYQ1bV/CPYX"
        + "iwlodzJwNdfIBfD/AC/GdnaWow=="
        + "-----END CERTIFICATE-----",
    "-----BEGIN CERTIFICATE-----"
        + "MIIDDDCCAnWgAwIBAgIJAPOCjrJP13nQMA0GCSqGSIb3DQEBCwUAMHYxCzAJBgNV"
        + "BAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlhMRYwFAYDVQQHEw1TYW4gRnJhbmNp"
        + "c2NvMQ0wCwYDVQQKEwRMeWZ0MRkwFwYDVQQLExBMeWZ0IEVuZ2luZWVyaW5nMRAw"
        + "DgYDVQQDEwdUZXN0IENBMB4XDTE3MDcwOTAxMzkzMloXDTE5MDcwOTAxMzkzMlow"
        + "ejELMAkGA1UEBhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExFjAUBgNVBAcTDVNh"
        + "biBGcmFuY2lzY28xDTALBgNVBAoTBEx5ZnQxGTAXBgNVBAsTEEx5ZnQgRW5naW5l"
        + "ZXJpbmcxFDASBgNVBAMTC1Rlc3QgU2VydmVyMIGfMA0GCSqGSIb3DQEBAQUAA4GN"
        + "ADCBiQKBgQDARNUJMFkWF0E6mbdz/nkydVC4TU2SgR95vhJhWpG6xKkCNoXkJxNz"
        + "XOmFUUIXQyq7FnIWACYuMrE2KXnomeCGP9A6M21lumNseYSLX3/b+ao4E6gimm1/"
        + "Gp8C3FaoAs8Ep7VE+o2DMIfTIPJhFf6RBFPundGhEm8/gv+QObVhKQIDAQABo4Gd"
        + "MIGaMAwGA1UdEwEB/wQCMAAwCwYDVR0PBAQDAgXgMB0GA1UdJQQWMBQGCCsGAQUF"
        + "BwMCBggrBgEFBQcDATAeBgNVHREEFzAVghNzZXJ2ZXIxLmV4YW1wbGUuY29tMB0G"
        + "A1UdDgQWBBRCcUr8mIigWlR61OX/gmDY5vBV6jAfBgNVHSMEGDAWgBQ7eKRRTxaE"
        + "kxxIKHoMrSuWQcp9eTANBgkqhkiG9w0BAQsFAAOBgQAtn05e8U41heun5L7MKflv"
        + "tJM7w0whavdS8hLe63CxnS98Ap973mSiShKG+OxSJ0ClMWIZPy+KyC+T8yGIaynj"
        + "wEEuoSGRWmhzcMMnZWxqQyD95Fsx6mtdnq/DJxiYzmH76fALe/538j8pTcoygSGD"
        + "NWw1EW8TEwlFyuvCrlWQcg=="
        + "-----END CERTIFICATE-----"
  };
}
