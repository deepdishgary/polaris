/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.polaris.core.storage.aws.r2;

import static org.apache.polaris.core.config.RealmConfigurationSource.EMPTY_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auth0.jwt.JWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.config.RealmConfigImpl;
import org.apache.polaris.core.storage.CredentialVendingContext;
import org.apache.polaris.core.storage.LocationGrant;
import org.apache.polaris.core.storage.PolarisStorageActions;
import org.apache.polaris.core.storage.StorageAccessConfig;
import org.apache.polaris.core.storage.StorageAccessProperty;
import org.apache.polaris.core.storage.aws.AwsStorageConfigurationInfo;
import org.apache.polaris.core.storage.aws.ImmutableAwsStorageConfigurationInfo;
import org.apache.polaris.core.storage.aws.S3CredentialVendingMechanism;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CloudflareR2CredentialCacheKeyTest {

  private static final String ACCOUNT = "0123456789abcdef0123456789abcdef";
  private static final String ENDPOINT = "https://" + ACCOUNT + ".r2.cloudflarestorage.com";
  private static final RealmConfig REALM_CONFIG = new RealmConfigImpl(EMPTY_CONFIG, () -> "r");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);

  /** A valid CLOUDFLARE_R2 config: the @Value.Check requires endpoint, path style and region. */
  private static ImmutableAwsStorageConfigurationInfo.Builder r2Config(String endpoint) {
    return AwsStorageConfigurationInfo.builder()
        .credentialVendingMechanism(S3CredentialVendingMechanism.CLOUDFLARE_R2)
        .endpoint(endpoint)
        .pathStyleAccess(true)
        .region("auto")
        .addAllowedLocation("s3://bucket/");
  }

  private static final AwsStorageConfigurationInfo CONFIG = r2Config(ENDPOINT).build();

  private static CloudflareR2CredentialCacheKey key(
      List<String> prefixes, String scope, String secret, Optional<String> refresh) {
    return key(CONFIG, prefixes, scope, secret, R2ParentToken.fingerprint(secret), refresh);
  }

  private static CloudflareR2CredentialCacheKey key(
      AwsStorageConfigurationInfo config,
      List<String> prefixes,
      String scope,
      String secret,
      String fingerprint,
      Optional<String> refresh) {
    return CloudflareR2CredentialCacheKey.of(
        "realm",
        config,
        "bucket",
        prefixes,
        scope,
        "parent-key",
        fingerprint,
        Duration.ofSeconds(3600),
        refresh,
        secret,
        CLOCK,
        REALM_CONFIG);
  }

  @Test
  void equalityIgnoresSecretButNotFingerprint() {
    CloudflareR2CredentialCacheKey a =
        key(List.of("p/"), "object-read-only", "s1", Optional.empty());
    CloudflareR2CredentialCacheKey sameData =
        key(List.of("p/"), "object-read-only", "s1", Optional.empty());
    CloudflareR2CredentialCacheKey rotated =
        key(List.of("p/"), "object-read-only", "s2", Optional.empty());
    assertThat(a).isEqualTo(sameData).hasSameHashCodeAs(sameData);
    assertThat(a).isNotEqualTo(rotated);

    // The secret itself is auxiliary: two keys that differ only in it stay equal, which is what
    // makes the fingerprint the thing that has to change on rotation.
    CloudflareR2CredentialCacheKey secretOnlyDifference =
        key(CONFIG, List.of("p/"), "object-read-only", "s1", "deadbeef", Optional.empty());
    CloudflareR2CredentialCacheKey otherSecretSameFingerprint =
        key(CONFIG, List.of("p/"), "object-read-only", "s2", "deadbeef", Optional.empty());
    assertThat(secretOnlyDifference)
        .isEqualTo(otherSecretSameFingerprint)
        .hasSameHashCodeAs(otherSecretSameFingerprint);
  }

  @Test
  void theStorageConfigIsPartOfTheKey() {
    AwsStorageConfigurationInfo otherAccount =
        r2Config("https://ffffffffffffffffffffffffffffffff.r2.cloudflarestorage.com").build();
    assertThat(key(CONFIG, List.of("p/"), "object-read-only", "s", "deadbeef", Optional.empty()))
        .isNotEqualTo(
            key(
                otherAccount,
                List.of("p/"),
                "object-read-only",
                "s",
                "deadbeef",
                Optional.empty()));
  }

  @Test
  void loadUsesTheJurisdictionEndpointAndAudience() {
    String expectedHost = ACCOUNT + ".eu.r2.cloudflarestorage.com";
    AwsStorageConfigurationInfo eu = r2Config("https://" + expectedHost).build();
    StorageAccessConfig cfg =
        key(eu, List.of("wh/t1/"), "object-read-write", "secret", "deadbeef", Optional.empty())
            .load();
    assertThat(cfg.extraProperties()).containsEntry("s3.endpoint", "https://" + expectedHost);

    String sessionToken = cfg.credentials().get(StorageAccessProperty.AWS_TOKEN.getPropertyName());
    String decoded = new String(Base64.getDecoder().decode(sessionToken), StandardCharsets.UTF_8);
    String jwt = decoded.substring(R2TemporaryCredentialSigner.SESSION_TOKEN_PREFIX.length());
    assertThat(JWT.decode(jwt).getAudience()).containsExactly(expectedHost);
    assertThat(JWT.decode(jwt).getSubject()).isEqualTo(ACCOUNT);
  }

  @Test
  void toStringNeverContainsTheSecret() {
    String secret = "very-secret-value";
    assertThat(key(List.of("p/"), "object-read-only", secret, Optional.empty()).toString())
        .doesNotContain(secret);
  }

  @Test
  void refreshEndpointIsPartOfTheKey() {
    assertThat(key(List.of("p/"), "object-read-only", "s", Optional.empty()))
        .isNotEqualTo(key(List.of("p/"), "object-read-only", "s", Optional.of("http://x/creds")));
  }

  /**
   * The client contract: the credential triple plus the expiry as credentials, and exactly the four
   * extra keys the STS integration also emits, with {@code s3.path-style-access} and {@code
   * client.region} taken from the catalog's explicit values.
   */
  @Test
  void loadEmitsCredentialsAndExtraProperties() {
    StorageAccessConfig cfg =
        key(List.of("wh/t1/"), "object-read-write", "secret", Optional.of("http://polaris/creds"))
            .load();
    assertThat(cfg.credentials())
        .containsEntry(StorageAccessProperty.AWS_KEY_ID.getPropertyName(), "parent-key")
        .containsOnlyKeys(
            StorageAccessProperty.AWS_KEY_ID.getPropertyName(),
            StorageAccessProperty.AWS_SECRET_KEY.getPropertyName(),
            StorageAccessProperty.AWS_TOKEN.getPropertyName(),
            StorageAccessProperty.AWS_SESSION_TOKEN_EXPIRES_AT_MS.getPropertyName());
    assertThat(cfg.credentials().get(StorageAccessProperty.AWS_SECRET_KEY.getPropertyName()))
        .isNotEqualTo("secret");
    assertThat(cfg.extraProperties())
        .containsEntry("s3.endpoint", ENDPOINT)
        .containsEntry("s3.path-style-access", "true")
        .containsEntry("client.region", "auto")
        .containsEntry(
            StorageAccessProperty.AWS_REFRESH_CREDENTIALS_ENDPOINT.getPropertyName(),
            "http://polaris/creds")
        .containsOnlyKeys(
            "s3.endpoint",
            "s3.path-style-access",
            "client.region",
            StorageAccessProperty.AWS_REFRESH_CREDENTIALS_ENDPOINT.getPropertyName());
    assertThat(cfg.internalProperties()).isEmpty();
    assertThat(cfg.expiresAt()).contains(Instant.parse("2026-09-01T13:00:00Z"));
    assertThat(cfg.supportsCredentialVending()).isTrue();
  }

  @Test
  void loadWithoutARefreshEndpointOmitsThatKey() {
    StorageAccessConfig cfg =
        key(List.of("wh/t1/"), "object-read-only", "secret", Optional.empty()).load();
    assertThat(cfg.extraProperties())
        .containsOnlyKeys("s3.endpoint", "s3.path-style-access", "client.region");
  }

  @Test
  void scopeMapping() {
    assertThat(
            CloudflareR2CredentialsStorageIntegration.scopeFor(
                List.of(
                    new LocationGrant(Set.of("s3://b/x/"), Set.of(PolarisStorageActions.READ)))))
        .isEqualTo("object-read-only");
    assertThat(
            CloudflareR2CredentialsStorageIntegration.scopeFor(
                List.of(
                    new LocationGrant(Set.of("s3://b/x/"), Set.of(PolarisStorageActions.READ)),
                    new LocationGrant(Set.of("s3://b/x/"), Set.of(PolarisStorageActions.LIST)))))
        .isEqualTo("object-read-only");
    assertThat(
            CloudflareR2CredentialsStorageIntegration.scopeFor(
                List.of(
                    new LocationGrant(Set.of("s3://b/x/"), Set.of(PolarisStorageActions.WRITE)))))
        .isEqualTo("object-read-write");
    assertThat(
            CloudflareR2CredentialsStorageIntegration.scopeFor(
                List.of(
                    new LocationGrant(Set.of("s3://b/x/"), Set.of(PolarisStorageActions.DELETE)))))
        .isEqualTo("object-read-write");
    assertThat(
            CloudflareR2CredentialsStorageIntegration.scopeFor(
                List.of(new LocationGrant(Set.of("s3://b/x/"), Set.of(PolarisStorageActions.ALL)))))
        .isEqualTo("object-read-write");
  }

  @Test
  void prefixNormalization() {
    List<LocationGrant> grants =
        List.of(
            new LocationGrant(
                Set.of("s3://b/wh/db/t", "s3://b/wh/db/t/"), Set.of(PolarisStorageActions.READ)),
            new LocationGrant(Set.of("s3://b/wh/db/a/"), Set.of(PolarisStorageActions.WRITE)));
    assertThat(CloudflareR2CredentialsStorageIntegration.normalizedPrefixes(grants))
        .containsExactly("wh/db/a/", "wh/db/t/");
    assertThat(
            CloudflareR2CredentialsStorageIntegration.normalizedPrefixes(
                List.of(
                    new LocationGrant(
                        Set.of("s3://b", "s3://b/x/"), Set.of(PolarisStorageActions.READ)))))
        .isEmpty();
  }

  /**
   * The prefix handed to the signer must be what the location validators compared, minus the one
   * leading slash {@code rawPath()} always carries. Collapsing {@code //} or trimming trailing
   * slashes would widen the credential past the location Polaris authorized.
   */
  @ParameterizedTest
  @CsvSource({
    "s3://b/x/, x/",
    "s3://b/x, x/",
    "s3://b//x/, /x/",
    "s3://b/x//, x//",
    "s3://b//, /",
  })
  void prefixKeepsTheGrantPathByteForByte(String location, String expected) {
    assertThat(
            CloudflareR2CredentialsStorageIntegration.normalizedPrefixes(
                List.of(new LocationGrant(Set.of(location), Set.of(PolarisStorageActions.READ)))))
        .containsExactly(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"s3://b", "s3://b/"})
  void onlyTheBucketRootScopesToTheWholeBucket(String location) {
    assertThat(
            CloudflareR2CredentialsStorageIntegration.normalizedPrefixes(
                List.of(new LocationGrant(Set.of(location), Set.of(PolarisStorageActions.READ)))))
        .isEmpty();
  }

  @Test
  void singleBucketRequired() {
    assertThat(
            CloudflareR2CredentialsStorageIntegration.singleBucket(
                List.of(
                    new LocationGrant(
                        Set.of("s3://b/x/", "s3a://b/y/"), Set.of(PolarisStorageActions.READ))),
                CredentialVendingContext.empty()))
        .isEqualTo("b");
    assertThatThrownBy(
            () ->
                CloudflareR2CredentialsStorageIntegration.singleBucket(
                    List.of(
                        new LocationGrant(
                            Set.of("s3://b1/x/", "s3://b2/y/"),
                            Set.of(PolarisStorageActions.READ))),
                    CredentialVendingContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("b1")
        .hasMessageContaining("b2");
  }

  @Test
  void locationWithoutABucketIsRejected() {
    assertThatThrownBy(
            () ->
                CloudflareR2CredentialsStorageIntegration.singleBucket(
                    List.of(
                        new LocationGrant(Set.of("s3:///x/"), Set.of(PolarisStorageActions.READ))),
                    CredentialVendingContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("s3:///x/")
        .hasMessageContaining("names no bucket");
  }
}
