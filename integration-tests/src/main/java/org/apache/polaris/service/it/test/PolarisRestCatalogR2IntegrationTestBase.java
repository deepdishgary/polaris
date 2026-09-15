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
package org.apache.polaris.service.it.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.responses.LoadTableResponse;
import org.apache.polaris.core.admin.model.AwsStorageConfigInfo;
import org.apache.polaris.core.admin.model.Catalog;
import org.apache.polaris.core.admin.model.CatalogProperties;
import org.apache.polaris.core.admin.model.PolarisCatalog;
import org.apache.polaris.core.admin.model.StorageConfigInfo;
import org.apache.polaris.service.it.env.ClientPrincipal;
import org.apache.polaris.service.it.env.PolarisClient;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Runs the shared REST catalog suite against a real Cloudflare R2 bucket through an S3 catalog
 * whose {@code credentialVendingMechanism} is {@code CLOUDFLARE_R2}. Enabled by the cloudTest
 * subclass only when the INTEGRATION_TEST_R2_* environment is present.
 *
 * <p>The server under test needs polaris.storage.cloudflare-r2.access-key / secret-key. Supply the
 * parent token through the environment (POLARIS_STORAGE_CLOUDFLARE_R2_ACCESS_KEY,
 * POLARIS_STORAGE_CLOUDFLARE_R2_SECRET_KEY), never through a Quarkus config override: the
 * integration-test launcher turns overrides into command-line arguments and prints them.
 */
public abstract class PolarisRestCatalogR2IntegrationTestBase
    extends PolarisRestCatalogIntegrationBase {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(PolarisRestCatalogR2IntegrationTestBase.class);

  public static final String BASE_LOCATION = System.getenv("INTEGRATION_TEST_R2_PATH");
  public static final String ACCOUNT_ID = System.getenv("INTEGRATION_TEST_R2_ACCOUNT_ID");
  public static final String JURISDICTION = System.getenv("INTEGRATION_TEST_R2_JURISDICTION");

  private String principalRoleName;

  @Override
  protected ClientPrincipal createTestPrincipal(
      PolarisClient client, String principalName, String principalRoleName) {
    // Remembered so a test can create and administer a second catalog for the same principal.
    this.principalRoleName = principalRoleName;
    return super.createTestPrincipal(client, principalName, principalRoleName);
  }

  @Override
  protected StorageConfigInfo getStorageConfigInfo() {
    return cloudflareR2StorageConfig(endpoint(ACCOUNT_ID), List.of(BASE_LOCATION));
  }

  static AwsStorageConfigInfo cloudflareR2StorageConfig(
      String endpoint, List<String> allowedLocations) {
    return AwsStorageConfigInfo.builder(StorageConfigInfo.StorageTypeEnum.S3)
        .setCredentialVendingMechanism("CLOUDFLARE_R2")
        .setEndpoint(endpoint)
        .setPathStyleAccess(true)
        .setRegion("auto")
        .setAllowedLocations(allowedLocations)
        .build();
  }

  private static String endpoint(String accountId) {
    String host =
        JURISDICTION == null || JURISDICTION.isEmpty()
            ? accountId + ".r2.cloudflarestorage.com"
            : accountId + "." + JURISDICTION + ".r2.cloudflarestorage.com";
    return "https://" + host;
  }

  /** Tests that write to storage directly need the R2 endpoint and the parent token. */
  @Override
  protected ImmutableMap.Builder<String, String> clientFileIOProperties() {
    return super.clientFileIOProperties()
        .put("s3.endpoint", endpoint(ACCOUNT_ID))
        .put("s3.path-style-access", "true")
        .put("client.region", "auto")
        .put("s3.access-key-id", requiredEnv("POLARIS_STORAGE_CLOUDFLARE_R2_ACCESS_KEY"))
        .put("s3.secret-access-key", requiredEnv("POLARIS_STORAGE_CLOUDFLARE_R2_SECRET_KEY"));
  }

  /**
   * Reads an environment variable the R2 suite cannot run without. Fails with the variable name
   * rather than letting a null reach the caller.
   */
  private static String requiredEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isEmpty()) {
      throw new IllegalStateException(
          "Environment variable " + name + " must be set to run the R2 integration tests");
    }
    return value;
  }

  private static S3Client clientFor(Map<String, String> vended) {
    return S3Client.builder()
        .endpointOverride(java.net.URI.create(vended.get("s3.endpoint")))
        .region(Region.of("auto"))
        .forcePathStyle(true)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsSessionCredentials.create(
                    vended.get("s3.access-key-id"),
                    vended.get("s3.secret-access-key"),
                    vended.get("s3.session-token"))))
        .build();
  }

  /** A client authenticated with the parent token itself, for test fixtures Polaris must find. */
  private static S3Client parentTokenClient() {
    return S3Client.builder()
        .endpointOverride(java.net.URI.create(endpoint(ACCOUNT_ID)))
        .region(Region.of("auto"))
        .forcePathStyle(true)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                    requiredEnv("POLARIS_STORAGE_CLOUDFLARE_R2_ACCESS_KEY"),
                    requiredEnv("POLARIS_STORAGE_CLOUDFLARE_R2_SECRET_KEY"))))
        .build();
  }

  /**
   * Splits an s3:// location into bucket and key prefix. A location with no path has an empty key.
   */
  private static String[] bucketAndKey(String location, String suffix) {
    String noScheme = location.substring(location.indexOf("://") + 3);
    int slash = noScheme.indexOf('/');
    String bucket = slash < 0 ? noScheme : noScheme.substring(0, slash);
    String key = slash < 0 ? "" : noScheme.substring(slash + 1);
    if (!key.isEmpty() && !key.endsWith("/")) {
      key = key + "/";
    }
    return new String[] {bucket, key + suffix};
  }

  /** A table's vended credential must not be able to write under a sibling table's prefix. */
  @Test
  public void vendedCredentialIsScopedToItsTablePrefix() {
    Namespace ns = Namespace.of("r2scope");
    catalog().createNamespace(ns);
    TableIdentifier a = TableIdentifier.of(ns, "table_a");
    TableIdentifier b = TableIdentifier.of(ns, "table_b");
    catalog().createTable(a, SCHEMA);
    catalog().createTable(b, SCHEMA);

    LoadTableResponse loadedA =
        catalogApi().loadTableWithAccessDelegation(currentCatalogName(), a, "all");
    LoadTableResponse loadedB =
        catalogApi().loadTableWithAccessDelegation(currentCatalogName(), b, "all");
    assertThat(loadedA.config()).containsKey("s3.session-token");

    try (S3Client asA = clientFor(loadedA.config())) {
      String[] ownTarget = bucketAndKey(loadedA.tableMetadata().location(), "probe.txt");
      asA.putObject(
          PutObjectRequest.builder().bucket(ownTarget[0]).key(ownTarget[1]).build(),
          RequestBody.fromString("ok"));

      try {
        String[] otherTarget = bucketAndKey(loadedB.tableMetadata().location(), "probe.txt");
        assertThatThrownBy(
                () ->
                    asA.putObject(
                        PutObjectRequest.builder()
                            .bucket(otherTarget[0])
                            .key(otherTarget[1])
                            .build(),
                        RequestBody.fromString("nope")))
            .isInstanceOf(S3Exception.class)
            .satisfies(e -> assertThat(((S3Exception) e).statusCode()).isEqualTo(403));
      } finally {
        // Leave the bucket as we found it, whether or not the assertion above held.
        asA.deleteObject(
            DeleteObjectRequest.builder().bucket(ownTarget[0]).key(ownTarget[1]).build());
      }
    }
  }

  /**
   * A catalog whose endpoint names a well-formed account id that is not the parent token's account
   * passes creation (nothing touches R2 at create) and cannot complete a registration: the server's
   * own first vended access, its read of the metadata file during {@code registerTable}, is signed
   * for the foreign account and addressed to that account's host. The metadata file is written
   * first with the parent token, so a missing object cannot be the cause and a 404 is rejected; an
   * implementation that signed for and addressed the real account by mistake would make the
   * register call succeed and this test fail. Observed against real R2 on 2026-09-11: Cloudflare
   * serves no TLS for an unknown account host, so the S3 client fails in the TLS handshake and R2
   * never evaluates the token. The case therefore proves that a wrong endpoint reaches no data. It
   * does not prove that R2 rejects a token signed for a foreign account; that needs a second real
   * account. The failure text is logged as evidence and no error code is pinned.
   */
  @Test
  public void foreignEndpointPassesCreateAndCannotCompleteRegistration() {
    String foreignCatalogName = currentCatalogName() + "_foreign";
    String foreignBase = BASE_LOCATION + "/foreign-account/" + currentCatalogName();
    Catalog foreign =
        PolarisCatalog.builder()
            .setType(Catalog.TypeEnum.INTERNAL)
            .setName(foreignCatalogName)
            .setProperties(new CatalogProperties(foreignBase))
            .setStorageConfigInfo(
                cloudflareR2StorageConfig(
                    endpoint(foreignAccountId(ACCOUNT_ID)), List.of(foreignBase)))
            .build();
    managementApi().createCatalog(principalRoleName, foreign);
    assertThat(managementApi().getCatalog(foreignCatalogName).getStorageConfigInfo())
        .isInstanceOf(AwsStorageConfigInfo.class);
    catalogApi().createNamespace(foreignCatalogName, "ns");

    // A real, readable metadata file in the real account, at the location the register names.
    String tableLocation = foreignBase + "/ns/t";
    String metadataLocation = tableLocation + "/metadata/00000-foreign.metadata.json";
    String[] metadataObject =
        bucketAndKey(tableLocation + "/metadata", "00000-foreign.metadata.json");
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            SCHEMA, PartitionSpec.unpartitioned(), tableLocation, Map.of());
    try (S3Client asParent = parentTokenClient()) {
      asParent.putObject(
          PutObjectRequest.builder().bucket(metadataObject[0]).key(metadataObject[1]).build(),
          RequestBody.fromString(TableMetadataParser.toJson(metadata)));
      try {
        Throwable failure =
            catchThrowable(
                () ->
                    catalogApi()
                        .registerTable(
                            foreignCatalogName, Namespace.of("ns"), "t", metadataLocation, false));
        assertThat(failure)
            .as(
                "a credential signed for and addressed to a foreign account must not complete the"
                    + " registration")
            .isNotNull();
        assertThat(failure)
            .as("the object exists, so the failure cannot be a missing file")
            .hasMessageNotContaining("404")
            .hasMessageNotContaining("NoSuchKey");
        LOGGER.info(
            "Registration against a foreign-account endpoint failed at the server's first vended"
                + " access: {}",
            failure.toString());
      } finally {
        // Leave the bucket as we found it, whether or not the assertions above held.
        asParent.deleteObject(
            DeleteObjectRequest.builder().bucket(metadataObject[0]).key(metadataObject[1]).build());
      }
    }
  }

  /**
   * Flips the first hex digit, so the id stays 32 lowercase hex characters but names no account.
   */
  static String foreignAccountId(String accountId) {
    char flipped = accountId.charAt(0) == '0' ? '1' : '0';
    return flipped + accountId.substring(1);
  }
}
