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
package org.apache.polaris.service.storage;

import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import org.apache.polaris.core.config.RealmConfig;
import org.apache.polaris.core.storage.PolarisStorageIntegration;
import org.apache.polaris.core.storage.aws.AwsStorageConfigurationInfo;
import org.apache.polaris.core.storage.aws.S3CredentialVendingMechanism;
import org.apache.polaris.core.storage.aws.r2.CloudflareR2CredentialsStorageIntegration;
import org.apache.polaris.core.storage.aws.r2.R2ParentTokenResolver;
import org.apache.polaris.core.storage.cache.StorageCredentialCache;

/** Cloudflare R2 temporary credentials signed by the server with a parent token. */
@ApplicationScoped
@Identifier(S3CredentialVendingMechanism.CLOUDFLARE_R2)
public class CloudflareR2CredentialVendingMechanism implements S3CredentialVendingMechanism {

  private final R2ParentTokenResolver parentTokenResolver;
  private final Clock clock;
  private final StorageCredentialCache cache;

  @Inject
  public CloudflareR2CredentialVendingMechanism(
      R2ParentTokenResolver parentTokenResolver, Clock clock, StorageCredentialCache cache) {
    this.parentTokenResolver = parentTokenResolver;
    this.clock = clock;
    this.cache = cache;
  }

  @Override
  public PolarisStorageIntegration integrationFor(
      AwsStorageConfigurationInfo storageConfig, RealmConfig realmConfig) {
    return new CloudflareR2CredentialsStorageIntegration(
        parentTokenResolver, clock, cache, storageConfig, realmConfig);
  }
}
