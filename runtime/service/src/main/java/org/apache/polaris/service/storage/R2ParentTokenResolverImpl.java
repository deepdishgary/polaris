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

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.apache.polaris.core.storage.aws.r2.R2ParentToken;
import org.apache.polaris.core.storage.aws.r2.R2ParentTokenResolver;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CDI adapter exposing {@link StorageConfiguration#cloudflareR2ParentTokenResolver()} as a bean.
 * Eager ({@link Startup}) so the half-set-token warning below is logged at boot, not on the first
 * request against a {@code CLOUDFLARE_R2} catalog.
 */
@Startup
@ApplicationScoped
public class R2ParentTokenResolverImpl implements R2ParentTokenResolver {

  private static final Logger LOGGER = LoggerFactory.getLogger(R2ParentTokenResolverImpl.class);

  private final R2ParentTokenResolver delegate;

  @Inject
  public R2ParentTokenResolverImpl(StorageConfiguration storageConfiguration) {
    this.delegate = storageConfiguration.cloudflareR2ParentTokenResolver();
    warnOnHalfSetDefaultToken(storageConfiguration.cloudflareR2());
  }

  /**
   * Warns once at startup when only one half of the default parent token is set. Both properties
   * are optional, so a typo in one of them leaves the token absent and every {@code CLOUDFLARE_R2}
   * catalog without a {@code storageName} silently unable to vend. Named entries cannot reach this
   * state: their properties are required, so a half-set named entry fails startup with SRCFG00014.
   * Only property names are logged, never values.
   */
  private static void warnOnHalfSetDefaultToken(
      StorageConfiguration.CloudflareR2StorageConfig cloudflareR2) {
    if (cloudflareR2.accessKey().isPresent() == cloudflareR2.secretKey().isPresent()) {
      return;
    }
    String missing =
        cloudflareR2.accessKey().isPresent()
            ? "polaris.storage.cloudflare-r2.secret-key"
            : "polaris.storage.cloudflare-r2.access-key";
    LOGGER.warn(
        "Ignoring the default Cloudflare R2 parent token because {} is not set. CLOUDFLARE_R2"
            + " catalogs without a storageName cannot vend credentials until both"
            + " polaris.storage.cloudflare-r2.access-key and polaris.storage.cloudflare-r2.secret-key"
            + " are set.",
        missing);
  }

  @Override
  public Optional<R2ParentToken> resolve(@Nullable String storageName) {
    return delegate.resolve(storageName);
  }
}
