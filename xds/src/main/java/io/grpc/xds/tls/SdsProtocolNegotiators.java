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

package io.grpc.xds.tls;

import io.grpc.Channel;
import io.grpc.internal.ObjectPool;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalNettyChannelBuilder.ProtocolNegotiatorFactory;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.NettyChannelBuilder;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AsciiString;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

/**
 * A gRPC {@link ProtocolNegotiator} used to handle SSL when certs/keys are
 * provided by SDS. This class creates a Netty handler similar to
 * Netty's {@code SslHandler}.
 */
public final class SdsProtocolNegotiators {
  @SuppressWarnings("unused")
  private static final Logger logger = Logger.getLogger(SdsProtocolNegotiators.class.getName());

  private static final AsciiString SCHEME = AsciiString.of("https");

  public static class Cfg {
    public String keyCertChain;
    public String key;
    public String trustChain;

    public InputStream getKeyCertChainInputStream() throws FileNotFoundException {
      return new FileInputStream(keyCertChain);
    }

    public InputStream getKeyInputStream() throws FileNotFoundException {
      return new FileInputStream(key);
    }

    public InputStream getTrustChainInputStream() throws FileNotFoundException {
      return new FileInputStream(trustChain);
    }
  }

  /**
   * Sets the {@link ProtocolNegotiatorFactory} to be used. Overrides any specified negotiation type
   * and {@code SslContext}.
   */
  public static void setProtocolNegotiatorFactory(
      NettyChannelBuilder builder, ClientSdsProtocolNegotiatorFactory protocolNegotiator) {
    InternalNettyChannelBuilder.setProtocolNegotiatorFactory(builder, protocolNegotiator);
  }

  /**
   * ClientSdsProtocolNegotiatorFactory is a factory for doing client side negotiation.
   */
  public static final class ClientSdsProtocolNegotiatorFactory
      implements InternalNettyChannelBuilder.ProtocolNegotiatorFactory {
    // inject SecretManager once ready

    Cfg cfg;

    public ClientSdsProtocolNegotiatorFactory(Cfg cfg) {
      this.cfg = cfg;
    }

    @Override
    public InternalProtocolNegotiator.ProtocolNegotiator buildProtocolNegotiator() {
      final ClientSdsProtocolNegotiator negotiator = new ClientSdsProtocolNegotiator(cfg);
      final class LocalSdsNegotiator implements InternalProtocolNegotiator.ProtocolNegotiator {

        @Override
        public AsciiString scheme() {
          return negotiator.scheme();
        }

        @Override
        public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
          return negotiator.newHandler(grpcHandler);
        }

        @Override
        public void close() {
          negotiator.close();
        }
      }

      return new LocalSdsNegotiator();
    }

  }

  private static final class ClientSdsProtocolNegotiator implements ProtocolNegotiator {
    final Cfg cfg;

    // private final ObjectPool<SdsClient> sdsClientPool;
    // private SdsClient sdsClient;
    public ClientSdsProtocolNegotiator(
        /*ObjectPool<SdsClient> sdsClientPool*/ Cfg cfg) {
      // this.sdsClientPool = sdsClientPool;
      // this.sdsClient = sdsClientPool.getObject();
      this.cfg = cfg;
      System.out.println("in ClientSdsProtocolNegotiator ctor:9/29");
    }

    @Override
    public void close() {
      // sdsClient = sdsClientPool.returnObject(sdsClient);
      System.out.println("from ClientSdsProtocolNegotiator close 9/29");
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      return new ClientSdsHandler(/*sdsClient,*/ grpcHandler, cfg);
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }
  }

  private static final class ClientSdsHandler extends ChannelHandlerAdapter {
    // private final SdsClient sdsClient;
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final Cfg cfg;

    ClientSdsHandler(
        /*SdsClient sdsClient,*/ GrpcHttp2ConnectionHandler grpcHandler,
        Cfg cfg) {
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

      // instead of using cfg.getTrustChainInputStream() to trustManager we use
      // SdsTrustManagerFactory
      // TODO: debug the issues with SdsKeyManagerFactory: it is not workign and is giving us
      // SSLHandshakeException: error:10000410:SSL routines:OPENSSL_internal:SSLV3_ALERT_HANDSHAKE_FAILURE
      SslContext sslContext = null;
      try {
        sslContext =
            GrpcSslContexts.forClient()
                .trustManager(new SdsTrustManagerFactory(cfg.getTrustChainInputStream()))
                .keyManager(cfg.getKeyCertChainInputStream(), cfg.getKeyInputStream())
                //.keyManager(new SdsKeyManagerFactory(cfg.key, cfg.keyCertChain))
                .build();
      } catch (SSLException | FileNotFoundException e) {
        throw new RuntimeException(e);
      }
      ChannelHandler handler = InternalProtocolNegotiators.tls(sslContext).newHandler(grpcHandler);
      // Delegate rest of handshake to TLS handler
      ctx.pipeline().replace(ClientSdsHandler.this, null, handler);
    }
  }

  /**
   * Creates a protocol negotiator for SDS on the server side.
   */
  public static ProtocolNegotiator serverSdsProtocolNegotiator() {


    return new ServerSdsProtocolNegotiator();
  }

  @SuppressWarnings("unused")
  private static final class ServerSdsHandler extends ChannelHandlerAdapter {
    // private final SdsClient sdsClient;
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final Cfg cfg;

    ServerSdsHandler(
            /*SdsClient sdsClient,*/ GrpcHttp2ConnectionHandler grpcHandler,
                                     Cfg cfg) {
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

      // instead of using cfg.getTrustChainInputStream() to trustManager we use
      // SdsTrustManagerFactory
      SslContext sslContext = null;
      try {
        /*sslContext =
                SslContextBuilder.forServer()
*/


        sslContext =
                GrpcSslContexts.forClient()
                        .trustManager(new SdsTrustManagerFactory(cfg.getTrustChainInputStream()))
                        .keyManager(cfg.getKeyCertChainInputStream(), cfg.getKeyInputStream())
                        .build();
      } catch (SSLException | FileNotFoundException e) {
        throw new RuntimeException(e);
      }
      ChannelHandler handler = InternalProtocolNegotiators.tls(sslContext).newHandler(grpcHandler);
      // Delegate rest of handshake to TLS handler
      ctx.pipeline().replace(ServerSdsHandler.this, null, handler);
    }
  }

  private static final class ServerSdsProtocolNegotiator implements ProtocolNegotiator {

    @Override
    public AsciiString scheme() {
      return null;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      return null;
    }

    @Override
    public void close() {

    }
  }
}
