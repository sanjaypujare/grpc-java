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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

final class SdsHandler extends ChannelHandlerAdapter {
  // private final SdsClient sdsClient;
  private final GrpcHttp2ConnectionHandler grpcHandler;

  public SdsHandler(/*SdsClient sdsClient,*/ GrpcHttp2ConnectionHandler grpcHandler) {
    // this.sdsClient = sdsClient;
    this.grpcHandler = grpcHandler;
  }

  static interface Cfg {
    TrustManagerFactory getTrustManagerFactory();

    KeyManagerFactory getKeyManagerFactory();
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

    ListenableFuture<Cfg> future = null; // sdsClient.getConfigAsync(sdsConfig);
    Futures.addCallback(
        future,
        new FutureCallback<Cfg>() {
          @Override
          public void onFailure(Throwable t) {
            ctx.fireExceptionCaught(t); // TODO: probably want to convert to a Status
          }

          @Override
          public void onSuccess(Cfg cfg) {
            SslContext sslContext = null;
            try {
              sslContext =
                  GrpcSslContexts.forClient()
                      .trustManager(cfg.getTrustManagerFactory())
                      .keyManager(cfg.getKeyManagerFactory())
                      .build();
            } catch (SSLException e) {
              throw new RuntimeException(e);
            }
            ChannelHandler handler =
                InternalProtocolNegotiators.tls(sslContext).newHandler(grpcHandler);
            // Delegate rest of handshake to TLS handler
            ctx.pipeline().replace(SdsHandler.this, null, handler);
          }
        },
        ctx.executor());
  }
}
