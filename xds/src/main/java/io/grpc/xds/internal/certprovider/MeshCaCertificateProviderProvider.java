/*
 * Copyright 2020 The gRPC Authors
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

package io.grpc.xds.internal.certprovider;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.InternalLogId;
import io.grpc.SynchronizationContext;
import io.grpc.internal.ExponentialBackoffPolicy;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.TimeProvider;
import io.grpc.xds.XdsClientWrapperForServerSds;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Provider of {@link CertificateProvider}s. Implemented by the implementer of the plugin. We may
 * move this out of the internal package and make this an official API in the future.
 */
final class MeshCaCertificateProviderProvider implements CertificateProviderProvider {
  private static final Logger logger = Logger.getLogger(MeshCaCertificateProviderProvider.class.getName());

  private static final String MESHCA_URL_KEY = "meshCaUrl";
  private static final String RPC_TIMEOUT_SECONDS_KEY = "rpcTimeoutSeconds";
  private static final String GKECLUSTER_URL_KEY = "gkeClusterUrl";
  private static final String CERT_VALIDITY_SECONDS_KEY = "certValiditySeconds";
  private static final String RENEWAL_GRACE_PERIOD_SECONDS_KEY = "renewalGracePeriodSeconds";
  private static final String KEY_ALGO_KEY = "keyAlgo";  // aka keyType
  private static final String KEY_SIZE_KEY = "keySize";
  private static final String SIGNATURE_ALGO_KEY = "signatureAlgo";
  private static final String MAX_RETRY_ATTEMPTS_KEY = "maxRetryAttempts";
  private static final String STS_URL_KEY = "stsUrl";
  private static final String GKE_SA_JWT_LOCATION_KEY = "gkeSaJwtLocation";

  private static final String MESHCA_URL_DEFAULT ="meshca.googleapis.com";
  private static final long RPC_TIMEOUT_SECONDS_DEFAULT = 5L;
  private static final long CERT_VALIDITY_SECONDS_DEFAULT = 9L * 3600L; // 9 hours
  private static final long RENEWAL_GRACE_PERIOD_SECONDS_DEFAULT = 1L * 3600L; // 1 hour
  private static final String KEY_ALGO_DEFAULT = "RSA";  // aka keyType
  private static final int KEY_SIZE_DEFAULT = 2048;
  private static final String SIGNATURE_ALGO_DEFAULT = "SHA256withRSA";
  private static final int MAX_RETRY_ATTEMPTS_DEFAULT = 10;
  private static final String STS_URL_DEFAULT = "https://securetoken.googleapis.com/v1/identitybindingtoken";

  static {
    try {
      CertificateProviderRegistry.getInstance().register(new MeshCaCertificateProviderProvider());
    } catch (Throwable t) {
      logger.log(Level.SEVERE, "Registering MeshCaCertificateProviderProvider", t);
    }
  }

  @Override
  public String getName() {
    return "meshCA";
  }

  @Override
  public CertificateProvider createCertificateProvider(
          Object config, CertificateProvider.DistributorWatcher watcher, boolean notifyCertUpdates) {
    /*
      Things to extract from config:
      gkeClusterUrl, cert validity, key-size, algo, signature-algo, renewalGracePeriod, maxRetryAttempts,
      STS service URI, GKE SA JWT location in the file system

      Construct audience from gkeClusterUrl by extracting trust-domain (as the GCP project id)
     */
    return new MeshCaCertificateProvider(watcher, notifyCertUpdates, "meshca.googleapis.com", null,
            TimeUnit.HOURS.toSeconds(9L), 2048, "RSA", "SHA256withRSA",
            MeshCaCertificateProvider.ChannelFactory.getInstance(), new ExponentialBackoffPolicy.Provider(),
            TimeUnit.HOURS.toSeconds(1L), 3, null,
            Executors.newSingleThreadScheduledExecutor(), TimeProvider.SYSTEM_TIME_PROVIDER);
  }

  static Config validateAndTranslateConfig(Object config) {
    // TODO(sanjaypujare): add support for string, struct proto etc
    checkArgument(config instanceof Map, "Only Map supported for config");
    Map<String, String> map = (Map<String, String>)config;

    Config configObj = new Config();
    configObj.meshCaUrl = mapGetOrDefault(map, MESHCA_URL_KEY, MESHCA_URL_DEFAULT);
    configObj.rpcTimeoutSeconds = mapGetOrDefault(map, RPC_TIMEOUT_SECONDS_KEY, RPC_TIMEOUT_SECONDS_DEFAULT);
    configObj.gkeClusterUrl = map.get(GKECLUSTER_URL_KEY);
    checkNotNull(configObj.gkeClusterUrl, GKECLUSTER_URL_KEY + " is required in the config");
    configObj.certValiditySeconds = mapGetOrDefault(map, CERT_VALIDITY_SECONDS_KEY, CERT_VALIDITY_SECONDS_DEFAULT);
    configObj.renewalGracePeriodSeconds = mapGetOrDefault(map, RENEWAL_GRACE_PERIOD_SECONDS_KEY, RENEWAL_GRACE_PERIOD_SECONDS_DEFAULT);
    configObj.keyAlgo = mapGetOrDefault(map, KEY_ALGO_KEY, KEY_ALGO_DEFAULT);
    configObj.keySize = mapGetOrDefault(map, KEY_SIZE_KEY, KEY_SIZE_DEFAULT);
    configObj.signatureAlgo = mapGetOrDefault(map, SIGNATURE_ALGO_KEY, SIGNATURE_ALGO_DEFAULT);
    configObj.maxRetryAttempts = mapGetOrDefault(map, MAX_RETRY_ATTEMPTS_KEY, MAX_RETRY_ATTEMPTS_DEFAULT);
    configObj.stsUrl = mapGetOrDefault(map, STS_URL_KEY, STS_URL_DEFAULT);
    configObj.gkeSaJwtLocation = map.get(GKE_SA_JWT_LOCATION_KEY);
    checkNotNull(configObj.gkeClusterUrl, GKE_SA_JWT_LOCATION_KEY + " is required in the config");
    return configObj;
  }

  private static String mapGetOrDefault(Map<String, String> map, String key, String defaultVal) {
    String value = map.get(key);
    if (value == null) {
      return defaultVal;
    }
    return value;
  }

  private static Long mapGetOrDefault(Map<String, String> map, String key, long defaultVal) {
    String value = map.get(key);
    if (value == null) {
      return defaultVal;
    }
    return Long.parseLong(value);
  }

  private static Integer mapGetOrDefault(Map<String, String> map, String key, int defaultVal) {
    String value = map.get(key);
    if (value == null) {
      return defaultVal;
    }
    return Integer.parseInt(value);
  }

  /** POJO class for storing various config values. */
  @VisibleForTesting
  static class Config {
    String meshCaUrl;
    Long rpcTimeoutSeconds;
    String gkeClusterUrl;
    Long certValiditySeconds;
    Long renewalGracePeriodSeconds;
    String keyAlgo;   // aka keyType
    Integer keySize;
    String signatureAlgo;
    Integer maxRetryAttempts;
    String stsUrl;
    String gkeSaJwtLocation;
  }
}
