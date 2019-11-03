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
   * Once the interface defines it this will override it.
   */
  public void close() {

  }
}
