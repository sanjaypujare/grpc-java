package io.grpc.xds.sds;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DummySdsClient {
    private static final Logger logger = Logger.getLogger(DummySdsClient.class.getName());

    // SecretTypeURL defines the type URL for Envoy secret proto.
    private static final String SECRET_TYPE_URL = "type.googleapis.com/envoy.api.v2.auth.Secret";

    private static SecretDiscoveryServiceGrpc.SecretDiscoveryServiceBlockingStub buildStub(
            int serverPort) {

        NettyChannelBuilder builder =
                NettyChannelBuilder.forTarget("localhost:" + serverPort)
                .usePlaintext();

        SecretDiscoveryServiceGrpc.SecretDiscoveryServiceBlockingStub blockingStub =
                SecretDiscoveryServiceGrpc.newBlockingStub(builder.build());
        return blockingStub;
    }

    private static SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub buildBidiStub(
            int serverPort) {

        NettyChannelBuilder builder =
                NettyChannelBuilder.forTarget("localhost:" + serverPort)
                        .usePlaintext();

        SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub bidiStub =
                SecretDiscoveryServiceGrpc.newStub(builder.build());
        return bidiStub;
    }

    public static void main(String[] args) throws InvalidProtocolBufferException, InterruptedException {
        if (true) {
            doStreaming();
        } else {
            doUnaryBlocking();
        }
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
        }

        @Override
        public void onNext(DiscoveryResponse discoveryResponse) {
            logger.info("Received DiscoveryResponse in onNext()");
            lastResponse = discoveryResponse;
            try {
                logDiscoveryResponse(discoveryResponse);
            } catch (InvalidProtocolBufferException e) {
                logger.log(Level.SEVERE, "from logDiscoveryResponse", e);
            }
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

    private static void doStreaming() throws InvalidProtocolBufferException, InterruptedException {
        SecretDiscoveryServiceGrpc.SecretDiscoveryServiceStub stub =
                buildBidiStub(8080);

        logger.info("Start doStreaming to authority: " + stub.getChannel().authority());
        ResponseObserver responseObserver =
            new ResponseObserver();
        StreamObserver<DiscoveryRequest> requestStreamObserver =
            stub.streamSecrets(responseObserver);
        responseObserver.requestStreamObserver = requestStreamObserver;
        synchronized (responseObserver) {
            while (!responseObserver.completed) {
                responseObserver.wait();
            }
        }
        logger.info("exiting from doStreaming");
    }

    private static void doUnaryBlocking() throws InvalidProtocolBufferException {
        SecretDiscoveryServiceGrpc.SecretDiscoveryServiceBlockingStub stub =
                buildStub(8080);
        logger.info("Start doUnaryBlocking to authority: " + stub.getChannel().authority());
        DiscoveryRequest request = getDiscoveryRequest("", "");
        DiscoveryResponse response =
            stub.fetchSecrets(request);
        logDiscoveryResponse(response);
    }

    private static void logDiscoveryResponse(DiscoveryResponse response) throws InvalidProtocolBufferException {
        StringBuffer sb = new StringBuffer();
        sb.append("Logging DiscoveryResponse(version, nonce): ").append(response.getVersionInfo())
            .append(" , ").append(response.getNonce()).append("\n");
        List<Any> resources = response.getResourcesList();
        for (Any any : resources) {
            String typeUrl = any.getTypeUrl();
            Secret secret = Secret.parseFrom(any.getValue());

            //sb.append("           typeUrl=" + typeUrl).append("\n")
            sb.append("           secret.name=" + secret.getName()).append("\n");
            TlsCertificate tlsCert = secret.getTlsCertificate();
            sb.append("               tlsCert.privateKey=" + tlsCert.getPrivateKey().getInlineBytes().toStringUtf8().substring(5, 40)).append("\n")
                .append("               tlsCert.certChain=" + tlsCert.getCertificateChain().getInlineBytes().toStringUtf8().substring(5, 40)).append("\n");
        }
        logger.info(sb.toString());
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

}
