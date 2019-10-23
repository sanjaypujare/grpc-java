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
 * A holder for shared resource singletons.
 *
 * <p>Components like client channels and servers need certain resources, e.g. a thread pool, to
 * run. If the user has not provided such resources, these components will use a default one, which
 * is shared as a static resource. This class holds these default resources and manages their
 * life-cycles.
 *
 * <p>A resource is identified by the reference of a {@link Resource} object, which is typically a
 * singleton, provided to the get() and release() methods. Each Resource object (not its class) maps
 * to an object cached in the holder.
 *
 * <p>Resources are ref-counted and shut down after a delay when the ref-count reaches zero.
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

  private final HashMap<K, Instance> instances;

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
  public T get(Resource<K, T> resource) {
    return getInternal(resource);
  }

  /**
   * Releases an instance of the given resource.
   *
   * <p>The instance must have been obtained from {@link #get(Resource)}. Otherwise will throw
   * IllegalArgumentException.
   *
   * <p>Caller must not release a reference more than once. It's advisory that you clear the
   * reference to the instance with the null returned by this method.
   *
   * @param resource the singleton Resource object that identifies the released static resource
   * @param instance the released static resource
   *
   * @return a null which the caller can use to clear the reference to that instance.
   */
  public T release(final Resource<K, T> resource, final T instance) {
    return releaseInternal(resource, instance);
  }

  /**
   * Visible to unit tests.
   *
   * @see #get(Resource)
   */
  @SuppressWarnings("unchecked")
  synchronized T getInternal(Resource<K, T> resource) {
    Instance instance = instances.get(resource.getKey());
    if (instance == null) {
      instance = new Instance(resource.create());
      instances.put(resource.getKey(), instance);
    }
    if (instance.destroyTask != null) {
      instance.destroyTask.cancel(false);
      instance.destroyTask = null;
    }
    instance.refcount++;
    return (T) instance.payload;
  }

  /**
   * Visible to unit tests.
   */
  synchronized T releaseInternal(final Resource<K, T> resource, final T instance) {
    final Instance cached = instances.get(resource.getKey());
    if (cached == null) {
      throw new IllegalArgumentException("No cached instance found for " + resource);
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
                instances.remove(resource.getKey());
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
   * Defines a resource, and the way to create and destroy instances of it.
   */
  public interface Resource<K, T> {
    /**
     * Create a new instance of the resource.
     */
    T create();

    K getKey();
  }

  interface ScheduledExecutorFactory {
    ScheduledExecutorService createScheduledExecutor();
  }

  private static class Instance {
    final Closeable payload;
    int refcount;
    ScheduledFuture<?> destroyTask;

    Instance(Closeable payload) {
      this.payload = payload;
      this.refcount = 0;
    }
  }
}
