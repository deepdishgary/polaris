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
title: Configuring S3 Storage
linkTitle: Configuring S3 Storage
type: docs
weight: 610
---

This page covers configuring AWS S3, and S3-compatible object stores (MinIO, Apache Ozone S3
gateway, Ceph RGW, and similar), as the storage backend for a Polaris catalog. On AWS S3, all read
and write operations are performed using credential vending: Polaris assumes a customer IAM role
via STS and returns scoped, short-lived credentials to the client. The IAM role, its trust policy,
and the bucket itself must be set up before the catalog is created.

This page is limited to native Polaris authentication. External identity providers are also
supported but are not yet covered here; the configuration patterns below remain otherwise the same.

## IAM identities involved

Three distinct IAM identities take part in the S3 credential-vending flow. Conflating them is the
most common source of `AccessDenied` errors at catalog creation or table load.

| # | Identity | Used by | Purpose |
|---|----------|---------|---------|
| 1 | Polaris service identity | Polaris server process | Sign every `sts:AssumeRole` request Polaris makes |
| 2 | Catalog access role | Polaris (assumed) | Hold the actual S3 / KMS permissions on the catalog bucket |
| 3 | Vended credentials | Iceberg client (Spark/Trino/PyIceberg) | Short-lived session keys returned by Polaris at table-load time |

At runtime the client calls Polaris to load a table, Polaris signs an `sts:AssumeRole` request
as identity 1 against identity 2, AWS STS returns scoped temporary credentials (identity 3), and
the client uses those credentials to talk to S3 and KMS directly.

Identity 1 is configured once at Polaris deployment time. Identity 2 is created per catalog and
its ARN is registered when the catalog is created. Identity 3 is generated on every table load
and is never persisted.

## Polaris service identity

The Polaris server itself needs an AWS identity to call STS on the catalog access role. This is
configured outside Polaris — through the standard AWS SDK credentials chain — and is independent
of any catalog.

Pick whichever discovery mechanism matches the deployment:

- **EKS / IRSA** — annotate the Polaris ServiceAccount with the IAM role ARN
  (`eks.amazonaws.com/role-arn`). The pod receives a projected token and exchanges it for STS
  credentials automatically.
- **EC2** — attach an IAM instance profile to the EC2 instance. The SDK reads credentials from
  IMDS.
- **Static credentials** — set `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, optionally
  `AWS_SESSION_TOKEN` and `AWS_REGION` in the Polaris container's environment. Suitable for local
  development only.

Any other AWS compute environment that participates in the standard AWS SDK credentials chain
should also work, though the patterns above are the ones we have validated.

Whichever mechanism is used, the resulting identity needs a single permission to talk to STS:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "arn:aws:iam::123456789012:role/polaris-warehouse-access"
    }
  ]
}
```

`Resource` should list every catalog access role Polaris is expected to assume; use a wildcard
(for example `arn:aws:iam::123456789012:role/polaris-*`) only when role names follow a strict
naming convention that the AWS account owner controls.

The ARN of this identity is the `Principal` that the catalog access role's trust policy must
trust — see the next section. One easy mistake is to update the catalog role's permissions
without also adding the Polaris service identity to its trust policy; the symptom is
`STS:AssumeRole` returning `AccessDenied` even though the catalog role has correct S3
permissions.

For an end-to-end deployment example that exercises this identity on EC2, see
[Deploying Polaris on AWS]({{% relref "../../getting-started/deploying-polaris/cloud-deploy/deploy-aws" %}}).

## Catalog access role and trust policy

Polaris assumes a customer-managed IAM role via STS when a client requests credentials. The role
must:

1. Grant the actions required for object access on the bucket and prefix that backs the catalog
   (`s3:GetObject`, `s3:PutObject`, `s3:DeleteObject`, `s3:ListBucket` and, if encryption is in use,
   the relevant `kms:*` actions).
2. Trust the Polaris service principal — typically the IAM role that the Polaris server runs as.
   Polaris fills the `sts:AssumeRole` request with an `externalId` when one is configured. The
   trust policy must accept the same external ID.

Using `externalId` is recommended for cross-account or hosted Polaris deployments to mitigate the
confused-deputy problem. A minimal trust policy looks like:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::123456789012:role/polaris-server" },
      "Action": "sts:AssumeRole",
      "Condition": {
        "StringEquals": { "sts:ExternalId": "polaris-prod" }
      }
    }
  ]
}
```

## Catalog storage configuration

Provide the role ARN, region, and `externalId` when creating the catalog. The token in the
`Authorization` header below is the Polaris admin bearer token obtained from
`/api/catalog/v1/oauth/tokens` (see [Configuring Polaris for Production]({{% relref "." %}}) for
how to bootstrap and issue admin tokens).

```bash
curl -X POST https://<polaris-host>/management/v1/catalogs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
        "catalog": {
          "type": "INTERNAL",
          "name": "warehouse_s3",
          "properties": { "default-base-location": "s3://warehouse-bucket/prod/" },
          "storageConfigInfo": {
            "storageType": "S3",
            "roleArn": "arn:aws:iam::123456789012:role/polaris-warehouse-access",
            "externalId": "polaris-prod",
            "region": "us-east-1"
          }
        }
      }'
```

The role ARN is validated against the pattern enforced by `AwsStorageConfigurationInfo`; an
ill-formed ARN is rejected at catalog creation time.

## Server-side encryption with KMS

When the bucket uses SSE-KMS, list the keys used for writes in `encryptionKeys`. Polaris grants
these keys both encryption and decryption permissions. List any additional keys needed to read
historical data in `decryptionKeys`. A key may appear in both lists; Polaris combines duplicate
decryption permissions. The deprecated `currentKmsKey` and `allowedKmsKeys` properties are treated
as entries in `encryptionKeys` for backward compatibility:

```json
"storageConfigInfo": {
  "storageType": "S3",
  "roleArn": "...",
  "region": "us-east-1",
  "encryptionKeys": [
    "arn:aws:kms:us-east-1:123456789012:key/aaaa-bbbb"
  ],
  "decryptionKeys": [
    "arn:aws:kms:us-east-1:123456789012:key/cccc-dddd"
  ]
}
```

The IAM role and key policies must allow encryption actions on every key in `encryptionKeys` and
decryption actions on every key in `encryptionKeys` and `decryptionKeys`.

If the deployment does not use KMS, set `kmsUnavailable` to `true` so Polaris will not request
KMS-related session permissions:

```json
"kmsUnavailable": true
```

## S3-compatible endpoints

Polaris can be pointed at S3-compatible object stores (MinIO, Ceph RGW, Apache Ozone S3 gateway).
The available fields are:

- `endpoint` — the S3 API endpoint Polaris and its clients should call.
- `endpointInternal` — optional, used by the Polaris server when the in-cluster endpoint differs
  from the one returned to clients.
- `pathStyleAccess` — set to `true` for backends that do not support virtual-host-style addressing.
- `stsEndpoint` — STS endpoint; defaults to `endpointInternal` then `endpoint` when not set.
- `stsUnavailable` — set to `true` when the backend does not implement STS.

The credential-vending guarantee at the top of this page assumes that the backend implements STS.
For AWS S3 and S3-compatible backends that expose the STS API (such as MinIO), leave
`stsUnavailable` unset (or `false`) and the vended-credentials flow described above works as is.

```json
"storageConfigInfo": {
  "storageType": "S3",
  "endpoint": "https://s3.internal.example.com",
  "pathStyleAccess": true,
  "region": "us-east-1"
}
```

For S3-compatible backends without STS (Apache Ozone S3 gateway, or Ceph RGW without STS enabled),
set `stsUnavailable: true`. Polaris will then skip subscoped credential vending entirely, and the
client must omit `X-Iceberg-Access-Delegation: vended-credentials` and authenticate to the object
store directly. The Polaris guides for [Apache Ozone][ozone-guide] and [Ceph][ceph-guide] show
this pattern end-to-end.

```json
"storageConfigInfo": {
  "storageType": "S3",
  "endpoint": "https://s3.internal.example.com",
  "pathStyleAccess": true,
  "stsUnavailable": true,
  "region": "us-east-1"
}
```

[ozone-guide]: ../../../../guides/ozone/
[ceph-guide]: ../../../../guides/ceph/

## Credential vending mechanisms

`credentialVendingMechanism` says how Polaris turns a table's locations and the requested actions into
short-lived storage credentials for an S3 catalog:

- `STS` (default): `AssumeRole` against `roleArn`, as described above.
- `CLOUDFLARE_R2`: Polaris signs Cloudflare R2 temporary-credential tokens locally with a
  server-held parent token. The catalog's `endpoint` must be an R2 endpoint,
  `https://<accountId>[.<jurisdiction>].r2.cloudflarestorage.com` (jurisdictions `eu`, `fedramp`,
  `us`), with `pathStyleAccess: true` and `region: "auto"`, and none of `roleArn`, `externalId`,
  `userArn`, `stsEndpoint`, `stsUnavailable`, `endpointInternal` or the KMS fields. The account id
  and jurisdiction are read from that validated endpoint; the endpoint is frozen after creation.
  Polaris mints each credential itself by signing a JWT with a server-held parent token, scoped to
  one bucket and the table's key prefixes and valid for `STORAGE_CREDENTIAL_DURATION_SECONDS`; no
  call to Cloudflare is made. See [Server-side parent token for
  CLOUDFLARE_R2](#server-side-parent-token-for-cloudflare_r2).

Servers may provide further mechanisms as CDI beans identified by `@Identifier`; a realm accepts
a mechanism only when its identifier appears in `SUPPORTED_S3_CREDENTIAL_VENDING_MECHANISMS`.

A realm accepts mechanisms through
`polaris.features."SUPPORTED_S3_CREDENTIAL_VENDING_MECHANISMS"` (default `["STS"]`). The list has
no implicit member: to add R2 to a realm set `["STS", "CLOUDFLARE_R2"]`, never
`["CLOUDFLARE_R2"]` alone, which would reject every plain S3 catalog. The setting is realm-level
only and cannot be widened from catalog properties. It is enforced at catalog create and update,
at catalog initialization on every request (so it has the same scope as
`SUPPORTED_CATALOG_STORAGE_TYPES`), when storage access is resolved for a table or a cleanup
task, and inside the storage integration provider.

```json
"storageConfigInfo": {
  "storageType": "S3",
  "credentialVendingMechanism": "CLOUDFLARE_R2",
  "endpoint": "https://0123456789abcdef0123456789abcdef.r2.cloudflarestorage.com",
  "pathStyleAccess": true,
  "region": "auto",
  "allowedLocations": ["s3://my-bucket/warehouse/"]
}
```

### Server-side parent token for CLOUDFLARE_R2

Create an R2 API token in the Cloudflare dashboard with **Object Read & Write** on every bucket
the catalogs use; a temporary credential cannot exceed its parent's permissions. Give Polaris its
access key id and secret:

```properties
polaris.storage.cloudflare-r2.access-key=<parent access key id>
polaris.storage.cloudflare-r2.secret-key=<parent secret access key>
```

To use different parent tokens for different catalogs, name them and reference the name from the
catalog's `storageName`:

```properties
polaris.storage.cloudflare-r2.research.access-key=...
polaris.storage.cloudflare-r2.research.secret-key=...
```

Deliver the secret through a secret config source or environment variables
(`POLARIS_STORAGE_CLOUDFLARE_R2_SECRET_KEY`), not a checked-in properties file. Avoid a storage
name equal to `access-key` or `secret-key`; it would collide with the default entry. Unlike S3,
R2 has no ambient credential chain, so a static server-side parent token is the only supported
path. Polaris logs a warning at startup if only one half of the default key pair is set; a named
entry with only one half fails startup, because both of its properties are required.

A named entry has two environment-variable forms, and which one works depends on the name.
`POLARIS_STORAGE_CLOUDFLARE_R2_<NAME>_ACCESS_KEY` and `POLARIS_STORAGE_CLOUDFLARE_R2_<NAME>_SECRET_KEY`
bind a storage name that is one lowercase `[a-z0-9]+` segment:
`POLARIS_STORAGE_CLOUDFLARE_R2_RESEARCH_ACCESS_KEY` binds `research`. A name containing `-`, `_`
or `.` does not bind through this form. Set such a name with the property name itself as the
variable name, which Kubernetes and podman both accept:

```yaml
env:
  - name: polaris.storage.cloudflare-r2.team-c.access-key
    value: <parent access key id>
  - name: polaris.storage.cloudflare-r2.team-c.secret-key
    valueFrom: { secretKeyRef: { name: r2-team-c, key: secret-key } }
```

A catalog's `storageName` is matched case-sensitively against the entry, so `RESEARCH` does not
find the entry `research`. Prefer lowercase alphanumeric storage names.

Catalog creation and update fail with 400 when the referenced parent token is not configured:
`No default Cloudflare R2 parent token is configured on the server (polaris.storage.cloudflare-r2.access-key / secret-key)`,
or the same message naming `polaris.storage.cloudflare-r2.<storageName>.*`.

### Layout rules for CLOUDFLARE_R2 catalogs

An R2 temporary credential binds to exactly one bucket. A catalog may list several buckets in
`allowedLocations`, but every location of one table (its base location, `write.data.path` and
`write.metadata.path`) must sit in one bucket; a table that spans two buckets is refused with 400
`R2 credentials are scoped to one bucket`. Lay tables out bucket-per-table or bucket-per-namespace.
Allowed locations must not contain an empty path segment (`//`).

### What CLOUDFLARE_R2 clients receive

With `X-Iceberg-Access-Delegation: vended-credentials`, `loadTable` returns
`s3.access-key-id` (the parent key id), `s3.secret-access-key`, `s3.session-token`,
`s3.session-token-expires-at-ms`, `s3.endpoint`, `s3.path-style-access=true`,
`client.region=auto`, and `client.refresh-credentials-endpoint`. Every client that can
load a table learns the parent key id; the secret and token are per table. On the wire
`s3.session-token` is the base64 encoding of `jwt/<signed JWT>`; clients pass it through
unchanged. Clients that honor `client.refresh-credentials-endpoint` can renew before expiry.
A client that ignores it holds a credential valid for only
`STORAGE_CREDENTIAL_DURATION_SECONDS` from mint time. Polaris caches vended credentials
for `min(remaining lifetime / 2, STORAGE_CREDENTIAL_CACHE_DURATION_SECONDS)`; the cache
duration must stay below the credential duration or the first vend fails.

### Diagnosing R2 access errors

R2 checks the signed token when data is accessed, not when Polaris vends it.

| Client symptom | Likely cause |
|---|---|
| `AccessDenied` on every object | parent token lacks permission on the bucket |
| `AccessDenied` on objects under another table | expected: credentials are prefix-scoped |
| 404 `NoSuchBucket`, or 400 `NoSuchBucketException` at `createTable` | the jurisdiction in the catalog's `endpoint` does not match the bucket's. Buckets live in one jurisdiction's namespace, so R2 answers that a bucket from another jurisdiction does not exist. A location hint such as `WEUR` is not a jurisdiction |
| 403 `SignatureDoesNotMatch` | the credential is past its `exp`, or it was signed with a parent secret that has since been rotated, or the Polaris host's clock has skewed; Polaris hosts need NTP. R2 reports expiry as a signature error and returns no `Expired*` or `InvalidToken` code, so client retry logic must not wait for one |
| 400 `No default Cloudflare R2 parent token is configured` | set `polaris.storage.cloudflare-r2.*` or the catalog's `storageName` entry |
| 400 `R2 credentials are scoped to one bucket` | a table's locations span buckets |
| 400 `S3 credential vending mechanism CLOUDFLARE_R2 is not enabled in this realm` | the realm's `SUPPORTED_S3_CREDENTIAL_VENDING_MECHANISMS` omits `CLOUDFLARE_R2` (the kill switch) |

### Rotating the parent token

Revoking a parent token invalidates every temporary credential derived from it immediately.
Polaris reads `polaris.storage.cloudflare-r2.*` at startup. To rotate: create the new token, deploy
Polaris with it, wait at least `STORAGE_CREDENTIAL_DURATION_SECONDS`, then revoke the old token.
Never use the Cloudflare "Roll" action on a token Polaris is using: it replaces the secret in
place, and every credential minted from the old secret fails at once.

### Upgrading and downgrading with CLOUDFLARE_R2 catalogs

A Polaris image without the `credentialVendingMechanism` field ignores it when it reads a catalog and
dispatches every S3 catalog to STS. An older instance that shares the metastore therefore
treats a `CLOUDFLARE_R2` catalog as a plain S3 catalog without a role ARN, and its vends and
cleanup tasks fail. Finish upgrading every instance that shares the metastore before you
enable `CLOUDFLARE_R2` in a realm or create a `CLOUDFLARE_R2` catalog. Existing S3 catalogs
need no migration: rows written before the field existed read as `STS`, and every write from
now on stores the field. To downgrade to an image without the field, remove or migrate every
`CLOUDFLARE_R2` catalog and let its pending tasks drain first.

### What each mechanism vends

No mechanism adds a key outside today's `s3.*` and `client.*` contract; they differ in how the
credential triple is produced.

| Key | `STS` | none (`stsUnavailable: true`) | `CLOUDFLARE_R2` |
|---|---|---|---|
| `s3.access-key-id`, `s3.secret-access-key`, `s3.session-token` | yes | no | yes |
| `s3.session-token-expires-at-ms` | when STS returns an expiration | no | yes |
| `s3.endpoint`, `s3.path-style-access`, `client.region` | from the catalog | from the catalog | from the catalog |
| `client.refresh-credentials-endpoint` | when the client asks for it | n/a (a vended-credentials request against a `stsUnavailable` catalog is a 400 at the handler) | when the client asks for it |

### Security note for locally signed credentials

A server that holds a signing token is a credential vending mechanism for that bucket set: a compromise of
the server can mint credentials up to the parent token's permissions for as long as that token
lives. This is the same class of exposure as STS-based vending, where the server holds AWS
credentials able to `AssumeRole`; the difference is that an AWS identity can be a workload identity
with automatic rotation, while a parent token is a static secret. Compensating controls: scope the
parent token to object read/write on the catalog's buckets only, never an admin permission; use
one parent token per `storageName` for each trust boundary (nothing enforces one name per catalog,
and the default entry is shared by every catalog without a name); inject the secret from a secret
store and keep it out of logs (Polaris excludes it from cache-key identity, `toString` and every
log line, but it stays in server memory); rotate by creating the successor, deploying it, waiting
one credential lifetime and revoking the predecessor; use the realm allowlist to stop issuance, and
revoke the parent token at the vendor if the secret is stolen, since the allowlist does nothing
against a stolen secret; and reject any credential scope the vendor cannot represent rather than
widening it.

## Client configuration

Engines connect through the Iceberg REST API and let Polaris vend credentials at table-load time;
they do not need static AWS credentials when STS is available.

Spark example, matching the property names used by the existing MinIO / RustFS guides:

This example follows the existing MinIO and RustFS guides for the property names. At the time of writing the authors have verified PyIceberg, DuckDB and Iceberg Java clients against R2 with vended credentials; Spark and Trino have not been run.

```shell
bin/spark-sql \
    --packages org.apache.iceberg:iceberg-spark-runtime-3.5_2.12:1.10.1,org.apache.iceberg:iceberg-aws-bundle:1.10.1 \
    --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
    --conf spark.sql.catalog.polaris=org.apache.iceberg.spark.SparkCatalog \
    --conf spark.sql.catalog.polaris.type=rest \
    --conf spark.sql.catalog.polaris.uri=https://<polaris-host>/api/catalog \
    --conf spark.sql.catalog.polaris.oauth2-server-uri=https://<polaris-host>/api/catalog/v1/oauth/tokens \
    --conf spark.sql.catalog.polaris.token-refresh-enabled=false \
    --conf spark.sql.catalog.polaris.warehouse=warehouse_s3 \
    --conf spark.sql.catalog.polaris.scope=PRINCIPAL_ROLE:ALL \
    --conf spark.sql.catalog.polaris.credential=<client-id>:<client-secret> \
    --conf spark.redaction.regex='(?i)secret|password|token|access[.]?key|credential' \
    --conf spark.sql.catalog.polaris.header.X-Iceberg-Access-Delegation=vended-credentials
```

The `spark.redaction.regex` line redacts the `credential` secret from the Spark UI and logs, since
Spark's default redaction pattern does not cover `credential`. Newer Spark releases redact this key
by default; the line keeps it redacted on earlier versions.

The `oauth2-server-uri` is recommended: without it the Iceberg REST client falls back to a
hard-coded `/v1/oauth/tokens` path and logs a deprecation warning, since the automatic fallback
is slated for removal in a future Iceberg release.

For Trino, use the Iceberg connector with the REST catalog. The REST/OAuth2 properties talk to
Polaris, and Polaris vends the endpoint, path-style flag, and region together with the scoped
credentials (`s3.endpoint`, `s3.path-style-access`, `client.region` in the load-table response),
so they do not need to be repeated on the client. The native S3 filesystem still has to be
enabled on the Trino side:

```properties
connector.name=iceberg
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=https://<polaris-host>/api/catalog
iceberg.rest-catalog.warehouse=warehouse_s3
iceberg.rest-catalog.security=OAUTH2
iceberg.rest-catalog.oauth2.credential=<client-id>:<client-secret>
iceberg.rest-catalog.oauth2.scope=PRINCIPAL_ROLE:ALL
iceberg.rest-catalog.oauth2.server-uri=https://<polaris-host>/api/catalog/v1/oauth/tokens
iceberg.rest-catalog.vended-credentials-enabled=true
fs.native-s3.enabled=true
```

For PyIceberg, use the `rest` catalog type. The same Polaris-side properties (`uri`, `warehouse`,
`credential`, `scope`, `oauth2-server-uri`) apply, and the vended-credential header must be
forwarded as a REST header:

```python
from pyiceberg.catalog.rest import RestCatalog

cat = RestCatalog(
    name="polaris",
    **{
        "uri": "https://<polaris-host>/api/catalog",
        "warehouse": "warehouse_s3",
        "credential": "<client-id>:<client-secret>",
        "scope": "PRINCIPAL_ROLE:ALL",
        "oauth2-server-uri": "https://<polaris-host>/api/catalog/v1/oauth/tokens",
        "header.X-Iceberg-Access-Delegation": "vended-credentials",
    },
)
```

Polaris returns the vended S3 properties (`s3.access-key-id`, `s3.secret-access-key`,
`s3.session-token`) to the client at table-load time, so static credentials should not be
configured on the PyIceberg side.

## Verifying the setup

A successful end-to-end test should be possible without giving the client any long-lived AWS
credentials:

```sql
CREATE NAMESPACE warehouse_s3.demo;
CREATE TABLE warehouse_s3.demo.t (id BIGINT, name STRING) USING iceberg;
INSERT INTO warehouse_s3.demo.t VALUES (1, 'hello');
SELECT * FROM warehouse_s3.demo.t;
```

If `INSERT` or `SELECT` fails with a 403, the most common causes are:

- The IAM role's trust policy does not match the `roleArn` / `externalId` Polaris is presenting.
- The role grants S3 permissions but is missing the required KMS actions for `encryptionKeys` or
  `decryptionKeys`.
- The bucket policy denies access from outside a specific VPC endpoint.

Polaris logs the assumed-role STS request at debug level, which is the fastest way to confirm
which identity is being presented.
