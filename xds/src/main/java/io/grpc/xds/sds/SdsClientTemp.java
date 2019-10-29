package io.grpc.xds.sds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.envoyproxy.envoy.api.v2.core.GrpcService.GoogleGrpc;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.unix.DomainSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * temporary implementation of our SDS client. A more robust implementation will follow
 */
public class SdsClientTemp {
  private static final Logger logger = Logger.getLogger(SdsClientTemp.class.getName());

  // SecretTypeURL defines the type URL for Envoy secret proto.
  private static final String SECRET_TYPE_URL = "type.googleapis.com/envoy.api.v2.auth.Secret";

  private static SdsClientTemp instance;

  private ConfigSource configSource;
  String udsTarget;
  private SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub stub;

  /**
   * Starts resource discovery with SDS protocol. This should be the first method to be called in
   * this class. It should only be called once.
   */
  void start() {
    stub = buildBidiStub(udsTarget);
    logger.info("Start doStreaming to authority: " + stub.getChannel().authority());
    ResponseObserver responseObserver =
        new ResponseObserver();
    StreamObserver<DiscoveryRequest> requestStreamObserver =
        stub.streamSecrets(responseObserver);
    responseObserver.requestStreamObserver = requestStreamObserver;
    synchronized (responseObserver) {
      while (!responseObserver.completed) {
        try {
          responseObserver.wait();
        } catch (InterruptedException e) {
          logger.log(Level.SEVERE, "wait", e);
        }
      }
    }
  }

  /**
   * Stops resource discovery. No method in this class should be called after this point.
   */
  void shutdown() {

  }


  static synchronized SdsClientTemp getInstance(ConfigSource configSource) {
    if (instance == null) {
      instance = new SdsClientTemp(configSource);
      return instance;
    }
    // check if apiConfigSource match
    if (instance.configSource.equals(configSource)) {
      return instance;
    }
    throw new UnsupportedOperationException("Multiple SdsClientTemp with different ApiConfigSource not supported");
  }

  /**
   * create the client with this apiConfigSource.
   * @param configSource
   */
  SdsClientTemp(ConfigSource configSource) {
    checkNotNull(configSource, "configSource");
    // for now we support only Google grpc UDS
    extractUdsTarget(configSource);
  }

  void extractUdsTarget(ConfigSource configSource) {
    checkArgument(configSource.hasApiConfigSource(), "only configSource with ApiConfigSource supported");
    ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
    checkArgument(ApiType.GRPC.equals(apiConfigSource.getApiType()), "only GRPC ApiConfigSource type supported");
    checkArgument(apiConfigSource.getGrpcServicesCount() == 1, "expecting exactly 1 GrpcService in ApiConfigSource");
    GrpcService grpcService = apiConfigSource.getGrpcServices(0);
    checkArgument(grpcService.hasGoogleGrpc() && !grpcService.hasEnvoyGrpc(), "only GoogleGrpc expected in GrpcService");
    GoogleGrpc googleGrpc = grpcService.getGoogleGrpc();
    // for now don't support any credentials
    checkArgument(!googleGrpc.hasChannelCredentials() && googleGrpc.getCallCredentialsCount() == 0 &&
        Strings.isNullOrEmpty(googleGrpc.getCredentialsFactoryName()), "No credentials supported in GoogleGrpc");
    String targetUri = googleGrpc.getTargetUri();
    checkArgument(!Strings.isNullOrEmpty(targetUri), "targetUri in GoogleGrpc is empty!");
    checkArgument(targetUri.startsWith("unix:"), "targetUri should contain UDS path starting with 'unix:'");
    udsTarget = targetUri.substring(5);
  }

  private static SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub buildBidiStub(
      String udsTarget) {

    EventLoopGroup elg = new EpollEventLoopGroup();

    ManagedChannel channel = NettyChannelBuilder
        .forAddress(new DomainSocketAddress(udsTarget))
        .eventLoopGroup(elg)
        .channelType(EpollDomainSocketChannel.class)
        .build();
    SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub bidiStub =
        SecretDiscoveryServiceGrpc.newStub(channel);
    return bidiStub;
  }

  private static DiscoveryRequest getDiscoveryRequest(String nonce, String versionInfo) {
    logger.info("Creating Discovery req (resources, version, response_nonce):" +
        "(foo,bar,boom,gad), " + versionInfo + " , " +
        nonce);
    return DiscoveryRequest.newBuilder()
        .addResourceNames("foo")
        .addResourceNames("bar")
        .addResourceNames("boom")
        .addResourceNames("gad")
        .setTypeUrl(SECRET_TYPE_URL)
        .setResponseNonce(nonce)
        .setVersionInfo(versionInfo)
        .build();
  }

  /**
   *
   */
  static class ResponseObserver implements StreamObserver<DiscoveryResponse> {
    StreamObserver<DiscoveryRequest> requestStreamObserver;
    DiscoveryResponse lastResponse;
    ScheduledExecutorService periodicScheduler;
    boolean completed = false;

    ResponseObserver() {
      periodicScheduler = Executors.newSingleThreadScheduledExecutor();
      /*
      periodicScheduler.scheduleAtFixedRate(new Runnable() {
        @Override
        public void run() {
          // generate a request
          try {
            DiscoveryRequest req =
                getDiscoveryRequest(lastResponse != null ? lastResponse.getNonce() : "",
                    lastResponse != null ? lastResponse.getVersionInfo() : "");
            requestStreamObserver.onNext(req);
          } catch (Throwable t) {
            logger.log(Level.SEVERE, "periodic req", t);
          }
        }
      }, 2L, 105L, TimeUnit.SECONDS);
       */
    }

    @Override
    public void onNext(DiscoveryResponse discoveryResponse) {
      logger.info("Received DiscoveryResponse in onNext()");
      lastResponse = discoveryResponse;
    }

    @Override
    public void onError(Throwable t) {
      logger.log(Level.SEVERE, "onError", t);
    }

    @Override
    public void onCompleted() {
      synchronized (this) {
        completed = true;
        this.notifyAll();
      }
    }
  }

}
