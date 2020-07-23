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

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;
import google.security.meshca.v1.MeshCertificateServiceGrpc;
import google.security.meshca.v1.Meshca;
import io.grpc.*;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.internal.BackoffPolicy;
import io.grpc.xds.internal.sds.trust.CertificateUtils;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;

import javax.security.auth.x500.X500Principal;
import java.io.*;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static io.grpc.Status.Code.*;

// TODOs
// decode config inside MeshCaCertificateProviderProvider
// first call to refreshCertificate
// 2: refresh cert - few minutes before previous expiry
// 4: close() implementation : cleanup of the provider
// 7: grace period
// 8: last certificate or at least its expiry
// rename watcher to listener
// replace long ctor param list with builder
// in test responsesToSend to use a proper type/discriminator instead of Object
// 1: integrate with STS once STS is in
// 9: when to erase the last certificate in the distributor? only if the current cert in the distributor has expired
// 10: notify error only if the current cert has expired
// 3: cleanup of the code: add final, private and appropriate access specifiers
// 5: unit tests
// 6: backoffPolicy, retryPolicy


class MeshCaCertificateProvider extends CertificateProvider {
  private static final Logger logger = Logger.getLogger(MeshCaCertificateProvider.class.getName());

  @VisibleForTesting
  static final Metadata.Key<String> KEY_FOR_ZONE_INFO =
          Metadata.Key.of("x-goog-request-params", Metadata.ASCII_STRING_MARSHALLER);
  @VisibleForTesting
  static final Metadata.Key<String> KEY_FOR_AUTHORIZATION =
          Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

  /**
   * A interceptor to handle client header.
   */
  class HeaderInterceptor implements ClientInterceptor {
    private final String zone;

    HeaderInterceptor(String zone) {
      this.zone = zone;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
                                                               CallOptions callOptions, Channel next) {
      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          /* put custom header */
          headers.put(KEY_FOR_ZONE_INFO, zone);
          // temporary until we have the proper StsCredential support
          //headers.put(KEY_FOR_AUTHORIZATION, "Bearer " + stsToken);
          super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {},
           headers);
        }
      };
    }
  }


  protected MeshCaCertificateProvider(DistributorWatcher watcher, boolean notifyCertUpdates,
                                      String meshCaUrl, String gkeClusterURL, long validitySeconds,
                                      int keySize, String alg, String signatureAlg, ChannelFactory channelFactory,
                                      BackoffPolicy.Provider backoffPolicyProvider, long renewalGracePeriodSeconds,
                                      int maxRetryAttempts, GoogleCredentials oauth2Creds) {
    super(watcher, notifyCertUpdates);
    this.meshCaUri = meshCaUrl;
    this.gkeClusterURL = gkeClusterURL;
    this.zone = parseZone(gkeClusterURL);
    this.headerInterceptor = new HeaderInterceptor(zone);
    this.validitySeconds = validitySeconds;
    this.keySize = keySize;
    this.alg = alg;
    this.signatureAlg = signatureAlg;
    this.channelFactory = channelFactory;
    this.backoffPolicyProvider = checkNotNull(backoffPolicyProvider, "backoffPolicyProvider");
    checkArgument(renewalGracePeriodSeconds > 0L && renewalGracePeriodSeconds < validitySeconds,
     "renewalGracePeriodSeconds");
    this.renewalGracePeriodSeconds = renewalGracePeriodSeconds;
    this.maxRetryAttempts = maxRetryAttempts;
    this.oauth2Creds = checkNotNull(oauth2Creds, "oauth2Creds");
  }

  @Override
  public void start() {
    // TODO start the first refresh immediately
  }

  @Override
  public void close() {
    // TODO stop everything and tear down this provider
  }

  void refreshCertificate() {
    // Assign a unique request ID for all the retries.
    String reqID = UUID.randomUUID().toString();
    Duration duration = Duration.newBuilder().setSeconds(validitySeconds).build();
    KeyPair keyPair = generateKeyPair();
    String csr = generateCSR(keyPair);

    ManagedChannel channel = channelFactory.createChannel(meshCaUri);
    MeshCertificateServiceGrpc.MeshCertificateServiceBlockingStub stub = createStubToMeshCA(channel);
    List<X509Certificate> x509Chain = makeRequestWithRetries(stub, reqID, duration, csr);
    shutdownChannel(channel);
    if (x509Chain != null) {
      getWatcher().updateCertificate(keyPair.getPrivate(), x509Chain);
      getWatcher().updateTrustedRoots(ImmutableList.of(x509Chain.get(x509Chain.size() - 1)));
    }
    // TODO set the timer for the next refresh: validity minus grace period
  }

  private static void shutdownChannel(ManagedChannel channel) {
    channel.shutdown();
    try {
      channel.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      logger.log(Level.SEVERE, "awaiting channel Termination", ex);
      channel.shutdownNow();
    }
  }

  private List<X509Certificate> getX509CertificatesFromResponse(Meshca.MeshCertificateResponse response) {
    List<String> certChain = response.getCertChainList();
    List<X509Certificate> x509Chain = new ArrayList<>(certChain.size());
    for (String certString : certChain) {
      try {
        x509Chain.add(
            CertificateUtils.toX509Certificate(new ByteArrayInputStream(certString.getBytes())));
      } catch (CertificateException | IOException ex) {
        logger.log(Level.SEVERE, "extracting x509 cert", ex);
      }
    }
    return x509Chain;
  }

  private List<X509Certificate> makeRequestWithRetries(MeshCertificateServiceGrpc.MeshCertificateServiceBlockingStub stub,
    String reqID, Duration duration, String csr) {
    Meshca.MeshCertificateRequest request = Meshca.MeshCertificateRequest.newBuilder()
            .setValidity(duration)
            .setCsr(csr)
            .setRequestId(reqID)
            .build();

    BackoffPolicy backoffPolicy = backoffPolicyProvider.get();
    Throwable lastException = null;
    long tick = 0;
    for (int i = 0; i < maxRetryAttempts; i++) {
      try {
        long xyz = backoffPolicy.nextBackoffNanos();
        logger.info("policy-delay = " + xyz + "; currentTime= " + (tick = System.nanoTime()));
        Meshca.MeshCertificateResponse response = stub.withDeadlineAfter(xyz, TimeUnit.NANOSECONDS)
          .createCertificate(request);
        return getX509CertificatesFromResponse(response);
      } catch (Throwable t) {
        if (!retriableThrowable(t)) {
          getWatcher().onError(Status.fromThrowable(t));
          return null;
        }
        long curTime = System.nanoTime();
        logger.info("Retriable exception received; currentTime= " + curTime + " observed-delay = " + (curTime - tick));
        lastException = t;
      }
    }
    getWatcher().onError(Status.fromThrowable(lastException)); //TODO: only if no current valid cert
    return null;
  }

  private static boolean retriableThrowable(Throwable t) {
    if (t instanceof StatusRuntimeException) {
      return retriable(((StatusRuntimeException)t).getStatus());
    } else if (t.getCause() instanceof StatusRuntimeException) {
      return retriable(((StatusRuntimeException)(t.getCause())).getStatus());
    }
    return false;
  }

  private static boolean retriable(Status status) {
    return RETRIABLE_CODES.contains(status.getCode());
  }

  KeyPair generateKeyPair() {
    try{
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(alg);
      keyPairGenerator.initialize(keySize);
      return keyPairGenerator.generateKeyPair();
    } catch (Exception ex) {
      logger.log(Level.SEVERE, "Generating keyPair", ex);
    }
    return null;
  }

  String generateCSR(KeyPair pair) {
    PKCS10CertificationRequestBuilder p10Builder = new JcaPKCS10CertificationRequestBuilder(
            new X500Principal("CN=EXAMPLE.COM"), pair.getPublic());
    JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder("SHA256withRSA");
    try {
      ContentSigner signer = csBuilder.build(pair.getPrivate());
      PKCS10CertificationRequest csr = p10Builder.build(signer);
      //return csr.getEncoded();
      PemObject pemObject = new PemObject("NEW CERTIFICATE REQUEST", csr.getEncoded());
      StringWriter str = new StringWriter();
      JcaPEMWriter pemWriter = new JcaPEMWriter(str);
      pemWriter.writeObject(pemObject);
      pemWriter.close();
      str.close();
      return str.toString();
    } catch (OperatorCreationException|IOException ex) {
      logger.log(Level.SEVERE, "Generating CSR", ex);
    }
    return null;
  }

  MeshCertificateServiceGrpc.MeshCertificateServiceBlockingStub createStubToMeshCA(ManagedChannel channel) {
    MeshCertificateServiceGrpc.MeshCertificateServiceBlockingStub stub = MeshCertificateServiceGrpc.newBlockingStub(channel);
    stub = stub.withCallCredentials(MoreCallCredentials.from(oauth2Creds));
    return stub.withInterceptors(headerInterceptor);
  }

  static String parseZone(String gkeClusterURL) {
    // input: https://container.googleapis.com/v1/projects/testproj/locations/us-central1-c/clusters/cluster1
    // output: us-central1-c
    Pattern p = Pattern.compile(".*/projects/(.*)/locations/(.*)/clusters/.*");
    Matcher matcher = p.matcher(gkeClusterURL);
    if (matcher.groupCount() < 3) {
      return "";
    }
    return matcher.group(2);
  }

  /**
   * Factory for creating channels to MeshCA sever.
   */
  abstract static class ChannelFactory {
    private static final ChannelFactory DEFAULT_INSTANCE = new ChannelFactory() {

      /**
       * Creates a channel to the URL in the given list.
       */
      @Override
      ManagedChannel createChannel(String serverUri) {
        checkArgument(serverUri != null && !serverUri.isEmpty(), "serverUri is null/empty!");
        logger.log(Level.INFO, "Creating channel to {0}", serverUri);
        // Use default channel credentials
        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forTarget(serverUri);
        return channelBuilder
                .keepAliveTime(1, TimeUnit.MINUTES)
                .build();
      }
    };

    static ChannelFactory getInstance() {
      return DEFAULT_INSTANCE;
    }

    /**
     * Creates a channel to the server.
     */
    abstract ManagedChannel createChannel(String serverUri);
  }

  private static final EnumSet<Status.Code> RETRIABLE_CODES =
      EnumSet.of(
          CANCELLED,
          UNKNOWN,
          DEADLINE_EXCEEDED,
          RESOURCE_EXHAUSTED,
          ABORTED,
          INTERNAL,
          UNAVAILABLE);

  private final int maxRetryAttempts;
  String stsToken;
  HeaderInterceptor headerInterceptor;
  ChannelFactory channelFactory;
  private final BackoffPolicy.Provider backoffPolicyProvider;
  private final String meshCaUri;
  private final String gkeClusterURL;
  private final String zone;
  private final long validitySeconds;
  private final long renewalGracePeriodSeconds;
  private final int keySize;
  private final String alg;
  private final String signatureAlg;
  private final GoogleCredentials oauth2Creds;
}
