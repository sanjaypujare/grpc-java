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

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Strings;
import io.envoyproxy.envoy.config.core.v3.DataSource.SpecifierCase;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CertificateValidationContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.CommonTlsContext.ValidationContextTypeCase;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.TlsCertificate;
import javax.annotation.Nullable;

/** Class for utility functions for {@link CommonTlsContext}. */
final class CommonTlsContextUtil {

  private CommonTlsContextUtil() {}

  /** Returns true only if given CommonTlsContext uses no SdsSecretConfigs. */
  static boolean hasAllSecretsUsingFilename(CommonTlsContext commonTlsContext) {
    checkNotNull(commonTlsContext, "commonTlsContext");
    // return true if it has no SdsSecretConfig(s)
    return commonTlsContext.getTlsCertificatesCount() > 0
    || commonTlsContext.hasValidationContext();
    //return (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() == 0)
    //    && !commonTlsContext.hasValidationContextSdsSecretConfig();
  }

  /** Returns true only if given CommonTlsContext uses only SdsSecretConfigs. */
  static boolean hasAllSecretsUsingSds(CommonTlsContext commonTlsContext) {
    checkNotNull(commonTlsContext, "commonTlsContext");
    // return true if it has only SdsSecretConfig(s)
    return (commonTlsContext.getTlsCertificateSdsSecretConfigsCount() > 0)
        || commonTlsContext.hasValidationContextSdsSecretConfig();
  }

  static boolean hasCertProviderInstance(CommonTlsContext commonTlsContext) {
    checkNotNull(commonTlsContext, "commonTlsContext");
    return (commonTlsContext.hasTlsCertificateCertificateProviderInstance());
  }

  @Nullable
  static CertificateValidationContext getCertificateValidationContext(
      CommonTlsContext commonTlsContext) {
    checkNotNull(commonTlsContext, "commonTlsContext");
    ValidationContextTypeCase type = commonTlsContext.getValidationContextTypeCase();
    checkState(
        type == ValidationContextTypeCase.VALIDATION_CONTEXT
            || type == ValidationContextTypeCase.VALIDATIONCONTEXTTYPE_NOT_SET,
        "incorrect ValidationContextTypeCase");
    return type == ValidationContextTypeCase.VALIDATION_CONTEXT
        ? commonTlsContext.getValidationContext()
        : null;
  }

  @Nullable
  static CertificateValidationContext validateCertificateContext(
      @Nullable CertificateValidationContext certContext, boolean optional) {
    if (certContext == null || !certContext.hasTrustedCa()) {
      checkArgument(optional, "certContext is required");
      return null;
    }
    checkArgument(
        certContext.getTrustedCa().getSpecifierCase() == SpecifierCase.FILENAME,
        "filename expected");
    return certContext;
  }

  @Nullable
  static TlsCertificate validateTlsCertificate(
      @Nullable TlsCertificate tlsCertificate, boolean optional) {
    if (tlsCertificate == null) {
      checkArgument(optional, "tlsCertificate is required");
      return null;
    }
    if (optional
        && (tlsCertificate.getPrivateKey().getSpecifierCase() == SpecifierCase.SPECIFIER_NOT_SET)
        && (tlsCertificate.getCertificateChain().getSpecifierCase()
            == SpecifierCase.SPECIFIER_NOT_SET)) {
      return null;
    }
    checkArgument(
        tlsCertificate.getPrivateKey().getSpecifierCase() == SpecifierCase.FILENAME,
        "filename expected");
    checkArgument(
        tlsCertificate.getCertificateChain().getSpecifierCase() == SpecifierCase.FILENAME,
        "filename expected");
    return tlsCertificate;
  }
}
