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

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.MoreExecutors;
import google.security.meshca.v1.MeshCertificateServiceGrpc;
import google.security.meshca.v1.Meshca;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.BackoffPolicy;
import io.grpc.internal.TimeProvider;
import io.grpc.internal.testing.TestUtils;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.xds.internal.sds.trust.CertificateUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.*;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.*;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.internal.sds.CommonTlsContextTestsUtil.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

/** Unit tests for {@link MeshCaCertificateProvider}. */
@RunWith(JUnit4.class)
public class MeshCaCertificateProviderTest {
  private static final Logger logger = Logger.getLogger(MeshCaCertificateProviderTest.class.getName());
  private static final String TEST_STS_TOKEN = "test-stsToken";
  public static final long RENEWAL_GRACE_PERIOD_SECONDS = TimeUnit.HOURS.toSeconds(1L);

  @Rule
  public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();

  private static class RequestRecord {
    final Meshca.MeshCertificateRequest request;
    final long nanoTime;

    RequestRecord(Meshca.MeshCertificateRequest req) {
      this.request = req;
      nanoTime = System.nanoTime();
    }
  }

  private static final long START_DELAY = 200_000_000L;  // 0.2 seconds
  private static final long[] DELAY_VALUES = {START_DELAY, START_DELAY * 2, START_DELAY * 4};
  private static final long CERT0_EXPIRY_TIME_MILLIS = 1899853658000L;
  public static final long CERT_VALIDITY_MILLIS = TimeUnit.MILLISECONDS.convert(12, TimeUnit.HOURS);
  // current time for 12 hour cert validity
  private static final long CURRENT_TIME_NANOS =
          TimeUnit.MILLISECONDS.toNanos(CERT0_EXPIRY_TIME_MILLIS - CERT_VALIDITY_MILLIS);

  private final Queue<RequestRecord> receivedRequests = new ArrayDeque<>();
  private final Queue<String> receivedStsCreds = new ArrayDeque<>();
  private final Queue<Object> responsesToSend = new ArrayDeque<>();
  private final Queue<String> oauth2Tokens = new ArrayDeque<>();
  private final AtomicBoolean callEnded = new AtomicBoolean(true);
  @Mock
  private MeshCertificateServiceGrpc.MeshCertificateServiceImplBase mockedMeshCaService;

  private ManagedChannel channel;
  private MeshCaCertificateProvider provider;

  @Mock
  private CertificateProvider.Watcher mockWatcher;
  @Mock
  private BackoffPolicy.Provider backoffPolicyProvider;
  @Mock
  private BackoffPolicy backoffPolicy;
  @Spy
  private GoogleCredentials oauth2Creds;
  @Mock
  private ScheduledExecutorService timeService;
  @Mock
  private TimeProvider timeProvider;

  @Before
  public void setUp() throws IOException {
    MockitoAnnotations.initMocks(this);
    when(backoffPolicyProvider.get()).thenReturn(backoffPolicy);
    when(backoffPolicy.nextBackoffNanos()).thenReturn(DELAY_VALUES[0], DELAY_VALUES[1], DELAY_VALUES[2]);
    doAnswer(new Answer<AccessToken>() {
      @Override
      public AccessToken answer(InvocationOnMock invocation) throws Throwable {
        return new AccessToken(oauth2Tokens.poll(), new Date(System.currentTimeMillis() + 1000L));
      }
    }).when(oauth2Creds).refreshAccessToken();
    //when(oauth2Creds.refreshAccessToken()).thenReturn(new AccessToken(oauth2Tokens.poll(), new Date(System.currentTimeMillis() + 1000L)));
    final String meshCaUri = InProcessServerBuilder.generateName();
    MeshCertificateServiceGrpc.MeshCertificateServiceImplBase meshCaServiceImpl =
        new MeshCertificateServiceGrpc.MeshCertificateServiceImplBase() {
          int requestNum = 0;

          @Override
          public void createCertificate(
              google.security.meshca.v1.Meshca.MeshCertificateRequest request,
              io.grpc.stub.StreamObserver<google.security.meshca.v1.Meshca.MeshCertificateResponse>
                  responseObserver) {
            assertThat(callEnded.get()).isTrue(); // ensure previous call was ended
            callEnded.set(false);
            Context.current()
                .addListener(
                    new Context.CancellationListener() {
                      @Override
                      public void cancelled(Context context) {
                        callEnded.set(true);
                      }
                    },
                    MoreExecutors.directExecutor());
            receivedRequests.offer(new RequestRecord(request));
            Object response = responsesToSend.poll();
            if (response instanceof Throwable) {
              responseObserver.onError((Throwable)response);
            } else if (response instanceof List<?>) {
              List<String> certChainInResponse = (List<String>)response;
              Meshca.MeshCertificateResponse responseToSend =
                  Meshca.MeshCertificateResponse.newBuilder()
                      .addAllCertChain(certChainInResponse)
                      .build();
              responseObserver.onNext(responseToSend);
              responseObserver.onCompleted();
            } else {
              callEnded.set(true);
            }
          }
        };
    mockedMeshCaService =
            mock(MeshCertificateServiceGrpc.MeshCertificateServiceImplBase.class, delegatesTo(meshCaServiceImpl));
    ServerInterceptor interceptor = new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String value = headers.get(MeshCaCertificateProvider.KEY_FOR_AUTHORIZATION);
        if (value != null) {
          receivedStsCreds.offer(value);
        }
        return next.startCall(call, headers);
      }
    };
    cleanupRule.register(
            InProcessServerBuilder
                    .forName(meshCaUri)
                    .addService(mockedMeshCaService)
                    .intercept(interceptor)
                    .directExecutor()
                    .build()
                    .start());
    channel =
            cleanupRule.register(InProcessChannelBuilder.forName(meshCaUri).directExecutor().build());
    MeshCaCertificateProvider.ChannelFactory channelFactory = new MeshCaCertificateProvider.ChannelFactory() {
      @Override
      ManagedChannel createChannel(String serverUri) {
        assertThat(serverUri).isEqualTo(meshCaUri);
        //channel =
        //        cleanupRule.register(InProcessChannelBuilder.forName(meshCaUri).directExecutor().build());
        return channel;
      }
    };
    CertificateProvider.DistributorWatcher watcher = new CertificateProvider.DistributorWatcher();
    watcher.addWatcher(mockWatcher);  //
    provider = new MeshCaCertificateProvider(watcher, true,
            meshCaUri,
            "https://container.googleapis.com/v1/projects/meshca-unit-test/locations/us-west2-a/clusters/meshca-cluster",
            TimeUnit.HOURS.toSeconds(9L), 2048, "RSA", "SHA256withRSA",
            channelFactory, backoffPolicyProvider, RENEWAL_GRACE_PERIOD_SECONDS, 4,
            oauth2Creds, timeService, timeProvider);
  }

  @Test
  public void getCertificate() throws IOException, CertificateException {
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    responsesToSend.offer(ImmutableList.of(
            getResourceContents(SERVER_0_PEM_FILE),
            getResourceContents(SERVER_1_PEM_FILE),
            getResourceContents(CA_PEM_FILE)));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
    doReturn(scheduledFuture).when(timeService).schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    Meshca.MeshCertificateRequest receivedReq = receivedRequests.poll().request;
    assertThat(receivedReq.getValidity().getSeconds()).isEqualTo(TimeUnit.HOURS.toSeconds(9L));
    // not easy to decode CSR: just check the PEM format delimiters
    String csr = receivedReq.getCsr();
    assertThat(receivedReq.getCsr()).startsWith("-----BEGIN NEW CERTIFICATE REQUEST-----\n");
    assertThat(receivedReq.getCsr()).endsWith("\n-----END NEW CERTIFICATE REQUEST-----\n");
    verifyStsCredentialsInMetadata(1);
    verify(timeService, times(1)).schedule(any(Runnable.class),
       eq(TimeUnit.MILLISECONDS.toSeconds(CERT_VALIDITY_MILLIS - TimeUnit.SECONDS.toMillis(RENEWAL_GRACE_PERIOD_SECONDS))),
        eq(TimeUnit.SECONDS));
    verifyMockWatcher();
  }

  @Test
  public void getCertificate_withError() throws IOException, CertificateException {
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    responsesToSend.offer(new StatusRuntimeException(Status.FAILED_PRECONDITION));
    ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
    doReturn(scheduledFuture).when(timeService).schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    verify(mockWatcher, never()).updateCertificate(any(PrivateKey.class), ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, never()).updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, times(1)).onError(Status.FAILED_PRECONDITION);
    verify(timeService, times(1)).schedule(any(Runnable.class),
            eq(MeshCaCertificateProvider.INITIAL_DELAY_SECONDS),
            eq(TimeUnit.SECONDS));
    verifyStsCredentialsInMetadata(1);
  }

  @Test
  public void getCertificate_withError_withExistingCert() throws IOException, CertificateException {
    PrivateKey mockKey = mock(PrivateKey.class);
    X509Certificate mockCert = mock(X509Certificate.class);
    // have current cert expire in 3 hours from current time
    long threeHoursFromNowMillis = TimeUnit.NANOSECONDS.toMillis(CURRENT_TIME_NANOS + TimeUnit.HOURS.toNanos(3));
    when(mockCert.getNotAfter()).thenReturn(new Date(threeHoursFromNowMillis));
    provider.getWatcher().updateCertificate(mockKey, ImmutableList.of(mockCert));
    reset(mockWatcher);
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    responsesToSend.offer(new StatusRuntimeException(Status.FAILED_PRECONDITION));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
    doReturn(scheduledFuture).when(timeService).schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    verify(mockWatcher, never()).updateCertificate(any(PrivateKey.class), ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, never()).updateTrustedRoots(ArgumentMatchers.<X509Certificate>anyList());
    verify(mockWatcher, times(1)).onError(Status.FAILED_PRECONDITION);
    verify(timeService, times(1)).schedule(any(Runnable.class),
            eq(5400L),
            eq(TimeUnit.SECONDS));
    verifyStsCredentialsInMetadata(1);
  }

  @Test
  public void getCertificate_retriesWithErrors() throws IOException, CertificateException {
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    oauth2Tokens.offer(TEST_STS_TOKEN + "1");
    oauth2Tokens.offer(TEST_STS_TOKEN + "2");
    responsesToSend.offer(new StatusRuntimeException(Status.UNKNOWN));
    responsesToSend.offer(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED));
    responsesToSend.offer(ImmutableList.of(
            getResourceContents(SERVER_0_PEM_FILE),
            getResourceContents(SERVER_1_PEM_FILE),
            getResourceContents(CA_PEM_FILE)));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
    doReturn(scheduledFuture).when(timeService).schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    assertThat(receivedRequests.size()).isEqualTo(3);
    /*RequestRecord[] requestRecords = receivedRequests.toArray(new RequestRecord[0]);
    assertThat(requestRecords[1].nanoTime - requestRecords[0].nanoTime)
        .isIn(Range.closed(9500L, 10500L)); */
    verify(timeService, times(1)).schedule(any(Runnable.class),
            eq(TimeUnit.MILLISECONDS.toSeconds(CERT_VALIDITY_MILLIS - TimeUnit.SECONDS.toMillis(RENEWAL_GRACE_PERIOD_SECONDS))),
            eq(TimeUnit.SECONDS));
    verifyMockWatcher();
    verifyStsCredentialsInMetadata(3);
  }

  @Test
  public void getCertificate_retriesWithTimeouts() throws IOException, CertificateException {
    //provider.stsToken = TEST_STS_TOKEN;
    oauth2Tokens.offer(TEST_STS_TOKEN + "0");
    oauth2Tokens.offer(TEST_STS_TOKEN + "1");
    oauth2Tokens.offer(TEST_STS_TOKEN + "2");
    oauth2Tokens.offer(TEST_STS_TOKEN + "3");
    responsesToSend.offer(new Object());
    responsesToSend.offer(new Object());
    responsesToSend.offer(new Object());
    responsesToSend.offer(ImmutableList.of(
            getResourceContents(SERVER_0_PEM_FILE),
            getResourceContents(SERVER_1_PEM_FILE),
            getResourceContents(CA_PEM_FILE)));
    when(timeProvider.currentTimeNanos()).thenReturn(CURRENT_TIME_NANOS);
    ScheduledFuture<?> scheduledFuture = mock(ScheduledFuture.class);
    doReturn(scheduledFuture).when(timeService).schedule(any(Runnable.class), any(Long.TYPE), eq(TimeUnit.SECONDS));
    provider.refreshCertificate();
    assertThat(receivedRequests.size()).isEqualTo(4);
    RequestRecord[] requestRecords = receivedRequests.toArray(new RequestRecord[0]);
    logger.info("request0 time " + requestRecords[0].nanoTime);
    logger.info("request1 time " + requestRecords[1].nanoTime);
    logger.info("request2 time " + requestRecords[2].nanoTime);
    logger.info("request3 time " + requestRecords[3].nanoTime);

    logger.info("Delay: 0->1 is " + (requestRecords[1].nanoTime - requestRecords[0].nanoTime));
    logger.info("Delay: 1->2 is " + (requestRecords[2].nanoTime - requestRecords[1].nanoTime));
    logger.info("Delay: 2->3 is " + (requestRecords[3].nanoTime - requestRecords[2].nanoTime));
    /*assertThat(requestRecords[1].nanoTime - requestRecords[0].nanoTime)
        .isIn(Range.closed((long)(DELAY_VALUES[0] * 0.8), (long)(DELAY_VALUES[0] * 1.2)));
    assertThat(requestRecords[2].nanoTime - requestRecords[1].nanoTime)
            .isIn(Range.closed((long)(DELAY_VALUES[1] * 0.8), (long)(DELAY_VALUES[1] * 1.2))); */
    verify(timeService, times(1)).schedule(any(Runnable.class),
            eq(TimeUnit.MILLISECONDS.toSeconds(CERT_VALIDITY_MILLIS - TimeUnit.SECONDS.toMillis(RENEWAL_GRACE_PERIOD_SECONDS))),
            eq(TimeUnit.SECONDS));
    verifyMockWatcher();
    verifyStsCredentialsInMetadata(4);
  }

  private void verifyMockWatcher() throws IOException, CertificateException {
    ArgumentCaptor<List<X509Certificate>> certChainCaptor = ArgumentCaptor.forClass(null);
    verify(mockWatcher, times(1)).updateCertificate(any(PrivateKey.class), certChainCaptor.capture());
    List<X509Certificate> certChain = certChainCaptor.getValue();
    assertThat(certChain).hasSize(3);
    assertThat(certChain.get(0)).isEqualTo(getCertFromResourceName(SERVER_0_PEM_FILE));
    assertThat(certChain.get(1)).isEqualTo(getCertFromResourceName(SERVER_1_PEM_FILE));
    assertThat(certChain.get(2)).isEqualTo(getCertFromResourceName(CA_PEM_FILE));

    ArgumentCaptor<List<X509Certificate>> rootsCaptor = ArgumentCaptor.forClass(null);
    verify(mockWatcher, times(1)).updateTrustedRoots(rootsCaptor.capture());
    List<X509Certificate> roots = rootsCaptor.getValue();
    assertThat(roots).hasSize(1);
    assertThat(roots.get(0)).isEqualTo(getCertFromResourceName(CA_PEM_FILE));
    verify(mockWatcher, never()).onError(any(Status.class));
  }


  private static X509Certificate getCertFromResourceName(String resourceName) throws IOException, CertificateException {
    return CertificateUtils.toX509Certificate(new ByteArrayInputStream(getResourceContents(resourceName).getBytes()));
  }

  private static String getResourceContents(String resourceName) throws IOException {

    InputStream inputStream = TestUtils.class.getResourceAsStream("/certs/" + resourceName);
    String text = null;
    try (Reader reader = new InputStreamReader(inputStream)) {
      text = CharStreams.toString(reader);
    }
    return text;
  }

  private void verifyStsCredentialsInMetadata(int count) {
    assertThat(receivedStsCreds).hasSize(count);
    for (int i = 0; i < count; i++) {
      assertThat(receivedStsCreds.poll()).isEqualTo("Bearer " + TEST_STS_TOKEN + i);
    }
  }
}
