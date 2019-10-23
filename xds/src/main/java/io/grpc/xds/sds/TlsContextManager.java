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

import com.google.common.base.Objects;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.Internal;
import io.netty.handler.ssl.SslContext;
import java.util.logging.Logger;

/**
 * Class to manage secrets used to create SSL contexts - this effectively manages SSL contexts
 * (aka TlsContexts) based on inputs we get from xDS. This is used by gRPC-xds to access the
 * SSL contexts/secrets and is not public API.
 * Currently it just creates a new SecretProvider for each call.
 */
@Internal
public final class TlsContextManager {
  private static final Logger logger = Logger.getLogger(TlsContextManager.class.getName());

  private static TlsContextManager instance;

  final private ReferenceCountingMap<Object, SecretProvider<SslContext>> holder;

  private TlsContextManager() {
    holder = new ReferenceCountingMap<>();
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

  private SecretProvider<SslContext> getSecretProviderFromResource(ReferenceCountingMap.Resource<Object, SecretProvider<SslContext>> resource) {
    SecretProvider<SslContext> retVal = holder.get(resource);
    retVal.setSharedResourcePool(holder, resource);
    return retVal;
  }

  /** Creates a SecretProvider. Used for retrieving a server-side SslContext. */
  public SecretProvider<SslContext> findOrCreateServerSslContextProvider(
      DownstreamTlsContext downstreamTlsContext) {
    return getSecretProviderFromResource(new ServerSecretProviderResource(downstreamTlsContext));
  }

  /** Creates a SecretProvider. Used for retrieving a client-side SslContext. */
  public SecretProvider<SslContext> findOrCreateClientSslContextProvider(
      UpstreamTlsContext upstreamTlsContext) {
    return getSecretProviderFromResource(new ClientSecretProviderResource(upstreamTlsContext));
  }

  private static class ServerSecretProviderResource implements ReferenceCountingMap.Resource<Object, SecretProvider<SslContext>> {

    private DownstreamTlsContext downstreamTlsContext;

    public ServerSecretProviderResource(DownstreamTlsContext downstreamTlsContext) {
      this.downstreamTlsContext = downstreamTlsContext;
    }

    @Override
    public SecretProvider<SslContext> create() {
      return SslContextSecretVolumeSecretProvider.getProviderForServer(downstreamTlsContext);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ServerSecretProviderResource)) return false;
      ServerSecretProviderResource that = (ServerSecretProviderResource) o;
      return Objects.equal(downstreamTlsContext, that.downstreamTlsContext);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(downstreamTlsContext);
    }

    @Override
    public Object getKey() {
      return downstreamTlsContext;
    }
  }

  private static class ClientSecretProviderResource implements ReferenceCountingMap.Resource<Object, SecretProvider<SslContext>> {

    private UpstreamTlsContext upstreamTlsContext;

    public ClientSecretProviderResource(UpstreamTlsContext upstreamTlsContext) {
      this.upstreamTlsContext = upstreamTlsContext;
    }

    @Override
    public SecretProvider<SslContext> create() {
      return SslContextSecretVolumeSecretProvider.getProviderForClient(upstreamTlsContext);
    }

    @Override
    public Object getKey() {
      return upstreamTlsContext;
    }
  }
}
