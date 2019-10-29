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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.LogExceptionRunnable;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A map for managing {@link SslContextProvider}s as reference-counted shared resources.
 *
 * <p>A resource is identified by the reference of a {@link ResourceDefinition} object, which
 * provides the getKey() and create() methods. The key from getKey() maps to the object stored in
 * the map and create() is used to create the resource (object) in case it is not present in the
 * map.
 *
 * <p>{@link SslContextProvider}s are ref-counted and closed by calling
 * {@link SslContextProvider#close()} when ref-count reaches zero.
 *
 * @param <K> Key type for the map
 */
@ThreadSafe
final class ReferenceCountingSslContextProviderMap<K> {

  private static final Logger logger = Logger.getLogger(ReferenceCountingSslContextProviderMap.class.getName());
  private final HashMap<K, Instance> instances;
  private final SslContextProviderFactory<K> sslContextProviderFactory;

  ReferenceCountingSslContextProviderMap(SslContextProviderFactory<K> sslContextProviderFactory) {
    instances = new HashMap<>();
    this.sslContextProviderFactory = sslContextProviderFactory;
  }

  /**
   * Try to get an existing instance of a given resource. If an instance does not exist, create a
   * new one with the given ResourceDefinition.
   */
  public SslContextProvider get(K key) {
    checkNotNull(key, "key");
    return getInternal(key);
  }

  /**
   * Releases an instance of the given resource.
   *
   * <p>The instance must have been obtained from {@link #get(ResourceDefinition)}. Otherwise will
   * throw IllegalArgumentException.
   *
   * <p>Caller must not release a reference more than once. It's advised that you clear the
   * reference to the instance with the null returned by this method.
   *
   * @param key the key that identifies the shared resource
   * @param value the resource to be released
   * @return a null which the caller can use to clear the reference to that instance.
   */
  public SslContextProvider release(final K key, final SslContextProvider value) {
    checkNotNull(key, "key");
    checkNotNull(value, "value");
    return releaseInternal(key, value);
  }

  private synchronized SslContextProvider getInternal(K key) {
    Instance instance = instances.get(key);
    if (instance == null) {
      instance = new Instance(sslContextProviderFactory.createSslContextProvider(key));
      instances.put(key, instance);
    }
    instance.refcount++;
    return instance.payload;
  }

  private synchronized SslContextProvider releaseInternal(final K key, final SslContextProvider instance) {
    final Instance cached = instances.get(key);
    if (cached == null) {
      throw new IllegalArgumentException("No cached instance found for " + key);
    }
    Preconditions.checkArgument(instance == cached.payload, "Releasing the wrong instance");
    Preconditions.checkState(cached.refcount > 0, "Refcount has already reached zero");
    cached.refcount--;
    if (cached.refcount == 0) {
      try {
        cached.payload.close();
      } finally {
        instances.remove(key);
      }
    }
    // Always returning null
    return null;
  }

  /** Defines a resource: identified using the key and the way to create it. */
  public interface ResourceDefinition<K> {

    /** Create a new instance of the resource. */
    SslContextProvider create();

    /** returns the key to be used to identify the resource. */
    K getKey();
  }

  interface ScheduledExecutorFactory {

    ScheduledExecutorService createScheduledExecutor();
  }

  /** Defines a factory that allows creation of an SslContextProvider. */
  public interface SslContextProviderFactory<K> {
    SslContextProvider createSslContextProvider(K key);
  }

  private static class Instance {
    final SslContextProvider payload;
    int refcount;

    Instance(SslContextProvider payload) {
      this.payload = payload;
      this.refcount = 0;
    }
  }
}
