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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
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
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

/**
 * A gRPC {@link ProtocolNegotiator} used to handle SSL when certs/keys are provided by SDS. This
 * class creates a Netty handler similar to Netty's {@code SslHandler}.
 */
public final class SdsProtocolNegotiators {

  @SuppressWarnings("unused")
  private static final Logger logger = Logger.getLogger(SdsProtocolNegotiators.class.getName());

  private static final AsciiString SCHEME = AsciiString.of("https");

  /**
   * Sets the {@link ProtocolNegotiatorFactory} to be used. Overrides any specified negotiation type
   * and {@code SslContext}.
   */
  public static void setProtocolNegotiatorFactory(
      NettyChannelBuilder builder, ClientSdsProtocolNegotiatorFactory protocolNegotiator) {
    InternalNettyChannelBuilder.setProtocolNegotiatorFactory(builder, protocolNegotiator);
  }

  /**
   * Creates a protocol negotiator for SDS on the server side.
   * TODO: do the necessary stuff here
   */
  public static ProtocolNegotiator serverSdsProtocolNegotiator(Cfg cfg) {

    return new ServerSdsProtocolNegotiator(cfg);
  }

  public static class CfgStreams {
    public InputStream keyCertChain;
    public InputStream key;
    public InputStream trustChain;
    public Exception exception;
  }

  /**
   * for now we use this to simulate the xDS info such as SdsSecretConfig.
   */
  public static class Cfg {
    static ListeningExecutorService executorService =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(4));

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

    /**
     * returns a ListenableFuture to compute all 3.
     */
    public ListenableFuture<CfgStreams> getAll3() {
      //final Throwable t = new Throwable();
      return executorService.submit(new Callable<CfgStreams>() {
        @Override
        public CfgStreams call() {
          logger.info("Cfg.getAll3 inside run()");
          final CfgStreams cfgStreams = new CfgStreams();
          //t.printStackTrace();
          try {
            cfgStreams.key = getKeyInputStream();
          } catch (FileNotFoundException e) {
            cfgStreams.exception = e;
            logger.log(Level.SEVERE, "getKeyInputStream", e);
          }
          try {
            cfgStreams.keyCertChain = getKeyCertChainInputStream();
          } catch (FileNotFoundException e) {
            cfgStreams.exception = e;
            logger.log(Level.SEVERE, "getKeyCertChainInputStream", e);
          }
          try {
            cfgStreams.trustChain = getTrustChainInputStream();
          } catch (FileNotFoundException e) {
            cfgStreams.exception = e;
            logger.log(Level.SEVERE, "getTrustChainInputStream", e);
          }
          return cfgStreams;
        }
      });
    }
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
      // SSLHandshakeException:
      // error:10000410:SSL routines:OPENSSL_internal:SSLV3_ALERT_HANDSHAKE_FAILURE
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

  @SuppressWarnings("unused")
  private static final class ServerSdsHandler extends InternalProtocolNegotiators.ProtocolNegotiationHandler {

    // private final SdsClient sdsClient;
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final Cfg cfg;

    ServerSdsHandler(
        /*SdsClient sdsClient,*/ GrpcHttp2ConnectionHandler grpcHandler,
        Cfg cfg) {
      super(new ChannelHandlerAdapter() {
      });
      // this.sdsClient = sdsClient;
      this.grpcHandler = grpcHandler;
      this.cfg = cfg;
    }

    private static class BufferReadsHandler extends ChannelInboundHandlerAdapter {
      private final List<Object> reads = new ArrayList<>();
      private boolean readComplete;

      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) {
        reads.add(msg);
      }

      @Override
      public void channelReadComplete(ChannelHandlerContext ctx) {
        readComplete = true;
      }

      @Override
      public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        for (Object msg : reads) {
          super.channelRead(ctx, msg);
        }
        if (readComplete) {
          super.channelReadComplete(ctx);
        }
      }
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {

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
      System.out.println("from ServerSdsHandler.handlerAdded:"
          + " passing new key & trust managers to a new SslContext");
      System.out.println(" last modified of keyFile:"
          + cfg.key + " is " + new Date(new File(cfg.key).lastModified()));
      ctx.pipeline().addBefore(ctx.name(), null, new LoggingHandler(LogLevel.WARN){
        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) throws Exception {
          //new Throwable("mine").printStackTrace();
          super.close(ctx, promise);
        }
      });

      boolean blocking = false;

      if (blocking) {
        SslContext sslContext = null;
        try {
                  /*sslContext =
                SslContextBuilder.forServer()
*/
          sslContext =
              GrpcSslContexts.forServer(cfg.getKeyCertChainInputStream(), cfg.getKeyInputStream())
                  .trustManager(new SdsTrustManagerFactory(cfg.getTrustChainInputStream()))
                  .build();
        } catch (SSLException | FileNotFoundException e) {
          throw new RuntimeException(e);
        }
        ChannelHandler handler = InternalProtocolNegotiators
            .serverTls(sslContext)
            .newHandler(grpcHandler);
        // Delegate rest of handshake to TLS handler
        ctx.pipeline().replace(ServerSdsHandler.this, null, handler);
      } else {
        // from /google3/java/com/google/net/grpc/loas/ServerAuthorizationHandler.java
        // we need to buffer the input until we have successfully finished the callback
        // to set the SSl context
        final BufferReadsHandler bufferReads = new BufferReadsHandler();
        ctx.pipeline().addBefore(ctx.name(), null, bufferReads);

        ListenableFuture<CfgStreams> future = cfg.getAll3();
        Futures.addCallback(future, new FutureCallback<CfgStreams>() {

          @Override
          public void onSuccess(@Nullable CfgStreams cfgStreams) {
            logger.info("onSuccess inside ServerSdsHandler.handlerAdded called:"
                    + cfgStreams.keyCertChain + " : " + cfgStreams.key + " : " + cfgStreams.trustChain);
            SslContext sslContext;
            try {
              sslContext = GrpcSslContexts.forServer(cfgStreams.keyCertChain, cfgStreams.key)
                  .trustManager(new SdsTrustManagerFactory(cfgStreams.trustChain))
                  .build();
            } catch (SSLException e) {
              ctx.fireExceptionCaught(e); // TODO: probably want to convert to a Status
              logger.log(Level.SEVERE, "SSLException received:", e);
              return;
            }
            ChannelHandler handler = InternalProtocolNegotiators
                .serverTls(sslContext)
                .newHandler(grpcHandler);
            // Delegate rest of handshake to TLS handler
            //ctx.pipeline().replace(ServerSdsHandler.this, null, handler);
            //logger.info("calling addAfter");
            ctx.pipeline().addAfter(ctx.name(), null, handler);
            // we need to now remove bufferReadHandler,again from ServerAuthorizationHandler.java
            //logger.info("calling fireProtocolNegotiationEvent");
            fireProtocolNegotiationEvent(ctx);
            ctx.pipeline().remove(bufferReads);
          }

          @Override
          public void onFailure(Throwable t) {
            logger.log(Level.SEVERE, "inside onFailure: ", t);
            ctx.fireExceptionCaught(t); // TODO: probably want to convert to a Status
          }
        }, ctx.executor());
      }
    }
  }

  private static final class ServerSdsProtocolNegotiator implements ProtocolNegotiator {

    final Cfg cfg;

    public ServerSdsProtocolNegotiator(Cfg cfg) {
      this.cfg = cfg;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      System.out.println("from ServerSdsProtocolNegotiator newHandler 9/30");
      return new ServerSdsHandler(/*sdsClient,*/ grpcHandler, cfg);
    }

    @Override
    public void close() {
      // sdsClient = sdsClientPool.returnObject(sdsClient);
      System.out.println("from ServerSdsProtocolNegotiator close 9/30");
    }
  }
}
