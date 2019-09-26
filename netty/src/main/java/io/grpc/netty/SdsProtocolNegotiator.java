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

final class SdsProtocolNegotiator implements ProtocolNegotiator {
  final SdsProtocolNegotiatorFactory.Cfg cfg;

  // private final ObjectPool<SdsClient> sdsClientPool;
  // private SdsClient sdsClient;
  public SdsProtocolNegotiator(
      /*ObjectPool<SdsClient> sdsClientPool*/ SdsProtocolNegotiatorFactory.Cfg cfg) {
    // this.sdsClientPool = sdsClientPool;
    // this.sdsClient = sdsClientPool.getObject();
    this.cfg = cfg;
    System.out.println("from SdsProtocolNegotiator ctor");
  }

  @Override
  public void close() {
    // sdsClient = sdsClientPool.returnObject(sdsClient);
    System.out.println("from SdsProtocolNegotiator close");
  }

  @Override
  public ChannelHandler newHandler(GrpcHttp2ConnectionHandler grpcHandler) {
    return new SdsHandler(/*sdsClient,*/ grpcHandler, cfg);
  }

  @Override
  public AsciiString scheme() {
    return Utils.HTTPS;
  }
}
