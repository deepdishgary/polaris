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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.apache.polaris.core.storage.cache.StorageCredentialCache;
import org.apache.polaris.core.storage.cache.StorageCredentialCacheConfig;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class CloudflareR2CredentialsStorageIntegrationTest {

  private static final String ACCOUNT = "0123456789abcdef0123456789abcdef";
  private static final String ENDPOINT = "https://" + ACCOUNT + ".r2.cloudflarestorage.com";
  private static final RealmConfig REALM_CONFIG = new RealmConfigImpl(EMPTY_CONFIG, () -> "realm");

  // Fixed at the current second, not at a literal past instant: StorageCredentialCache computes an
  // entry's TTL from (expirationTime - System.currentTimeMillis()) / 2, so credentials minted from
  // a clock in the past land in the cache already expired. See expiredCredentialIsNotReused.
  private static final Clock CLOCK =
      Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);

  private static ImmutableAwsStorageConfigurationInfo.Builder r2Config() {
    return AwsStorageConfigurationInfo.builder()
        .credentialVendingMechanism(S3CredentialVendingMechanism.CLOUDFLARE_R2)
        .endpoint(ENDPOINT)
        .pathStyleAccess(true)
        .region("auto")
        .addAllowedLocation("s3://bucket/wh/");
  }

  private static final AwsStorageConfigurationInfo CONFIG =
      r2Config().storageName("primary").build();
  private static final R2ParentTokenResolver RESOLVER =
      name ->
          "primary".equals(name) ? Optional.of(new R2ParentToken("pk", "ps")) : Optional.empty();
  private static final List<LocationGrant> GRANTS =
      List.of(new LocationGrant(Set.of("s3://bucket/wh/db/t/"), Set.of(PolarisStorageActions.ALL)));

  private static CloudflareR2CredentialsStorageIntegration integration(
      R2ParentTokenResolver resolver,
      Clock clock,
      StorageCredentialCache cache,
      AwsStorageConfigurationInfo config) {
    return new CloudflareR2CredentialsStorageIntegration(
        resolver, clock, cache, config, REALM_CONFIG);
  }

  @Test
  void vendsWithoutCache() {
    StorageAccessConfig cfg =
        integration(RESOLVER, CLOCK, null, CONFIG)
            .getStorageAccessConfig(GRANTS, Optional.empty(), CredentialVendingContext.empty());
    assertThat(cfg.credentials())
        .containsEntry(StorageAccessProperty.AWS_KEY_ID.getPropertyName(), "pk");
    assertThat(cfg.extraProperties()).containsEntry("s3.endpoint", ENDPOINT);
  }

  @Test
  void secondCallIsServedFromCache() {
    StorageCredentialCacheConfig cacheConfig = () -> 10_000;
    StorageCredentialCache cache = new StorageCredentialCache(cacheConfig);
    CloudflareR2CredentialsStorageIntegration integration =
        integration(RESOLVER, CLOCK, cache, CONFIG);
    StorageAccessConfig first =
        integration.getStorageAccessConfig(
            GRANTS, Optional.empty(), CredentialVendingContext.empty());
    StorageAccessConfig second =
        integration.getStorageAccessConfig(
            GRANTS, Optional.empty(), CredentialVendingContext.empty());
    assertThat(second).isSameAs(first);
    assertThat(cache.getEstimatedSize()).isEqualTo(1);
  }

  @Test
  void missingParentTokenIsAnIllegalArgumentNamingTheProperty() {
    AwsStorageConfigurationInfo unconfigured = r2Config().storageName("unknown").build();
    CloudflareR2CredentialsStorageIntegration integration =
        integration(RESOLVER, CLOCK, null, unconfigured);
    assertThatThrownBy(
            () ->
                integration.getStorageAccessConfig(
                    GRANTS, Optional.empty(), CredentialVendingContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(R2ParentTokenResolver.missingTokenMessage("unknown"))
        .hasMessageContaining("polaris.storage.cloudflare-r2.unknown.access-key");
  }

  @Test
  void missingDefaultParentTokenIsAnIllegalArgumentNamingTheDefaultEntry() {
    AwsStorageConfigurationInfo unnamed = r2Config().build();
    CloudflareR2CredentialsStorageIntegration integration =
        integration(R2ParentTokenResolver.none(), CLOCK, null, unnamed);
    assertThatThrownBy(
            () ->
                integration.getStorageAccessConfig(
                    GRANTS, Optional.empty(), CredentialVendingContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(R2ParentTokenResolver.missingTokenMessage(null));
  }

  @Test
  void multiBucketGrantsAreRejected() {
    CloudflareR2CredentialsStorageIntegration integration =
        integration(RESOLVER, CLOCK, null, CONFIG);
    List<LocationGrant> grants =
        List.of(
            new LocationGrant(
                Set.of("s3://bucket/a/", "s3://other/b/"), Set.of(PolarisStorageActions.READ)));
    assertThatThrownBy(
            () ->
                integration.getStorageAccessConfig(
                    grants, Optional.empty(), CredentialVendingContext.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one bucket");
  }

  @Test
  void multiBucketRejectionNamesTheTable() {
    CloudflareR2CredentialsStorageIntegration integration =
        integration(RESOLVER, CLOCK, null, CONFIG);
    List<LocationGrant> grants =
        List.of(
            new LocationGrant(
                Set.of("s3://bucket/a/", "s3://other/b/"), Set.of(PolarisStorageActions.READ)));
    CredentialVendingContext context =
        CredentialVendingContext.builder()
            .realm(Optional.of("realm"))
            .catalogName(Optional.of("c"))
            .namespace(Optional.of("ns"))
            .tableName(Optional.of("t"))
            .build();
    assertThatThrownBy(() -> integration.getStorageAccessConfig(grants, Optional.empty(), context))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("c.ns.t")
        .hasMessageContaining("bucket")
        .hasMessageContaining("other");
  }

  /** The vended session token is base64("jwt/" + JWT); return the decoded JWT. */
  private static DecodedJWT vendedJwt(StorageAccessConfig cfg) {
    String sessionToken = cfg.credentials().get(StorageAccessProperty.AWS_TOKEN.getPropertyName());
    String raw = new String(Base64.getDecoder().decode(sessionToken), StandardCharsets.UTF_8);
    assertThat(raw).startsWith(R2TemporaryCredentialSigner.SESSION_TOKEN_PREFIX);
    return JWT.decode(raw.substring(R2TemporaryCredentialSigner.SESSION_TOKEN_PREFIX.length()));
  }

  private static String vendedScope(StorageAccessConfig cfg) {
    return vendedJwt(cfg).getClaim("scope").asString();
  }

  @Test
  void uniformReadOnlyGrantsVendReadOnly() {
    List<LocationGrant> grants =
        List.of(
            new LocationGrant(Set.of("s3://bucket/wh/db/t/"), Set.of(PolarisStorageActions.READ)),
            new LocationGrant(Set.of("s3://bucket/wh/db/u/"), Set.of(PolarisStorageActions.LIST)));
    StorageAccessConfig cfg =
        integration(RESOLVER, CLOCK, null, CONFIG)
            .getStorageAccessConfig(grants, Optional.empty(), CredentialVendingContext.empty());
    assertThat(vendedScope(cfg)).isEqualTo(R2TemporaryCredentialSigner.SCOPE_OBJECT_READ_ONLY);
  }

  @Test
  void uniformWriteCapableGrantsVendReadWrite() {
    List<LocationGrant> grants =
        List.of(
            new LocationGrant(Set.of("s3://bucket/wh/db/t/"), Set.of(PolarisStorageActions.WRITE)),
            new LocationGrant(Set.of("s3://bucket/wh/db/u/"), Set.of(PolarisStorageActions.ALL)));
    StorageAccessConfig cfg =
        integration(RESOLVER, CLOCK, null, CONFIG)
            .getStorageAccessConfig(grants, Optional.empty(), CredentialVendingContext.empty());
    assertThat(vendedScope(cfg)).isEqualTo(R2TemporaryCredentialSigner.SCOPE_OBJECT_READ_WRITE);
  }

  @Test
  void mixedReadOnlyAndWriteGrantsAreRejected() {
    List<LocationGrant> grants =
        List.of(
            new LocationGrant(Set.of("s3://bucket/wh/db/t/"), Set.of(PolarisStorageActions.READ)),
            new LocationGrant(Set.of("s3://bucket/wh/db/u/"), Set.of(PolarisStorageActions.WRITE)));
    CloudflareR2CredentialsStorageIntegration integration =
        integration(RESOLVER, CLOCK, null, CONFIG);
    CredentialVendingContext context =
        CredentialVendingContext.builder()
            .realm(Optional.of("realm"))
            .catalogName(Optional.of("c"))
            .namespace(Optional.of("ns"))
            .tableName(Optional.of("t"))
            .build();
    assertThatThrownBy(() -> integration.getStorageAccessConfig(grants, Optional.empty(), context))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(
            "R2 credentials carry one scope for all prefixes; grants with mixed read-only and"
                + " write actions cannot be vended together for table c.ns.t");
  }

  @Test
  void oneGrantMixingReadAndWriteActionsVendsReadWrite() {
    List<LocationGrant> grants =
        List.of(
            new LocationGrant(
                Set.of("s3://bucket/wh/db/t/"),
                Set.of(PolarisStorageActions.READ, PolarisStorageActions.WRITE)));
    assertThat(CloudflareR2CredentialsStorageIntegration.scopeFor(grants))
        .isEqualTo(R2TemporaryCredentialSigner.SCOPE_OBJECT_READ_WRITE);
    CloudflareR2CredentialsStorageIntegration integration =
        integration(RESOLVER, CLOCK, null, CONFIG);
    assertThatCode(
            () ->
                integration.getStorageAccessConfig(
                    grants, Optional.empty(), CredentialVendingContext.empty()))
        .doesNotThrowAnyException();
  }

  @Test
  void mintIsLoggedWithoutCredentialMaterial() {
    String parentSecret = "parent-secret-must-not-be-logged-9f3c";
    R2ParentTokenResolver resolver =
        name -> Optional.of(new R2ParentToken("parent-key-id", parentSecret));
    Logger logger =
        (Logger) LoggerFactory.getLogger(CloudflareR2CredentialsStorageIntegration.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Level original = logger.getLevel();
    logger.setLevel(Level.INFO);
    logger.addAppender(appender);
    StorageAccessConfig cfg;
    try {
      cfg =
          integration(resolver, CLOCK, null, CONFIG)
              .getStorageAccessConfig(GRANTS, Optional.empty(), CredentialVendingContext.empty());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(original);
    }
    String sessionToken = cfg.credentials().get(StorageAccessProperty.AWS_TOKEN.getPropertyName());
    String derivedSecret =
        cfg.credentials().get(StorageAccessProperty.AWS_SECRET_KEY.getPropertyName());
    assertThat(sessionToken).isNotBlank();
    assertThat(derivedSecret).isNotBlank();
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(
            message ->
                assertThat(message)
                    .contains("Minted R2 credential")
                    .contains("storageName=primary")
                    .contains("bucket=bucket")
                    .contains("prefixes=1")
                    .contains("scope=object-read-write")
                    .contains("ttl=")
                    .contains("expiresAt="))
        .allSatisfy(
            message ->
                assertThat(message)
                    .doesNotContain(parentSecret)
                    .doesNotContain(sessionToken)
                    .doesNotContain(derivedSecret));
  }

  @Test
  void mintOfTheDefaultStorageNameIsLoggedAsDefault() {
    AwsStorageConfigurationInfo unnamed = r2Config().build();
    Logger logger =
        (Logger) LoggerFactory.getLogger(CloudflareR2CredentialsStorageIntegration.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Level original = logger.getLevel();
    logger.setLevel(Level.INFO);
    logger.addAppender(appender);
    try {
      integration(name -> Optional.of(new R2ParentToken("pk", "ps")), CLOCK, null, unnamed)
          .getStorageAccessConfig(GRANTS, Optional.empty(), CredentialVendingContext.empty());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(original);
    }
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anySatisfy(message -> assertThat(message).contains("storageName=<default>"));
  }

  /** Scope is part of the cache key: a WRITE vend and a READ vend for one location never share. */
  @Test
  void writeAndReadGrantsForTheSameLocationGetDistinctCachedTokens() {
    StorageCredentialCacheConfig cacheConfig = () -> 10_000;
    StorageCredentialCache cache = new StorageCredentialCache(cacheConfig);
    CloudflareR2CredentialsStorageIntegration integration =
        integration(RESOLVER, CLOCK, cache, CONFIG);
    List<LocationGrant> write =
        List.of(
            new LocationGrant(Set.of("s3://bucket/wh/db/t/"), Set.of(PolarisStorageActions.WRITE)));
    List<LocationGrant> read =
        List.of(
            new LocationGrant(Set.of("s3://bucket/wh/db/t/"), Set.of(PolarisStorageActions.READ)));
    StorageAccessConfig writeCfg =
        integration.getStorageAccessConfig(
            write, Optional.empty(), CredentialVendingContext.empty());
    StorageAccessConfig readCfg =
        integration.getStorageAccessConfig(
            read, Optional.empty(), CredentialVendingContext.empty());
    assertThat(cache.getEstimatedSize()).isEqualTo(2);
    assertThat(vendedScope(writeCfg))
        .isEqualTo(R2TemporaryCredentialSigner.SCOPE_OBJECT_READ_WRITE);
    assertThat(vendedScope(readCfg)).isEqualTo(R2TemporaryCredentialSigner.SCOPE_OBJECT_READ_ONLY);
    String tokenName = StorageAccessProperty.AWS_TOKEN.getPropertyName();
    assertThat(readCfg.credentials().get(tokenName))
        .isNotEqualTo(writeCfg.credentials().get(tokenName));
  }

  /** The credential lifetime comes from the realm's STORAGE_CREDENTIAL_DURATION_SECONDS. */
  @Test
  void ttlComesFromTheRealmConfig() {
    RealmConfig tenMinutes =
        new RealmConfigImpl(
            (rc, name) -> "STORAGE_CREDENTIAL_DURATION_SECONDS".equals(name) ? 600 : null,
            () -> "realm");
    CloudflareR2CredentialsStorageIntegration integration =
        new CloudflareR2CredentialsStorageIntegration(RESOLVER, CLOCK, null, CONFIG, tenMinutes);
    StorageAccessConfig cfg =
        integration.getStorageAccessConfig(
            GRANTS, Optional.empty(), CredentialVendingContext.empty());
    DecodedJWT jwt = vendedJwt(cfg);
    Instant expected = CLOCK.instant().plusSeconds(600);
    assertThat(jwt.getExpiresAtAsInstant()).isEqualTo(expected);
    assertThat(
            jwt.getExpiresAtAsInstant().getEpochSecond()
                - jwt.getIssuedAtAsInstant().getEpochSecond())
        .isEqualTo(600);
    String expiresName = StorageAccessProperty.AWS_SESSION_TOKEN_EXPIRES_AT_MS.getPropertyName();
    String advertised =
        cfg.credentials().containsKey(expiresName)
            ? cfg.credentials().get(expiresName)
            : cfg.extraProperties().get(expiresName);
    assertThat(advertised).isEqualTo(String.valueOf(expected.toEpochMilli()));
  }

  @Test
  void expiredCredentialIsNotReused() {
    // A clock two hours behind the wall clock mints credentials whose expiry is already past, so
    // StorageCredentialCache computes a zero TTL and the second call has to mint again.
    Clock stale = Clock.fixed(Instant.now().minus(2, ChronoUnit.HOURS), ZoneOffset.UTC);
    StorageCredentialCacheConfig cacheConfig = () -> 10_000;
    StorageCredentialCache cache = new StorageCredentialCache(cacheConfig);
    CloudflareR2CredentialsStorageIntegration integration =
        integration(RESOLVER, stale, cache, CONFIG);
    StorageAccessConfig first =
        integration.getStorageAccessConfig(
            GRANTS, Optional.empty(), CredentialVendingContext.empty());
    StorageAccessConfig second =
        integration.getStorageAccessConfig(
            GRANTS, Optional.empty(), CredentialVendingContext.empty());
    assertThat(second).isNotSameAs(first);
  }
}
