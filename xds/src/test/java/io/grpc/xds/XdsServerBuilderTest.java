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

package io.grpc.xds;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.grpc.netty.InternalProtocolNegotiators;
import io.grpc.xds.internal.sds.SdsProtocolNegotiators.ServerSdsProtocolNegotiator;
import io.grpc.xds.internal.sds.ServerWrapperForXds;
import io.grpc.xds.internal.sds.XdsServerBuilder;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link XdsServerBuilder}.
 */
@RunWith(JUnit4.class)
public class XdsServerBuilderTest {

  @Test
  public void xdsServer_callsShutdown() throws IOException, InterruptedException {
    int port = findFreePort();
    XdsServerBuilder builder = XdsServerBuilder.forPort(port);
    XdsClient mockXdsClient = mock(XdsClient.class);
    XdsClientWrapperForServerSds xdsClientWrapperForServerSds =
        new XdsClientWrapperForServerSds(port);
    xdsClientWrapperForServerSds.start(mockXdsClient, null);
    ServerSdsProtocolNegotiator serverSdsProtocolNegotiator =
        new ServerSdsProtocolNegotiator(xdsClientWrapperForServerSds,
            InternalProtocolNegotiators.serverPlaintext());
    ServerWrapperForXds xdsServer = builder.buildServer(serverSdsProtocolNegotiator);
    xdsServer.start();
    xdsServer.shutdown();
    xdsServer.awaitTermination(500L, TimeUnit.MILLISECONDS);
    verify(mockXdsClient, times(1)).shutdown();
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      socket.setReuseAddress(true);
      return socket.getLocalPort();
    }
  }
}
