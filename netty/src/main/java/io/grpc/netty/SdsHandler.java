/*
 * Copyright 2015 The gRPC Authors
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

package io.grpc.netty;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.SSLException;

final class SdsHandler extends ChannelHandlerAdapter {
  // private final SdsClient sdsClient;
  private final GrpcHttp2ConnectionHandler grpcHandler;
  SdsProtocolNegotiatorFactory.Cfg cfg;

  public SdsHandler(
      /*SdsClient sdsClient,*/ GrpcHttp2ConnectionHandler grpcHandler,
      SdsProtocolNegotiatorFactory.Cfg cfg) {
    // this.sdsClient = sdsClient;
    this.grpcHandler = grpcHandler;
    this.cfg = cfg;
  }

  @Override
  public void handlerAdded(final ChannelHandlerContext ctx) {
    /*SdsSecretConfig sdsConfig = grpcHandler.getEagAttributes().get(SDS_CONFIG_KEY);
    if (sdsConfig == null) {
        ctx.fireExceptionCaught(Status.UNAVAILABLE.withDescription(
        “No SDS info available; not using xDS balancer”).toException());
        return;
    } */
    // Cfg could just be SslContext; made it a separate object to show we can pass
    // whatever data is needed.

    SslContext sslContext = null;
    try {
      sslContext =
          GrpcSslContexts.forClient()
              .trustManager(cfg.getTrustChainInputStream())
              .keyManager(cfg.getKeyCertChainInputStream(), cfg.getKeyInputStream())
              .build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
    ChannelHandler handler = InternalProtocolNegotiators.tls(sslContext).newHandler(grpcHandler);
    // Delegate rest of handshake to TLS handler
    ctx.pipeline().replace(SdsHandler.this, null, handler);
  }
}
