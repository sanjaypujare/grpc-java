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
import io.netty.util.AsciiString;

public final class SdsProtocolNegotiatorFactory
    implements InternalNettyChannelBuilder.ProtocolNegotiatorFactory {

  // inject SecretManager once ready

  public SdsProtocolNegotiatorFactory() {}

  @Override
  public InternalProtocolNegotiator.ProtocolNegotiator buildProtocolNegotiator() {
    final SdsProtocolNegotiator negotiator = new SdsProtocolNegotiator();
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
