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

package io.grpc.xds.sds.internal;

import com.google.common.annotations.VisibleForTesting;
import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.SdsSecretConfig;
import io.envoyproxy.envoy.api.v2.auth.TlsCertificate;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.xds.XdsAttributes;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AsciiString;
import java.util.logging.Logger;

/**
 * A gRPC {@link ProtocolNegotiator} that uses certs/keys provided by SDS. This
 * class creates a Netty handler similar to Netty's {@code SslHandler}.
 */
public class SdsProtocolNegotiators {

  private static final Logger logger = Logger.getLogger(SdsProtocolNegotiators.class.getName());

  private static final AsciiString SCHEME = AsciiString.of("https");

  /**
   * ClientSdsProtocolNegotiatorFactory is a factory for doing client side negotiation.
   */
  public static final class ClientSdsProtocolNegotiatorFactory
      implements InternalNettyChannelBuilder.ProtocolNegotiatorFactory {

    @Override
    public ProtocolNegotiator buildProtocolNegotiator() {
      return null;
    }
  }

  private static final class ClientSdsProtocolNegotiator implements ProtocolNegotiator {

    ClientSdsProtocolNegotiator() {

    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      return null;
    }

    @Override
    public void close() {

    }
  }

  @VisibleForTesting
  static final class ClientSdsHandler extends InternalProtocolNegotiators.ProtocolNegotiationHandler {
    private final GrpcHttp2ConnectionHandler grpcHandler;

    ClientSdsHandler(
        GrpcHttp2ConnectionHandler grpcHandler) {
      super(new ChannelHandlerAdapter() {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
          ctx.pipeline().remove(this);
        }
      });
      this.grpcHandler = grpcHandler;
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {
      SdsSecretConfig sdsSecretConfig =
          grpcHandler.getEagAttributes().get(XdsAttributes.ATTR_SDS_CONFIG);
      TlsCertificate tlsCertificate =
          grpcHandler.getEagAttributes().get(XdsAttributes.ATTR_TLS_CERTIFICATE);
      CertificateValidationContext certContext =
          grpcHandler.getEagAttributes().get(XdsAttributes.ATTR_CERT_VALIDATION_CONTEXT);



    }
  }
}
