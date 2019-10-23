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

import io.grpc.Internal;
import io.grpc.xds.sds.ReferenceCountingMap.ResourceDefinition;
import java.io.Closeable;
import java.util.concurrent.Executor;


/**
 * A SecretProvider is a "container" or provider of a secret. This is used by gRPC-xds to access
 * secrets, so is not part of the public API of gRPC. This "container" may represent a stream that
 * is receiving the requested secret(s) or it could represent file-system based secret(s) that are
 * dynamic.
 */
@Internal
public abstract class SecretProvider<K, T> implements Closeable {
  private ReferenceCountingMap<Object, SecretProvider<K, T>> map;
  private K key;

  void setSharedResourcePool(ReferenceCountingMap<Object, SecretProvider<K, T>> map,
                             K key) {
    this.map = map;
    this.key = key;
  }

  public static interface Callback<T> {
    /** Informs callee of new/updated secret. */
    void updateSecret(T secret);

    /** Informs callee of an exception that was generated. */
    void onException(Throwable throwable);
  }

  public SecretProvider<K, T> release() {
    map.release(key, this);
    return null;
  }

  @Override
  public void close() { }
  /**
   * Registers a callback on the given executor. The callback will run when secret becomes available
   * or immediately if the result is already available.
   */
  public abstract void addCallback(Callback<T> callback, Executor executor);
}
