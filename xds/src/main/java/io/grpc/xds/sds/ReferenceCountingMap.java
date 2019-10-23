/*
 * Copyright 2014 The gRPC Authors
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

import com.google.common.base.Preconditions;
import io.grpc.internal.GrpcUtil;
import io.grpc.internal.LogExceptionRunnable;

import javax.annotation.concurrent.ThreadSafe;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A map for reference counted shared resources.
 *
 * <p>This class is used to hold shared resources as reference counted objects and manages their
 * life-cycles.
 *
 * <p>A resource is identified by the reference of a {@link ResourceDefinition} object, which provides
 * the getKey() and create() methods. The key from getKey() maps to the object stored in the map and
 * create() is used to create the resource (object) in case it is not present in the map.
 *
 * <p>Resources are ref-counted and closed by calling {@link Closeable#close()} after a delay when
 * the ref-count reaches zero.
 */
@ThreadSafe
public final class ReferenceCountingMap<K, T extends Closeable> {
  private static final Logger logger = Logger.getLogger(ReferenceCountingMap.class.getName());

  static final long DESTROY_DELAY_SECONDS = 1;

  ReferenceCountingMap() {
    this(new ScheduledExecutorFactory() {
      @Override
      public ScheduledExecutorService createScheduledExecutor() {
        return Executors.newSingleThreadScheduledExecutor(
                GrpcUtil.getThreadFactory("grpc-shared-destroyer-%d", true));
      }
    });
  }

  private final HashMap<K, Instance<T>> instances;

  private final ScheduledExecutorFactory destroyerFactory;

  private ScheduledExecutorService destroyer;

  // Visible to tests that would need to create instances of the holder.
  ReferenceCountingMap(ScheduledExecutorFactory destroyerFactory) {
    this.destroyerFactory = destroyerFactory;
    instances = new HashMap<>();
  }

  /**
   * Try to get an existing instance of the given resource. If an instance does not exist, create a
   * new one with the given factory.
   *
   * @param resource the singleton object that identifies the requested static resource
   */
  public T get(ResourceDefinition<K, T> resource) {
    return getInternal(resource);
  }

  /**
   * Releases an instance of the given resource.
   *
   * <p>The instance must have been obtained from {@link #get(ResourceDefinition)}. Otherwise will throw
   * IllegalArgumentException.
   *
   * <p>Caller must not release a reference more than once. It's advised that you clear the
   * reference to the instance with the null returned by this method.
   *
   * @param key the key that identifies the shared resource
   * @param instance the released static resource
   *
   * @return a null which the caller can use to clear the reference to that instance.
   */
  public T release(final K key, final T instance) {
    return releaseInternal(key, instance);
  }

  /**
   *
   * @see #get(ResourceDefinition)
   */
  @SuppressWarnings("unchecked")
  private synchronized T getInternal(ResourceDefinition<K, T> resource) {
    Instance<T> instance = instances.get(resource.getKey());
    if (instance == null) {
      instance = new Instance<>(resource.create());
      instances.put(resource.getKey(), instance);
    }
    if (instance.destroyTask != null) {
      instance.destroyTask.cancel(false);
      instance.destroyTask = null;
    }
    instance.refcount++;
    return (T) instance.payload;
  }

  private synchronized T releaseInternal(final K key, final T instance) {
    final Instance<T> cached = instances.get(key);
    if (cached == null) {
      throw new IllegalArgumentException("No cached instance found for " + key);
    }
    Preconditions.checkArgument(instance == cached.payload, "Releasing the wrong instance");
    Preconditions.checkState(cached.refcount > 0, "Refcount has already reached zero");
    cached.refcount--;
    if (cached.refcount == 0) {
      Preconditions.checkState(cached.destroyTask == null, "Destroy task already scheduled");
      // Schedule a delayed task to destroy the resource.
      if (destroyer == null) {
        destroyer = destroyerFactory.createScheduledExecutor();
      }
      cached.destroyTask = destroyer.schedule(new LogExceptionRunnable(new Runnable() {
        @Override
        public void run() {
          synchronized (ReferenceCountingMap.this) {
            // Refcount may have gone up since the task was scheduled. Re-check it.
            if (cached.refcount == 0) {
              try {
                cached.payload.close();
              } catch (IOException e) {
                logger.log(Level.SEVERE, "close", e);
              } finally {
                instances.remove(key);
                if (instances.isEmpty()) {
                  destroyer.shutdown();
                  destroyer = null;
                }
              }
            }
          }
        }
      }), DESTROY_DELAY_SECONDS, TimeUnit.SECONDS);
    }
    // Always returning null
    return null;
  }

  /**
   * Defines a resource: identify it using a key and the way to create it.
   */
  public interface ResourceDefinition<K, T> {
    /**
     * Create a new instance of the resource.
     */
    T create();

    /** returns the key to be used to identify the resource. */
    K getKey();
  }

  interface ScheduledExecutorFactory {
    ScheduledExecutorService createScheduledExecutor();
  }

  private static class Instance<T extends Closeable> {
    final T payload;
    int refcount;
    ScheduledFuture<?> destroyTask;

    Instance(T payload) {
      this.payload = payload;
      this.refcount = 0;
    }
  }
}
