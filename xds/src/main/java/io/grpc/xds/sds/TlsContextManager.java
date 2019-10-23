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

package io.grpc.xds.sds;

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.Internal;
import io.netty.handler.ssl.SslContext;
import io.grpc.xds.sds.ReferenceCountingMap.ResourceDefinition;

/**
 * Class to manage secrets used to create SSL contexts - this effectively manages SSL contexts
 * (aka TlsContexts) based on inputs we get from xDS. This is used by gRPC-xds to access the
 * SSL contexts/secrets and is not public API.
 * Currently it just creates a new SecretProvider for each call.
 */
@Internal
public final class TlsContextManager {

  private static TlsContextManager instance;

  final private ReferenceCountingMap<Object, SecretProvider<Object, SslContext>> referenceCountingMap;

  private TlsContextManager() {
    referenceCountingMap = new ReferenceCountingMap<>();
  }

  /*
  @VisibleForTesting
  static synchronized void clearInstance() {
    instance = null;
  } */

  /** Gets the ContextManager singleton. */
  public static synchronized TlsContextManager getInstance() {
    if (instance == null) {
      instance = new TlsContextManager();
    }
    return instance;
  }

  private SecretProvider<Object, SslContext> getSecretProviderFromResource(ReferenceCountingMap.ResourceDefinition<Object, SecretProvider<Object, SslContext>> resource) {
    SecretProvider<Object, SslContext> retVal = referenceCountingMap.get(resource);
    retVal.setSharedResourcePool(referenceCountingMap, resource.getKey());
    return retVal;
  }

  /** Creates a SecretProvider. Used for retrieving a server-side SslContext. */
  public SecretProvider<Object, SslContext> findOrCreateServerSslContextProvider(
      DownstreamTlsContext downstreamTlsContext) {
    return getSecretProviderFromResource(new ServerSecretProviderResource(downstreamTlsContext));
  }

  /** Creates a SecretProvider. Used for retrieving a client-side SslContext. */
  public SecretProvider<Object, SslContext> findOrCreateClientSslContextProvider(
      UpstreamTlsContext upstreamTlsContext) {
    return getSecretProviderFromResource(new ClientSecretProviderResource(upstreamTlsContext));
  }

  private static class ServerSecretProviderResource implements ResourceDefinition<Object, SecretProvider<Object, SslContext>> {

    private DownstreamTlsContext downstreamTlsContext;

    public ServerSecretProviderResource(DownstreamTlsContext downstreamTlsContext) {
      this.downstreamTlsContext = downstreamTlsContext;
    }

    @Override
    public SecretProvider<Object, SslContext> create() {
      return SslContextSecretVolumeSecretProvider.getProviderForServer(downstreamTlsContext);
    }

    @Override
    public Object getKey() {
      return downstreamTlsContext;
    }
  }

  private static class ClientSecretProviderResource implements ResourceDefinition<Object, SecretProvider<Object, SslContext>> {

    private UpstreamTlsContext upstreamTlsContext;

    public ClientSecretProviderResource(UpstreamTlsContext upstreamTlsContext) {
      this.upstreamTlsContext = upstreamTlsContext;
    }

    @Override
    public SecretProvider<Object, SslContext> create() {
      return SslContextSecretVolumeSecretProvider.getProviderForClient(upstreamTlsContext);
    }

    @Override
    public Object getKey() {
      return upstreamTlsContext;
    }
  }
}
