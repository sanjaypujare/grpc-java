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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;

import io.grpc.xds.sds.ReferenceCountingSslContextProviderMap.SslContextProviderFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link ReferenceCountingSslContextProviderMap}. */
@RunWith(JUnit4.class)
public class ReferenceCountingSslContextProviderMapTest {

  @Test
  public void referenceCountingMap_getAndRelease_closeCalled() throws InterruptedException {
    SslContextProviderFactory<Integer> mockFactory
            = mock(SslContextProviderFactory.class);
    ReferenceCountingSslContextProviderMap<Integer> map =
        new ReferenceCountingSslContextProviderMap<>(mockFactory);

    SslContextProvider valueFor3 = mock(SslContextProvider.class);
    when(mockFactory.createSslContextProvider(3)).thenReturn(valueFor3);
    SslContextProvider val = map.get(3);
    assertThat(val).isSameInstanceAs(valueFor3);
    verify(valueFor3, never()).close();
    val = map.get(3);
    assertThat(val).isSameInstanceAs(valueFor3);
    // at this point ref-count is 2
    assertThat(map.release(3, val)).isNull();
    verify(valueFor3, never()).close();
    assertThat(map.release(3, val)).isNull(); // after this ref-count is 0
    verify(valueFor3, times(1)).close();
  }

  @Test
  public void referenceCountingMap_distinctElements() throws InterruptedException {
    SslContextProviderFactory<Integer> mockFactory
            = mock(SslContextProviderFactory.class);
    ReferenceCountingSslContextProviderMap<Integer> map =
            new ReferenceCountingSslContextProviderMap<>(mockFactory);

    SslContextProvider valueFor3 = mock(SslContextProvider.class);
    SslContextProvider valueFor4 = mock(SslContextProvider.class);
    when(mockFactory.createSslContextProvider(3)).thenReturn(valueFor3);
    when(mockFactory.createSslContextProvider(4)).thenReturn(valueFor4);
    SslContextProvider val3 = map.get(3);
    assertThat(val3).isSameInstanceAs(valueFor3);
    SslContextProvider val4 = map.get(4);
    assertThat(val4).isSameInstanceAs(valueFor4);
    assertThat(map.release(3, val3)).isNull();
    verify(valueFor3, times(1)).close();
    verify(valueFor4, never()).close();
    assertThat(map.release(4, val4)).isNull();
    verify(valueFor4, times(1)).close();
  }

  @Test
  public void referenceCountingMap_excessRelease_expectException() throws InterruptedException {
    SslContextProviderFactory<Integer> mockFactory
            = mock(SslContextProviderFactory.class);
    ReferenceCountingSslContextProviderMap<Integer> map =
            new ReferenceCountingSslContextProviderMap<>(mockFactory);

    SslContextProvider valueFor4 = mock(SslContextProvider.class);
    when(mockFactory.createSslContextProvider(4)).thenReturn(valueFor4);
    SslContextProvider val = map.get(4);
    assertThat(val).isSameInstanceAs(valueFor4);
    // at this point ref-count is 1
    map.release(4, val);
    // at this point ref-count is 0
    try {
      map.release(4, val);
      fail("exception expected");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessageThat().contains("No cached instance found for 4");
    }
  }

  @Test
  public void referenceCountingMap_releaseAndGet_differentInstance()
      throws InterruptedException {
    SslContextProviderFactory<Integer> mockFactory
            = mock(SslContextProviderFactory.class);
    ReferenceCountingSslContextProviderMap<Integer> map =
            new ReferenceCountingSslContextProviderMap<>(mockFactory);

    SslContextProvider valueFor4 = mock(SslContextProvider.class);
    when(mockFactory.createSslContextProvider(4)).thenReturn(valueFor4);
    SslContextProvider val = map.get(4);
    assertThat(val).isSameInstanceAs(valueFor4);
    // at this point ref-count is 1
    map.release(4, val);
    // at this point ref-count is 0
    // another instance for 4
    SslContextProvider valueFor4a = mock(SslContextProvider.class);
    assertThat(valueFor4).isNotSameInstanceAs(valueFor4a);
    when(mockFactory.createSslContextProvider(4)).thenReturn(valueFor4a);
    val = map.get(4);
    assertThat(val).isSameInstanceAs(valueFor4a);
  }
}
