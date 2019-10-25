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


import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.ProtocolStringList;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.api.v2.core.DataSource;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DummySdsServer {
  private static final Logger logger = Logger.getLogger(DummySdsServer.class.getName());

  // key for UDS path in gRPC context metadata map.
  private static final String UDS_PATH_KEY = ":authority";
  // SecretTypeURL defines the type URL for Envoy secret proto.
  private static final String SECRET_TYPE_URL = "type.googleapis.com/envoy.api.v2.auth.Secret";
  // SecretName defines the type of the secrets to fetch from the SDS server.
  private static final String SECRET_NAME = "SPKI";

  String currentVersion;
  String lastRespondedNonce;

  private static final class MySecret {
    MySecret(String privateKey, String certificateChain) {
      this.privateKey = privateKey;
      this.certificateChain = certificateChain;
    }

    String privateKey;
    String certificateChain;
  }

  static MySecret secrets[] = new MySecret[] {
      new MySecret("-----BEGIN RSA PRIVATE KEY-----"
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
              + "-----END CERTIFICATE-----"),
      new MySecret("-----BEGIN RSA PRIVATE KEY-----"
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
          + "-----END RSA PRIVATE KEY-----",
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
              + "-----END CERTIFICATE-----")};

  private class SecretDiscoveryServiceImpl extends SecretDiscoveryServiceGrpc.SecretDiscoveryServiceImplBase {

    final long startTime = System.nanoTime();

    SecretDiscoveryServiceImpl() {

    }

    /**
     *
     */
    class SdsInboundStreamObserver implements StreamObserver<DiscoveryRequest> {
      // this is outbound...
      private final StreamObserver<DiscoveryResponse> responseObserver;

      public SdsInboundStreamObserver(StreamObserver<DiscoveryResponse> responseObserver) {
        this.responseObserver = responseObserver;
      }

      @Override
      public void onNext(DiscoveryRequest discoveryRequest) {
        ProtocolStringList resourceNames = discoveryRequest.getResourceNamesList();
        String version = discoveryRequest.getVersionInfo();
        String nonce = discoveryRequest.getResponseNonce();

        if (!currentVersion.equals(version)) {
          // responseObserver.onError might close the call...
          responseObserver.onError(new RuntimeException("incorrect version received:" + version));
          return;
        }
        if (!lastRespondedNonce.equals(nonce)) {
          // responseObserver.onError might close the call...
          responseObserver.onError(new RuntimeException("incorrect nonce received:" + nonce));
          return;
        }
        // TODO: is there a way to build a single DiscoveryResponse for all resource names?
        for (String resourceName : resourceNames) {
          responseObserver.onNext(buildResponse(resourceName));
        }
      }

      @Override
      public void onError(Throwable t) {
        logger.log(Level.SEVERE, "onError", t);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    }

    // bidi streaming
    @Override
    public StreamObserver<DiscoveryRequest> streamSecrets(
        StreamObserver<DiscoveryResponse> responseObserver) {
      return new SdsInboundStreamObserver(responseObserver);
    }

    // unary call
    @Override
    public void fetchSecrets(DiscoveryRequest request,
        StreamObserver<DiscoveryResponse> responseObserver) {

      DiscoveryResponse response = buildResponse(request.getResourceNames(0));
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }

    private DiscoveryResponse buildResponse(String resourceName) {
      final String version = "" + ((System.nanoTime() - startTime) / 1000000L);


      Secret secret = Secret.newBuilder()
              .setName(resourceName)
              .setTlsCertificate(getOneTlsCert())
              .build();

      ByteString data = secret.toByteString();
      Any anyValue = Any.newBuilder()
              .setTypeUrl(SECRET_TYPE_URL)
              .setValue(data)
              .build();
      DiscoveryResponse response = DiscoveryResponse.newBuilder()
              .setVersionInfo(version)
              .setNonce(getAndSaveNonce())
              .setTypeUrl(SECRET_TYPE_URL)
              .addResources(anyValue)
              .build();
      currentVersion = version;
      return response;
    }

    private TlsCertificate getOneTlsCert() {
      int index = (int)Math.round(Math.random());
      MySecret mySecret = secrets[index];
      TlsCertificate tlsCertificate = TlsCertificate.newBuilder()
              .setPrivateKey(DataSource.newBuilder().setInlineBytes(ByteString.copyFromUtf8(mySecret.privateKey)).build())
              .setCertificateChain(DataSource.newBuilder().setInlineBytes(ByteString.copyFromUtf8(mySecret.certificateChain)).build())
              .build();
      return tlsCertificate;
    }
  }

  private Server createSdsServer() throws IOException {
    NettyServerBuilder serverBuilder =
            NettyServerBuilder.forPort(8080)
                    .addService(new SecretDiscoveryServiceImpl());
    return serverBuilder.build().start();
  }

  private void runServer() throws IOException {
    Server server = createSdsServer();
  }

  private String getAndSaveNonce() {
    lastRespondedNonce = Long.toHexString(System.currentTimeMillis());
    return lastRespondedNonce;
  }

  public static void main(String[] args) throws IOException {
    DummySdsServer dummySdsServer = new DummySdsServer();
    dummySdsServer.runServer();
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    in.readLine();
  }

}
