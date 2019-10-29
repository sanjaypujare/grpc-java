package io.grpc.xds.sds;

import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.netty.handler.ssl.SslContext;
import java.util.concurrent.Executor;

/**
 * An SslContext provider that uses SDS to get the necessary secrets to build an SslContext.
 * Used for both server and client SslContexts
 */
public class SdsSslContextProvider implements SecretProvider<SslContext> {



  @Override
  public void addCallback(Callback<SslContext> callback, Executor executor) {

  }

  /**
   * Once the interface defines it this will override it
   */
  public void close() {

  }
}
