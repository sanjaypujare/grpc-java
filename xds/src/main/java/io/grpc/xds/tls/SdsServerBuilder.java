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

import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.Internal;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.ServerStreamTracer;
import io.grpc.ServerTransportFilter;
import io.grpc.netty.NettyServerBuilder;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.annotation.Nullable;

/**
 * gRPC secure server builder used for xDS-controlled mTLS.
 */
@Internal
public final class SdsServerBuilder extends ServerBuilder<SdsServerBuilder> {

  @SuppressWarnings("unused")
  private static final Logger logger = Logger.getLogger(SdsServerBuilder.class.getName());
  private final NettyServerBuilder delegate;

  private SdsServerBuilder(NettyServerBuilder nettyDelegate) {
    this.delegate = nettyDelegate;
  }

  /**
   * Creates a gRPC server builder for the given port.
   */
  public static SdsServerBuilder forPort(int port) {
    NettyServerBuilder nettyDelegate = NettyServerBuilder.forAddress(new InetSocketAddress(port));
    return new SdsServerBuilder(nettyDelegate);
  }

  @Override
  public SdsServerBuilder handshakeTimeout(long timeout, TimeUnit unit) {
    delegate.handshakeTimeout(timeout, unit);
    return this;
  }

  @Override
  public SdsServerBuilder directExecutor() {
    delegate.directExecutor();
    return this;
  }

  @Override
  public SdsServerBuilder addStreamTracerFactory(ServerStreamTracer.Factory factory) {
    delegate.addStreamTracerFactory(factory);
    return this;
  }

  @Override
  public SdsServerBuilder addTransportFilter(ServerTransportFilter filter) {
    delegate.addTransportFilter(filter);
    return this;
  }

  @Override
  public SdsServerBuilder executor(Executor executor) {
    delegate.executor(executor);
    return this;
  }

  @Override
  public SdsServerBuilder addService(ServerServiceDefinition service) {
    delegate.addService(service);
    return this;
  }

  @Override
  public SdsServerBuilder addService(BindableService bindableService) {
    delegate.addService(bindableService);
    return this;
  }

  @Override
  public SdsServerBuilder fallbackHandlerRegistry(@Nullable HandlerRegistry fallbackRegistry) {
    delegate.fallbackHandlerRegistry(fallbackRegistry);
    return this;
  }

  @Override
  public SdsServerBuilder useTransportSecurity(File certChain, File privateKey) {
    delegate.useTransportSecurity(certChain, privateKey);
    return this;
  }

  @Override
  public SdsServerBuilder decompressorRegistry(@Nullable DecompressorRegistry registry) {
    delegate.decompressorRegistry(registry);
    return this;
  }

  @Override
  public SdsServerBuilder compressorRegistry(@Nullable CompressorRegistry registry) {
    delegate.compressorRegistry(registry);
    return this;
  }

  @Override
  public SdsServerBuilder intercept(ServerInterceptor interceptor) {
    delegate.intercept(interceptor);
    return this;
  }

  @Override
  public Server build() {
    delegate.protocolNegotiator(
        SdsProtocolNegotiators.serverSdsProtocolNegotiator());
    return delegate.build();
  }
}
