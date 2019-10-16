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

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.Internal;
import io.grpc.netty.GrpcHttp2ConnectionHandler;
import io.grpc.netty.InternalNettyChannelBuilder;
import io.grpc.netty.InternalNettyChannelBuilder.ProtocolNegotiatorFactory;
import io.grpc.netty.InternalProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiator.ProtocolNegotiator;
import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.xds.XdsAttributes;
import io.grpc.xds.sds.SecretProvider;
import io.grpc.xds.sds.TlsContextManager;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides client and server side gRPC {@link ProtocolNegotiator}s that use SDS to provide the SSL
 * context.
 */
@Internal
public final class SdsProtocolNegotiators {

  private static final Logger logger = Logger.getLogger(SdsProtocolNegotiators.class.getName());

  private static final AsciiString SCHEME = AsciiString.of("https");

  private static final class ClientSdsProtocolNegotiatorFactory
      implements InternalNettyChannelBuilder.ProtocolNegotiatorFactory {

    // temporary until we have CDS implemented
    UpstreamTlsContext tempTlsContext;

    ClientSdsProtocolNegotiatorFactory() {
      tempTlsContext = null;
    }

    ClientSdsProtocolNegotiatorFactory(UpstreamTlsContext upstreamTlsContext) {
      this.tempTlsContext = upstreamTlsContext;
    }

    @Override
    public InternalProtocolNegotiator.ProtocolNegotiator buildProtocolNegotiator() {
      final ClientSdsProtocolNegotiator negotiator = new ClientSdsProtocolNegotiator(tempTlsContext);
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

    // temporary until CDS implemented
    UpstreamTlsContext tempTlsContext;

    ClientSdsProtocolNegotiator(UpstreamTlsContext upstreamTlsContext) {
      this.tempTlsContext = upstreamTlsContext;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      UpstreamTlsContext upstreamTlsContext = tempTlsContext;

         // the following will be uncommented once CDS implemented
         // grpcHandler.getEagAttributes().get(XdsAttributes.ATTR_UPSTREAM_TLS_CONTEXT);
      if (tlsContextIsEmpty(upstreamTlsContext)) {
        return InternalProtocolNegotiators.plaintext().newHandler(grpcHandler);
      }
      return new ClientSdsHandler(grpcHandler, upstreamTlsContext);
    }

    private static boolean tlsContextIsEmpty(UpstreamTlsContext upstreamTlsContext) {
      // TODO(sanjaypujare): check upstreamTlsContext even if non-null
      return upstreamTlsContext == null;
    }

    @Override
    public void close() {}
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

  private static final class ClientSdsHandler
      extends InternalProtocolNegotiators.ProtocolNegotiationHandler {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final UpstreamTlsContext upstreamTlsContext;

    ClientSdsHandler(
        GrpcHttp2ConnectionHandler grpcHandler,
        UpstreamTlsContext upstreamTlsContext) {
      super(new ChannelHandlerAdapter() {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
          ctx.pipeline().remove(this);
        }
      });
      this.grpcHandler = grpcHandler;
      this.upstreamTlsContext = upstreamTlsContext;
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {
      final BufferReadsHandler bufferReads = new BufferReadsHandler();
      ctx.pipeline().addBefore(ctx.name(), null, bufferReads);

      SecretProvider<SslContext> sslContextProvider =
          TlsContextManager.getInstance().findOrCreateClientSslContextProvider(upstreamTlsContext);

      sslContextProvider.addCallback(new SecretProvider.Callback<SslContext>() {

        @Override
        public void updateSecret(SslContext sslContext) {
          ChannelHandler handler =
              InternalProtocolNegotiators.tls(sslContext).newHandler(grpcHandler);
          // Delegate rest of handshake to TLS handler
          ctx.pipeline().replace(ClientSdsHandler.this, null, handler);
        }

        @Override
        public void onException(Throwable throwable) {
          logger.log(Level.SEVERE, "onException", throwable);
          ctx.fireExceptionCaught(throwable);
        }
      }, ctx.executor());
    }
  }

  private static final class ServerSdsProtocolNegotiator implements ProtocolNegotiator {

    // this is temporary until we have plumbing implemented with LDS
    private DownstreamTlsContext tempDownstreamTlsContext;

    ServerSdsProtocolNegotiator(DownstreamTlsContext downstreamTlsContext) {
      this.tempDownstreamTlsContext = downstreamTlsContext;
    }

    ServerSdsProtocolNegotiator() {
      this.tempDownstreamTlsContext = null;
    }

    @Override
    public AsciiString scheme() {
      return SCHEME;
    }

    @Override
    public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
      DownstreamTlsContext downstreamTlsContext = tempDownstreamTlsContext;

          // following will be uncommented once we have LDS plumbing implemented
          //grpcHandler.getEagAttributes().get(XdsAttributes.ATTR_DOWNSTREAM_TLS_CONTEXT);

      if (tlsContextIsEmpty(downstreamTlsContext)) {
        return InternalProtocolNegotiators.serverPlaintext().newHandler(grpcHandler);
      }

      return new ServerSdsHandler(grpcHandler, downstreamTlsContext);
    }

    private static boolean tlsContextIsEmpty(DownstreamTlsContext downstreamTlsContext) {
      // TODO(sanjaypujare): check downstreamTlsContext even if non-null
      return downstreamTlsContext == null;
    }

    @Override
    public void close() {}
  }

  private static final class ServerSdsHandler
      extends InternalProtocolNegotiators.ProtocolNegotiationHandler {
    private final GrpcHttp2ConnectionHandler grpcHandler;
    private final DownstreamTlsContext downstreamTlsContext;

    ServerSdsHandler(
        GrpcHttp2ConnectionHandler grpcHandler,
        DownstreamTlsContext downstreamTlsContext) {
      super(new ChannelHandlerAdapter() {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
          ctx.pipeline().remove(this);
        }
      });
      // this.sdsClient = sdsClient;
      this.grpcHandler = grpcHandler;
      this.downstreamTlsContext = downstreamTlsContext;
    }

    @Override
    protected void handlerAdded0(final ChannelHandlerContext ctx) {
      final BufferReadsHandler bufferReads = new BufferReadsHandler();
      ctx.pipeline().addBefore(ctx.name(), null, bufferReads);

      SecretProvider<SslContext> sslContextProvider =
          TlsContextManager
              .getInstance().findOrCreateServerSslContextProvider(downstreamTlsContext);

      sslContextProvider.addCallback(new SecretProvider.Callback<SslContext>() {

        @Override
        public void updateSecret(SslContext sslContext) {
          ChannelHandler handler = InternalProtocolNegotiators
              .serverTls(sslContext)
              .newHandler(grpcHandler);
          // Delegate rest of handshake to TLS handler

          ctx.pipeline().addAfter(ctx.name(), null, handler);
          fireProtocolNegotiationEvent(ctx);
          ctx.pipeline().remove(bufferReads);
        }

        @Override
        public void onException(Throwable throwable) {
          logger.log(Level.SEVERE, "onException", throwable);
          ctx.fireExceptionCaught(throwable);
        }
      }, ctx.executor());
    }
  }

  /** Sets the {@link ProtocolNegotiatorFactory} on a NettyChannelBuilder. */
  public static void setProtocolNegotiatorFactory(NettyChannelBuilder builder) {
    InternalNettyChannelBuilder.setProtocolNegotiatorFactory(
        builder, new ClientSdsProtocolNegotiatorFactory());
  }

  // temporary...
  public static void setProtocolNegotiatorFactory(NettyChannelBuilder builder,
                                                  UpstreamTlsContext tempTlsContext) {
    InternalNettyChannelBuilder.setProtocolNegotiatorFactory(
            builder, new ClientSdsProtocolNegotiatorFactory(tempTlsContext));
  }

  /** Creates an SDS based {@link ProtocolNegotiator} for a server. */
  public static ProtocolNegotiator serverProtocolNegotiator() {
    return new ServerSdsProtocolNegotiator();
  }

  /** TODO: temporary one until LDS implemented */
  public static ProtocolNegotiator serverProtocolNegotiator(DownstreamTlsContext downstreamTlsContext) {
    return new ServerSdsProtocolNegotiator(downstreamTlsContext);
  }
}
