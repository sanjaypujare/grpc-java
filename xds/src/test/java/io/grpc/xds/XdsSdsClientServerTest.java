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

package io.grpc.xds;

import static com.google.common.truth.Truth.assertThat;
import static io.grpc.xds.XdsClientWrapperForServerSdsTest.buildFilterChainMatch;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.grpc.testing.protobuf.SimpleRequest;
import io.grpc.testing.protobuf.SimpleResponse;
import io.grpc.testing.protobuf.SimpleServiceGrpc;
import io.grpc.xds.internal.sds.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import javax.net.ssl.SSLHandshakeException;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link XdsChannelBuilder} and {@link XdsServerBuilder} for plaintext/TLS/mTLS
 * modes.
 */
@RunWith(JUnit4.class)
public class XdsSdsClientServerTest {
  private static final Logger logger = Logger.getLogger(XdsSdsClientServerTest.class.getName());

  /** Untrusted server. */
  private static final String BAD_SERVER_PEM_FILE = "badserver.pem";
  private static final String BAD_SERVER_KEY_FILE = "badserver.key";

  @Rule public final GrpcCleanupRule cleanupRule = new GrpcCleanupRule();
  private Server server;
  private TestSdsNameResolverProvider testSdsNameResolverProvider;
  @Mock private TestSdsNameResolver.Callback callback;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
    testSdsNameResolverProvider = new TestSdsNameResolverProvider(callback);
    NameResolverRegistry.getDefaultRegistry().register(testSdsNameResolverProvider);
  }

  @After
  public void tearDown() {
    NameResolverRegistry.getDefaultRegistry().deregister(testSdsNameResolverProvider);
  }

  @Test
  public void plaintextClientServer() throws IOException {
    getXdsServer(/* downstreamTlsContext= */ null);
    buildClientAndTest(
        /* upstreamTlsContext= */ null, /* overrideAuthority= */ null, "buddy", server.getPort());
  }

  /** TLS channel - no mTLS. */
  @Test
  public void tlsClientServer_noClientAuthentication() throws IOException {
    DownstreamTlsContext downstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            SecretVolumeSslContextProviderTest.SERVER_1_KEY_FILE,
            SecretVolumeSslContextProviderTest.SERVER_1_PEM_FILE,
            null);

    getXdsServer(downstreamTlsContext);

    // for TLS client doesn't need cert but needs trustCa
    UpstreamTlsContext upstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
            null, null, SecretVolumeSslContextProviderTest.CA_PEM_FILE);
    buildClientAndTest(upstreamTlsContext, "foo.test.google.fr", "buddy", server.getPort());
  }

  private XdsClient.ListenerWatcher mtlsCommonTest(UpstreamTlsContext upstreamTlsContext)
      throws IOException {
    DownstreamTlsContext downstreamTlsContext =
        SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
            SecretVolumeSslContextProviderTest.SERVER_1_KEY_FILE,
            SecretVolumeSslContextProviderTest.SERVER_1_PEM_FILE,
            SecretVolumeSslContextProviderTest.CA_PEM_FILE);

    XdsClient.ListenerWatcher listenerWatcher = getXdsServer(downstreamTlsContext);
    buildClientAndTest(upstreamTlsContext, "foo.test.google.fr", "buddy", server.getPort());
    return listenerWatcher;
  }

  /** mTLS - client auth enabled. */
  @Test
  public void mtlsClientServer_withClientAuthentication() throws IOException {
    UpstreamTlsContext upstreamTlsContext =
            SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
                    SecretVolumeSslContextProviderTest.CLIENT_KEY_FILE,
                    SecretVolumeSslContextProviderTest.CLIENT_PEM_FILE,
                    SecretVolumeSslContextProviderTest.CA_PEM_FILE);
    mtlsCommonTest(upstreamTlsContext);
  }

  /** mTLS - client auth enabled then update server certs to untrusted. */
  @Test
  public void mtlsClientServer_changeServerContext_expectException() throws IOException {
    UpstreamTlsContext upstreamTlsContext =
            SecretVolumeSslContextProviderTest.buildUpstreamTlsContextFromFilenames(
                    SecretVolumeSslContextProviderTest.CLIENT_KEY_FILE,
                    SecretVolumeSslContextProviderTest.CLIENT_PEM_FILE,
                    SecretVolumeSslContextProviderTest.CA_PEM_FILE);
    XdsClient.ListenerWatcher listenerWatcher = mtlsCommonTest(upstreamTlsContext);
    DownstreamTlsContext downstreamTlsContext =
            SecretVolumeSslContextProviderTest.buildDownstreamTlsContextFromFilenames(
                    BAD_SERVER_KEY_FILE,
                    BAD_SERVER_PEM_FILE,
                    SecretVolumeSslContextProviderTest.CA_PEM_FILE);
    createListenerUpdate(server.getPort(), downstreamTlsContext, listenerWatcher);
    try {
      buildClientAndTest(upstreamTlsContext, "foo.test.google.fr", "buddy", server.getPort());
      fail("exception expected");
    } catch (StatusRuntimeException sre) {
      assertThat(sre).hasCauseThat().isInstanceOf(SSLHandshakeException.class);
      assertThat(sre).hasCauseThat().hasMessageThat().isEqualTo("General OpenSslEngine problem");
    }
  }

  private XdsClient.ListenerWatcher getXdsServer(DownstreamTlsContext downstreamTlsContext)
      throws IOException {
    int freePort = findFreePort();
    XdsServerBuilder builder =
        XdsServerBuilder.forPort(freePort).addService(new SimpleServiceImpl());
    final XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        createXdsClientWrapperForServerSds(freePort, downstreamTlsContext);
    SdsProtocolNegotiators.ServerSdsProtocolNegotiator serverSdsProtocolNegotiator =
        new SdsProtocolNegotiators.ServerSdsProtocolNegotiator(xdsClientWrapperForServerSds);
    server = cleanupRule.register(builder.buildServer(serverSdsProtocolNegotiator)).start();
    return xdsClientWrapperForServerSds.getListenerWatcher();
  }

  /** Creates a XdsClientWrapperForServerSds for a port and tlsContext. */
  public static XdsClientWrapperForServerSds createXdsClientWrapperForServerSds(
      int freePort, DownstreamTlsContext downstreamTlsContext) {
    XdsClient mockXdsClient = mock(XdsClient.class);
    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        new XdsClientWrapperForServerSds(freePort, mockXdsClient, null);
    createListenerUpdate(freePort, downstreamTlsContext,
        xdsClientWrapperForServerSds.getListenerWatcher());
    return xdsClientWrapperForServerSds;
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }

  private static void createListenerUpdate(
      int port, DownstreamTlsContext tlsContext, XdsClient.ListenerWatcher registeredWatcher) {
    EnvoyServerProtoData.Listener listener =
        buildListener("listener1", "0.0.0.0", port, tlsContext);
    XdsClient.ListenerUpdate listenerUpdate =
        XdsClient.ListenerUpdate.newBuilder().setListener(listener).build();
    registeredWatcher.onListenerChanged(listenerUpdate);
  }

  static EnvoyServerProtoData.Listener buildListener(
          String name,
          String address,
          int port,
          DownstreamTlsContext tlsContext) {
    EnvoyServerProtoData.FilterChainMatch filterChainMatch =
            buildFilterChainMatch(port, address);
    EnvoyServerProtoData.FilterChain filterChain1 =
            new EnvoyServerProtoData.FilterChain(filterChainMatch, tlsContext);
    EnvoyServerProtoData.Listener listener =
            new EnvoyServerProtoData.Listener(name, address, Arrays.asList(filterChain1));
    return listener;
  }

  private void buildClientAndTest(
          final UpstreamTlsContext upstreamTlsContext,
          String overrideAuthority,
          String requestMessage,
          final int serverPort) {

    XdsChannelBuilder builder =
        XdsChannelBuilder.forTarget("sdstest:///localhost:" + serverPort)/*.tlsContext(upstreamTlsContext)*/;
    if (overrideAuthority != null) {
      builder = builder.overrideAuthority(overrideAuthority);
    }
    SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub =
        SimpleServiceGrpc.newBlockingStub(cleanupRule.register(builder.build()));
    logger.info("Before answer:  port="+serverPort);
    doAnswer(new Answer<NameResolver.ResolutionResult>() {
      @Override
      public NameResolver.ResolutionResult answer(InvocationOnMock invocation) throws Throwable {
        // TODO: check all types, and check the address matches dest address
        // cherck for dest addr being IPV4 localhost
        Object[] args = invocation.getArguments();
        logger.info("Inside answer1: args length="+args.length+", port="+serverPort);
        NameResolver.ResolutionResult resolutionResult = (NameResolver.ResolutionResult)args[0];
        List<EquivalentAddressGroup> addresses = resolutionResult.getAddresses();
        if (upstreamTlsContext != null && addresses != null) {
          logger.info("Inside answer2: List size="+addresses.size()+", port="+serverPort);
          ArrayList<EquivalentAddressGroup> copyList = new ArrayList<>(addresses.size());
          for (EquivalentAddressGroup eag : addresses) {
            // TODO check that eag.getAddresses() is an IPv4 localhost address
            EquivalentAddressGroup eagCopy =
                    new EquivalentAddressGroup(eag.getAddresses(),
                            eag.getAttributes()
                                    .toBuilder()
                                    .set(XdsAttributes.ATTR_UPSTREAM_TLS_CONTEXT, upstreamTlsContext)
                                    .build()
                    );
            copyList.add(eagCopy);
          }
          resolutionResult = resolutionResult.toBuilder().setAddresses(copyList).build();
        }
        return resolutionResult;
      }
    }).when(callback).onResult(any(NameResolver.ResolutionResult.class));
    String resp = unaryRpc(requestMessage, blockingStub);
    assertThat(resp).isEqualTo("Hello " + requestMessage);
  }

  /** Say hello to server. */
  private static String unaryRpc(
      String requestMessage, SimpleServiceGrpc.SimpleServiceBlockingStub blockingStub) {
    SimpleRequest request = SimpleRequest.newBuilder().setRequestMessage(requestMessage).build();
    SimpleResponse response = blockingStub.unaryRpc(request);
    return response.getResponseMessage();
  }

  private static class SimpleServiceImpl extends SimpleServiceGrpc.SimpleServiceImplBase {

    @Override
    public void unaryRpc(SimpleRequest req, StreamObserver<SimpleResponse> responseObserver) {
      SimpleResponse response =
          SimpleResponse.newBuilder()
              .setResponseMessage("Hello " + req.getRequestMessage())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    }
  }
}
