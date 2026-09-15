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

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the Cloudflare R2 parent token for a catalog. A catalog with a {@code storageName}
 * resolves to the named server-side entry; a catalog without one resolves to the default entry.
 * Absence means the server is not configured for that catalog, and vending must fail.
 */
@FunctionalInterface
public interface R2ParentTokenResolver {

  Optional<R2ParentToken> resolve(@Nullable String storageName);

  /** A resolver with no tokens; every lookup is empty. */
  static R2ParentTokenResolver none() {
    return storageName -> Optional.empty();
  }

  /**
   * The message for a catalog whose parent token is not configured, naming the properties an
   * operator has to set. Used at catalog create and update and at vend time, so the two sites
   * cannot drift apart.
   */
  static String missingTokenMessage(@Nullable String storageName) {
    return storageName == null
        ? "No default Cloudflare R2 parent token is configured on the server"
            + " (polaris.storage.cloudflare-r2.access-key / secret-key)"
        : "No Cloudflare R2 parent token is configured for storage name '"
            + storageName
            + "' (polaris.storage.cloudflare-r2."
            + storageName
            + ".access-key / secret-key)";
  }
}
