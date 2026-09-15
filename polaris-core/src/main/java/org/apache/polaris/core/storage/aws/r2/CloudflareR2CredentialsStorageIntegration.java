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

import static org.apache.polaris.core.config.FeatureConfiguration.STORAGE_CREDENTIAL_DURATION_SECONDS;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.storage.CachingStorageIntegration;
import org.apache.polaris.core.storage.CredentialVendingContext;
import org.apache.polaris.core.storage.LocationGrant;
import org.apache.polaris.core.storage.PolarisStorageActions;
import org.apache.polaris.core.storage.StorageAccessConfig;
import org.apache.polaris.core.storage.StorageAccessProperty;
import org.apache.polaris.core.storage.StorageUri;
import org.apache.polaris.core.storage.aws.AwsStorageConfigurationInfo;
import org.apache.polaris.core.storage.cache.StorageCredentialCache;
import org.apache.polaris.core.storage.cache.StorageCredentialCacheKey;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vends Cloudflare R2 temporary credentials for an S3 catalog whose {@code
 * credentialVendingMechanism} is {@code CLOUDFLARE_R2}, scoped to one bucket and the table's key
 * prefixes. Credentials are minted locally from the server-side parent token (see {@link
 * R2TemporaryCredentialSigner}); no call to Cloudflare is made. The token subject and audience come
 * from the catalog's validated {@code endpoint} through {@link
 * AwsStorageConfigurationInfo#getCloudflareR2Endpoint()}, the same parse the model check accepted
 * at create time.
 */
public class CloudflareR2CredentialsStorageIntegration
    extends CachingStorageIntegration<AwsStorageConfigurationInfo> {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(CloudflareR2CredentialsStorageIntegration.class);

  private static final Set<PolarisStorageActions> WRITE_ACTIONS =
      EnumSet.of(
          PolarisStorageActions.WRITE, PolarisStorageActions.DELETE, PolarisStorageActions.ALL);

  private final R2ParentTokenResolver parentTokenResolver;
  private final Clock clock;

  public CloudflareR2CredentialsStorageIntegration(
      @NonNull R2ParentTokenResolver parentTokenResolver,
      @NonNull Clock clock,
      @Nullable StorageCredentialCache cache,
      @NonNull AwsStorageConfigurationInfo storageConfig,
      @NonNull RealmConfig realmConfig) {
    super(cache, realmConfig, storageConfig);
    this.parentTokenResolver = parentTokenResolver;
    this.clock = clock;
  }

  @Override
  protected StorageCredentialCacheKey buildCacheKey(
      @NonNull List<LocationGrant> grants,
      @NonNull Optional<String> refreshEndpoint,
      @NonNull CredentialVendingContext context) {
    AwsStorageConfigurationInfo config = storageConfig();
    R2ParentToken token =
        parentTokenResolver
            .resolve(config.getStorageName())
            .orElseThrow(() -> noParentToken(config.getStorageName(), context));
    Duration ttl = Duration.ofSeconds(realmConfig().getConfig(STORAGE_CREDENTIAL_DURATION_SECONDS));
    requireUniformScope(grants, context);
    return CloudflareR2CredentialCacheKey.of(
        context.realm().orElse(""),
        config,
        singleBucket(grants, context),
        normalizedPrefixes(grants),
        scopeFor(grants),
        token.accessKeyId(),
        R2ParentToken.fingerprint(token.secretAccessKey()),
        ttl,
        refreshEndpoint,
        token.secretAccessKey(),
        clock,
        realmConfig());
  }

  /**
   * Reports a vend attempt against a storage name the server holds no parent token for. Logs at
   * ERROR because the catalog exists but cannot vend, which an operator has to fix.
   */
  private static IllegalArgumentException noParentToken(
      @Nullable String storageName, CredentialVendingContext context) {
    LOGGER.error(
        "No Cloudflare R2 parent token for storage name {} (catalog {})",
        storageName == null ? "<default>" : storageName,
        context.catalogName().orElse("<none>"));
    return new IllegalArgumentException(R2ParentTokenResolver.missingTokenMessage(storageName));
  }

  /**
   * Mint a fresh {@link StorageAccessConfig} for the given key. Called by the cache on miss, so the
   * INFO line below is one per mint rather than one per credential request: a request served from
   * the cache logs nothing. The line names the credential's shape and never its material — no key
   * id, no secret, no session token, no JWT.
   *
   * <p>The {@code s3.*} tail is emitted from the catalog's explicit values. The model check on a
   * {@code CLOUDFLARE_R2} config guarantees an R2 endpoint, {@code pathStyleAccess=true} and {@code
   * region=auto}, so the client sees exactly the same properties as before this integration was
   * re-parented onto the S3 config.
   */
  static StorageAccessConfig compute(CloudflareR2CredentialCacheKey key) {
    AwsStorageConfigurationInfo config = key.storageConfig();
    CloudflareR2Endpoint endpoint = config.getCloudflareR2Endpoint();
    R2TemporaryCredentialSigner.Credential credential =
        R2TemporaryCredentialSigner.sign(
            new R2TemporaryCredentialSigner.Request(
                key.parentKeyId(),
                key.parentSecret(),
                endpoint.accountId(),
                endpoint.host(),
                key.bucket(),
                key.prefixes(),
                key.scope(),
                key.ttl(),
                key.clock().instant()));
    LOGGER.info(
        "Minted R2 credential: storageName={} bucket={} prefixes={} scope={} ttl={}s expiresAt={}",
        config.getStorageName() == null ? "<default>" : config.getStorageName(),
        key.bucket(),
        key.prefixes().size(),
        key.scope(),
        key.ttl().toSeconds(),
        credential.expiresAt());
    StorageAccessConfig.Builder builder = StorageAccessConfig.builder();
    builder.put(StorageAccessProperty.AWS_KEY_ID, credential.accessKeyId());
    builder.put(StorageAccessProperty.AWS_SECRET_KEY, credential.secretAccessKey());
    builder.put(StorageAccessProperty.AWS_TOKEN, credential.sessionToken());
    builder.put(
        StorageAccessProperty.AWS_SESSION_TOKEN_EXPIRES_AT_MS,
        String.valueOf(credential.expiresAt().toEpochMilli()));
    builder.put(StorageAccessProperty.AWS_ENDPOINT, String.valueOf(config.getEndpointUri()));
    builder.put(
        StorageAccessProperty.AWS_PATH_STYLE_ACCESS, String.valueOf(config.getPathStyleAccess()));
    builder.put(StorageAccessProperty.CLIENT_REGION, String.valueOf(config.getRegion()));
    key.refreshCredentialsEndpoint()
        .ifPresent(e -> builder.put(StorageAccessProperty.AWS_REFRESH_CREDENTIALS_ENDPOINT, e));
    return builder.build();
  }

  /**
   * The single bucket all grants refer to; an R2 temporary credential binds to exactly one. Names
   * the table in the failure message whenever the context carries one, because the fix is to give
   * that table a location layout confined to one bucket.
   */
  static String singleBucket(List<LocationGrant> grants, CredentialVendingContext context) {
    Set<String> buckets = new TreeSet<>();
    for (LocationGrant grant : grants) {
      for (String location : grant.locations()) {
        String bucket = StorageUri.parse(location).authority();
        if (bucket == null) {
          throw new IllegalArgumentException(
              "R2 location '"
                  + location
                  + "'"
                  + forTable(context)
                  + " names no bucket; R2 locations must be s3://<bucket>/<prefix>");
        }
        buckets.add(bucket);
      }
    }
    if (buckets.size() != 1) {
      throw new IllegalArgumentException(
          "R2 credentials are scoped to one bucket, but the grants"
              + forTable(context)
              + " span "
              + buckets
              + "; use one bucket per table");
    }
    return buckets.iterator().next();
  }

  /**
   * {@code " for table <catalog>.<namespace>.<table>"} when the context identifies a table, else
   * the empty string.
   */
  private static String forTable(CredentialVendingContext context) {
    return context
        .tableName()
        .map(
            table ->
                " for table "
                    + Stream.of(context.catalogName(), context.namespace(), Optional.of(table))
                        .flatMap(Optional::stream)
                        .collect(Collectors.joining(".")))
        .orElse("");
  }

  /**
   * Key prefixes for the {@code paths.prefixPaths} claim, deduplicated and sorted. A prefix is the
   * grant location's raw path minus exactly one leading slash, with one trailing slash added only
   * when the path has none.
   *
   * <p>The prefix must stay byte-identical to the path the location validators compared, because
   * those validators do not normalize: {@code S3Location.isChildOf} and the table-overlap check are
   * raw {@code startsWith} comparisons. Doubled slashes are therefore preserved and never
   * collapsed, and trailing slashes are never trimmed. Collapsing {@code bucket//sibling/} to
   * {@code sibling/} would hand out a credential over the real {@code sibling/} table, which
   * Polaris never authorized.
   *
   * <p>An empty result scopes the credential to the whole bucket, so it is returned only when a
   * grant is literally at bucket root ({@code s3://bucket} or {@code s3://bucket/}) — a path that
   * is empty after one leading slash comes off.
   *
   * <p>Combining several grants unions their locations: every location under the single bucket
   * contributes a prefix, whatever actions its own grant carries.
   */
  static List<String> normalizedPrefixes(List<LocationGrant> grants) {
    TreeSet<String> prefixes = new TreeSet<>();
    for (LocationGrant grant : grants) {
      for (String location : grant.locations()) {
        String path = StorageUri.parse(location).rawPath();
        if (path.startsWith("/")) {
          path = path.substring(1);
        }
        if (path.isEmpty()) {
          return List.of();
        }
        prefixes.add(path.endsWith("/") ? path : path + "/");
      }
    }
    return List.copyOf(prefixes);
  }

  /**
   * Rejects a grant list that mixes read-only grants with write-capable ones. R2 attaches one scope
   * to the whole credential, so such a list can only be served by widening the read-only prefixes
   * to read-write, which hands out more than Polaris authorized. One grant per vend is the only
   * shape the callers produce today, but the SPI accepts a list, so fail closed rather than widen.
   */
  private static void requireUniformScope(
      List<LocationGrant> grants, CredentialVendingContext context) {
    if (grants.size() < 2) {
      return;
    }
    long writeCapable =
        grants.stream()
            .filter(grant -> grant.actions().stream().anyMatch(WRITE_ACTIONS::contains))
            .count();
    if (writeCapable != 0 && writeCapable != grants.size()) {
      throw new IllegalArgumentException(
          "R2 credentials carry one scope for all prefixes; grants with mixed read-only and write"
              + " actions cannot be vended together"
              + forTable(context));
    }
  }

  /**
   * The single R2 scope covering all grants. R2 attaches one scope to the whole credential, so a
   * WRITE, DELETE or ALL action yields {@code object-read-write} for every prefix; only an all-read
   * set yields {@code object-read-only}.
   *
   * <p>{@link #requireUniformScope} runs first, so this widening applies only within one grant, or
   * across grants that are uniformly write-capable. A list that mixes a read-only grant with a
   * write-capable one never reaches here.
   */
  static String scopeFor(List<LocationGrant> grants) {
    boolean write =
        grants.stream().flatMap(g -> g.actions().stream()).anyMatch(WRITE_ACTIONS::contains);
    return write
        ? R2TemporaryCredentialSigner.SCOPE_OBJECT_READ_WRITE
        : R2TemporaryCredentialSigner.SCOPE_OBJECT_READ_ONLY;
  }
}
