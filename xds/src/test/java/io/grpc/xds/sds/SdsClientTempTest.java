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

import static com.google.common.truth.Truth.assertThat;

import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.envoyproxy.envoy.api.v2.core.ApiConfigSource;
import io.envoyproxy.envoy.api.v2.core.ConfigSource;
import io.envoyproxy.envoy.api.v2.core.GrpcService;
import io.netty.handler.ssl.SslContext;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link SdsClientTemp}. */
@RunWith(JUnit4.class)
public class SdsClientTempTest {


  @Test
  public void configSourceUdsTarget() {
    ConfigSource configSource = ConfigSource.newBuilder()
        .setApiConfigSource(ApiConfigSource.newBuilder()
                .setApiType(ApiConfigSource.ApiType.GRPC)
                .addGrpcServices(GrpcService.newBuilder()
                        .setGoogleGrpc(GrpcService.GoogleGrpc.newBuilder()
                                .setTargetUri("unix:/tmp/uds_path")
                                .build())
                        .build())
                .build())
        .build();
    SdsClientTemp sdsClientTemp = new SdsClientTemp(configSource);
    assertThat(sdsClientTemp.udsTarget).isEqualTo("/tmp/uds_path");
  }

}
