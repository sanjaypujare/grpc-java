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

import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.api.v2.Listener;
import io.envoyproxy.envoy.api.v2.listener.FilterChain;
import io.envoyproxy.envoy.config.core.v3.Address;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

/**
 * Unit tests to parse V2 resources as V3.
 */
@RunWith(JUnit4.class)
public class XdsV2V3Test {

  @Test
  public void createV2Resource_parseAsV3() {
    final FilterChain filterChainOutbound = XdsClientImplTestForListener.buildFilterChain(
            XdsClientImplTestForListener.buildFilterChainMatch(8000), null);
    Listener v2Listener = XdsClientImplTestForListener.buildListenerWithFilterChain("INBOUND_LISTENER", 15001, "0.0.0.0",
            filterChainOutbound);

    try {
      io.envoyproxy.envoy.config.listener.v3.Listener v3Listener =
              io.envoyproxy.envoy.config.listener.v3.Listener.parseFrom(v2Listener.toByteArray());
      assertThat(v3Listener.getName()).isEqualTo("INBOUND_LISTENER");
      Address v3Address = v3Listener.getAddress();
      List<io.envoyproxy.envoy.config.listener.v3.FilterChain> filterChainList = v3Listener.getFilterChainsList();
      assertThat(filterChainList.size()).isEqualTo(1);
    } catch (InvalidProtocolBufferException e) {
      fail(e.getMessage());
    }
  }
}
