package io.grpc.xds.sds;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.api.v2.DiscoveryRequest;
import io.envoyproxy.envoy.api.v2.DiscoveryResponse;
import io.envoyproxy.envoy.api.v2.auth.Secret;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.envoyproxy.envoy.service.discovery.v2.SecretDiscoveryServiceGrpc;
import io.grpc.netty.NettyChannelBuilder;

import java.util.List;
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

    public static void main(String[] args) throws InvalidProtocolBufferException {
        SecretDiscoveryServiceGrpc.SecretDiscoveryServiceBlockingStub stub =
                buildStub(8080);

        DiscoveryRequest request = DiscoveryRequest.newBuilder()
                .addResourceNames("foo")
                .setTypeUrl(SECRET_TYPE_URL)
                .build();
        DiscoveryResponse response =
            stub.fetchSecrets(request);

        logger.info("DiscoveryResponse = " + response);
        List<Any> resources = response.getResourcesList();
        for (Any any : resources) {
            String typeUrl = any.getTypeUrl();
            Secret secret = Secret.parseFrom(any.getValue());


            logger.info("typeUrl=" + typeUrl);
            logger.info("secret.name=" + secret.getName());
            TlsCertificate tlsCert = secret.getTlsCertificate();
            logger.info("tlsCert.privateKey=" + tlsCert.getPrivateKey().getInlineBytes().toStringUtf8());
            logger.info("tlsCert.certChain=" + tlsCert.getCertificateChain().getInlineBytes().toStringUtf8());
        }
    }

}
