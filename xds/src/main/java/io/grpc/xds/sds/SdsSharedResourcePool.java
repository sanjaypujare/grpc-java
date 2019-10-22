/*
 * Copyright 2016 The gRPC Authors
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

import io.grpc.internal.ObjectPool;

/**
 * An ObjectPool backed by a {@link SdsSharedResourceHolder.Resource}.
 */
public final class SdsSharedResourcePool<T> implements ObjectPool<T> {
  private final SdsSharedResourceHolder.Resource<T> resource;
  final private SdsSharedResourceHolder<T> holder;

  private SdsSharedResourcePool(SdsSharedResourceHolder.Resource<T> resource, SdsSharedResourceHolder<T> holder) {
    this.resource = resource;
    this.holder = holder;
  }

  public static <T> SdsSharedResourcePool<T> forResource(SdsSharedResourceHolder.Resource<T> resource, SdsSharedResourceHolder<T> holder) {
    return new SdsSharedResourcePool<>(resource, holder);
  }

  @Override
  public T getObject() {
    return holder.get(resource);
  }

  @Override
  @SuppressWarnings("unchecked")
  public T returnObject(Object object) {
    holder.release(resource, (T) object);
    return null;
  }
}
