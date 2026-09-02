---
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
title: Configuring Cloudflare R2 Storage
linkTitle: Configuring Cloudflare R2 Storage
type: docs
weight: 630
---

Cloudflare R2 speaks the S3 API. Polaris models it as its own storage type, `R2`, because its
credential model differs from AWS: there is no STS. Polaris mints **temporary credentials** itself
by signing a JWT with a server-side **parent token**, the path Cloudflare documents for
high-volume minting. Each vended credential is bound to one bucket and to the table's key
prefixes, and it expires after `STORAGE_CREDENTIAL_DURATION_SECONDS`.

## Enable the storage type

`R2` is opt-in. It is not in the default `SUPPORTED_CATALOG_STORAGE_TYPES`, so creating an R2
catalog returns 400 `Unsupported storage type: R2` until you add it. Enable it for the server:

```properties
polaris.features."SUPPORTED_CATALOG_STORAGE_TYPES"=["S3","AZURE","GCS","R2"]
```

This list replaces the default instead of adding to it, so name every storage type your deployment
uses. To enable R2 in one realm only, use a realm override:

```properties
polaris.features.realm-overrides."my-realm"."SUPPORTED_CATALOG_STORAGE_TYPES"=["S3","AZURE","GCS","R2"]
```

Warning: a Polaris image that does not know the `R2` type refuses to start while `R2` is in this
list. Remove `R2` from the list before you downgrade to an image built without it.

## Server configuration

Create an R2 API token in the Cloudflare dashboard with **Object Read & Write** on every bucket
your catalogs use; a temporary credential cannot exceed its parent's permissions. Give Polaris
its access key id and secret:

```properties
polaris.storage.r2.access-key=<parent access key id>
polaris.storage.r2.secret-key=<parent secret access key>
```

To use different parent tokens for different catalogs, name them and reference the name from the
catalog's `storageName`:

```properties
polaris.storage.r2.research.access-key=...
polaris.storage.r2.research.secret-key=...
```

Deliver the secret through a secret config source or environment variables
(`POLARIS_STORAGE_R2_SECRET_KEY`), not a checked-in properties file. Avoid a storage name equal to
`access-key` or `secret-key`; it would collide with the default entry. Unlike S3, R2 has no ambient
credential chain, so a static server-side parent token is the only supported path. Polaris logs a
warning at startup if only one half of the key pair is set.

A named entry has two environment-variable forms, and which one works depends on the name.
`POLARIS_STORAGE_R2_<NAME>_ACCESS_KEY` and `POLARIS_STORAGE_R2_<NAME>_SECRET_KEY` bind a storage
name that is one lowercase `[a-z0-9]+` segment: `POLARIS_STORAGE_R2_RESEARCH_ACCESS_KEY` binds
`research`. A name containing `-`, `_` or `.` does not bind through this form.
`POLARIS_STORAGE_R2_TEAM_A_ACCESS_KEY` binds none of `team-a`, `team_a` or `team.a`. Set such a
name with the property name itself as the variable name, which Kubernetes and podman both accept:

```yaml
env:
  - name: polaris.storage.r2.team-c.access-key
    value: <parent access key id>
  - name: polaris.storage.r2.team-c.secret-key
    valueFrom: { secretKeyRef: { name: r2-team-c, key: secret-key } }
```

A catalog's `storageName` is matched case-sensitively against the entry, so `RESEARCH` does not
find the entry `research`. Prefer lowercase alphanumeric storage names: they keep the underscore
form available, and a secret config source is the other way to set any name.

The parent token is separate from enabling the type: both are needed before a catalog can vend.

## Catalog configuration

```json
{
  "storageType": "R2",
  "accountId": "0123456789abcdef0123456789abcdef",
  "jurisdiction": "eu",
  "allowedLocations": ["s3://my-bucket/warehouse/"],
  "storageName": "research"
}
```

- `accountId` (required): the Cloudflare account id, 32 lowercase hex characters. The endpoint
  `https://<accountId>.r2.cloudflarestorage.com` and the audience of vended credentials derive
  from it. The `aud` claim binds a credential to the account and not to a jurisdiction host: R2
  accepts the account's default and jurisdictional hosts interchangeably. What isolates
  jurisdictions is their separate bucket namespaces, plus the credential's `bucket` claim.
- `jurisdiction` (optional): `eu`, `fedramp`, or `us`. Changes the host to
  `<accountId>.<jurisdiction>.r2.cloudflarestorage.com`. A bucket's jurisdiction is fixed at
  creation, so this field cannot be changed on an existing catalog (nor can `accountId`) unless
  `ALLOW_UNRESTRICTED_STORAGE_CONFIG_ROLE_CHANGES` is enabled. Only these three values are
  jurisdictions. A location hint such as `WEUR` or `EEUR` is not one: a bucket created with a
  location hint lives in the default jurisdiction and answers on the default endpoint. Each
  jurisdiction keeps its own bucket namespace, so one catalog covers exactly one jurisdiction.
- `allowedLocations`: `s3://` or `s3a://` locations. A catalog may span buckets, but one table's
  locations must sit in one bucket: R2 credentials cannot span buckets. Lay tables out
  bucket-per-table or bucket-per-namespace.

Catalog creation fails with 400 when the referenced parent token is not configured.

## What clients receive

With `X-Iceberg-Access-Delegation: vended-credentials`, `loadTable` returns
`s3.access-key-id` (the parent key id), `s3.secret-access-key`, `s3.session-token`,
`s3.session-token-expires-at-ms`, `s3.endpoint`, `s3.path-style-access=true`,
`client.region=auto`, and `client.refresh-credentials-endpoint`. Every client that can load a
table learns the parent key id; the secret and token are per table. For the full property reference
and client compatibility, see
[Vended Credentials Reference — Cloudflare R2]({{% ref "../../vended-credentials#cloudflare-r2" %}}).

Clients that honor `client.refresh-credentials-endpoint` never observe expiry. Clients that do not
hold a credential valid for `STORAGE_CREDENTIAL_DURATION_SECONDS` from mint time. Polaris caches
vended credentials for `min(remaining lifetime / 2, STORAGE_CREDENTIAL_CACHE_DURATION_SECONDS)`;
the cache duration must stay below the credential duration or the first vend fails.

## Diagnosing access errors

R2 checks the signed token when data is accessed, not when Polaris vends it.

| Client symptom | Likely cause |
|---|---|
| `AccessDenied` on every object | parent token lacks permission on the bucket |
| `AccessDenied` on objects under another table | expected: credentials are prefix-scoped |
| 404 `NoSuchBucket`, or 400 `NoSuchBucketException` at `createTable` | the catalog's `jurisdiction` does not match the bucket's. Buckets live in one jurisdiction's namespace, so R2 answers that a bucket from another jurisdiction does not exist. A location hint such as `WEUR` is not a jurisdiction |
| 403 `SignatureDoesNotMatch` | the credential is past its `exp`, or it was signed with a parent secret that has since been rotated, or the Polaris host's clock has skewed — Polaris hosts need NTP. R2 reports expiry as a signature error and returns no `Expired*` or `InvalidToken` code, so client retry logic must not wait for one |
| 400 `No default R2 parent token is configured` | set `polaris.storage.r2.*` or the catalog's `storageName` entry |
| 400 `R2 credentials are scoped to one bucket` | a table's locations span buckets |

## Rotating the parent token

Revoking a parent token invalidates every temporary credential derived from it immediately.
Polaris reads `polaris.storage.r2.*` at startup. To rotate: create the new token, deploy Polaris
with it, wait at least `STORAGE_CREDENTIAL_DURATION_SECONDS`, then revoke the old token.
