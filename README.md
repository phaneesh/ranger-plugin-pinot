<!-- Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Ranger Plugin for Apache Pinot

An [Apache Ranger](https://ranger.apache.org/) authorization plugin for [Apache Pinot](https://pinot.apache.org/) 1.4.x
and 1.5.x. Define one set of table-level access policies in Ranger Admin — ACL, tag-based, and row-level security — and
have them enforced consistently on **both** of Pinot's authorization surfaces: the **broker** (query-time table ACL +
row filtering) and the **controller** (admin REST API, table CRUD, cluster-level actions), with audit trails and
fail-closed behavior on policy-fetch failure.

Built on Ranger's own plugin framework (`RangerBasePlugin`, `RangerPluginClassLoader` shim isolation) and modeled on
Ranger's Hive, Kafka, and Presto plugins — same service-def structure, same install flow, same policy semantics a Ranger
admin already knows.

## Features

- **Broker authorization** — per-table ACL on every query, via Pinot's `pinot.broker.access.control.class` hook
- **Controller authorization** — table-scoped CRUD plus `FineGrainedAccessControl` enforcement of cluster-level admin
  actions (`Get*`, `Create*`, `Delete*`, ... 130+ access types covering Pinot's REST API vocabulary)
- **Row-level security (RLS)** — Ranger row-filter policies compiled to SQL predicates and applied per user/table
  through the broker's native `getRowColFilters` hook
- **Tag-based policies** — Ranger tag service support: policy tags like `PII` or `sensitive` apply to tables without
  per-table ACLs
- **Audit logging** — every authorization decision emitted to log4j and (optionally) Solr/HDFS destinations, matching
  Ranger's audit format
- **Fail-closed** — a policy-fetch failure denies access rather than allowing it
- **Policy refresh** — 30s polling with a local policy cache fallback, so a broker/controller restarts even when Ranger
  Admin is down
- **One build, two versions** — the relevant Pinot SPI interfaces are byte-identical across 1.4.x, 1.5.x, and master; a
  single plugin build works on both

Column masking is not supported: Pinot 1.4/1.5's broker SPI exposes no masking channel (see
`broker/RangerPinotAccessControl#getRowColFilters`).

## Requirements

- Pinot **1.4.x** or **1.5.x** (running on JDK 11+)
- Ranger Admin **2.8.x**
- JDK 17 and Maven 3.9+ (to build from source)
- Docker (only for the integration-test harness)

## Build

```bash
mvn clean verify
```

The distributable plugin tarball lands at:

```text
ranger-pinot-plugin-distro/target/ranger-<version>-pinot-plugin.tar.gz
```

Releases are published automatically: pushing a version tag (`v1.0.0`) triggers
the [release workflow](.github/workflows/release.yml), which builds and attaches the tarball to a GitHub Release.

## Installation

These steps install the plugin into an existing Pinot deployment. If you built from source, extract
`ranger-<version>-pinot-plugin.tar.gz` first.

### 1. Prepare Ranger Admin

Before configuring the Pinot side, the service definition must exist in Ranger Admin and a Pinot service instance must
be created:

1. **Load the service definition** (if your Ranger Admin doesn't already have `pinot`):

   ```bash
   curl -u admin:password -X POST \
     -H "Content-Type: application/json" \
     -d @ranger-pinot-plugin/src/main/resources/service-defs/ranger-servicedef-pinot.json \
     "http://<ranger-admin>:6080/service/plugins/definitions"
   ```

2. **Create a service** in the Ranger Admin UI (Access Manager → Pinot → Add New Service), e.g. `pinotdev`. Fill in the
   service configs: `username`/`password` and `controller.url` (used for Test Connection and table-name autocomplete).
   Author your policies against this service name.

### 2. Configure install.properties

Edit `install.properties` in the extracted plugin directory:

| Property                             | Required | Description                                                                               |
|--------------------------------------|----------|-------------------------------------------------------------------------------------------|
| `POLICY_MGR_URL`                     | ✓       | Ranger Admin URL, e.g. `http://ranger.example.com:6080`                                   |
| `REPOSITORY_NAME`                    | ✓       | Ranger service name created above (e.g. `pinotdev`)                                       |
| `COMPONENT_INSTALL_DIR_NAME`         |          | Path to the Pinot install (`../pinot` by default, absolute or relative to the plugin dir) |
| `PINOT_BROKER_CONF_DIR`              |          | Broker conf dir; defaults to `$PINOT_HOME/conf`                                           |
| `PINOT_CONTROLLER_CONF_DIR`          |          | Controller conf dir; defaults to `$PINOT_HOME/conf`                                       |
| `POLICY_CACHE_FILE_PATH`             |          | Policy cache dir; defaults to `/etc/ranger/<repo>/policycache`                            |
| `PINOT_ENABLE_ROW_COLUMN_LEVEL_AUTH` |          | Enable broker row-level security; `true` by default                                       |
| `XAAUDIT.*`                          |          | Audit destinations (log4j enabled by default; Solr/HDFS disabled)                         |
| `SSL_KEYSTORE_FILE_PATH` etc.        |          | Keystore/truststore for 2-way SSL to Ranger Admin                                         |

### 3. Enable the plugin

Run from the extracted plugin directory (idempotent — safe to re-run):

```bash
./enable-pinot-plugin.sh
```

This:

1. Copies and renders the Ranger config XMLs (`ranger-pinot-security.xml`, `ranger-pinot-audit.xml`,
   `ranger-policymgr-ssl.xml`) into both the broker and controller conf dirs, resolving `%PLACEHOLDER%` values from
   `install.properties`
2. Installs the plugin jars into `$PINOT_HOME/lib` — the shim + classloader on Pinot's classpath, the implementation in
   its isolated `lib/ranger-pinot-plugin-impl/` sibling directory
3. Creates the policy cache directory
4. Wires Pinot's access-control switches in the `.conf` files:
    - `pinot-broker.conf` →
      `pinot.broker.access.control.class=org.apache.ranger.authorization.pinot.authorizer.broker.RangerPinotAccessControlFactory`
      and `pinot.broker.enable.row.column.level.auth=true`
    - `pinot-controller.conf` →
      `controller.admin.access.control.factory.class=org.apache.ranger.authorization.pinot.authorizer.controller.RangerPinotAccessControlFactory`

### 4. Restart Pinot

Restart the broker and controller. On startup each loads the plugin via `RangerPluginClassLoader`, fetches policies from
Ranger Admin, and begins enforcing them. To remove the plugin:

```bash
./disable-pinot-plugin.sh
```

(then restart Pinot again).

## Configuration

After installation, runtime behavior is controlled by the rendered `ranger-pinot-security.xml` in each conf dir
(`$PINOT_HOME/conf` by default):

| Property                                                      | Default                          | Description                                                    |
|---------------------------------------------------------------|----------------------------------|----------------------------------------------------------------|
| `ranger.plugin.pinot.service.name`                            | —                                | Ranger service name (from `REPOSITORY_NAME`)                   |
| `ranger.plugin.pinot.policy.rest.url`                         | —                                | Ranger Admin URL                                               |
| `ranger.plugin.pinot.policy.source.impl`                      | `RangerAdminRESTClient`          | Policy source class                                            |
| `ranger.plugin.pinot.policy.pollIntervalMs`                   | `30000`                          | Policy refresh interval                                        |
| `ranger.plugin.pinot.policy.cache.dir`                        | `/etc/ranger/<repo>/policycache` | Local policy cache (fallback when Ranger Admin is unreachable) |
| `ranger.plugin.pinot.policy.rest.ssl.config.file`             | `ranger-policymgr-ssl.xml`       | SSL config for Ranger Admin                                    |
| `ranger.plugin.pinot.policy.rest.client.connection.timeoutMs` | `120000`                         | REST connection timeout                                        |
| `ranger.plugin.pinot.policy.rest.client.read.timeoutMs`       | `30000`                          | REST read timeout                                              |

`ranger-pinot-security.xml` must be on the classpath of both broker and controller — `RangerBasePlugin` loads it from
the classpath (the docker harness in this repo does it with `CLASSPATH_PREFIX=/opt/pinot/conf`).

Audit destinations are configured in `ranger-pinot-audit.xml`: log4j by default; Solr and HDFS destinations available
with spool-dir-based async shipping (`xasecure.audit.solr.*`, `xasecure.audit.hdfs.*`).

## Resource model and policies

The service definition (`service-defs/ranger-servicedef-pinot.json`) defines two Ranger resource types and 133 access
types:

| Resource  | Policies                                                                                                                                       |
|-----------|------------------------------------------------------------------------------------------------------------------------------------------------|
| `table`   | per-table ACLs (query, create, read, update, delete, and the table-level REST verbs), row-filter policies (`rowFilterDef`), tag-based policies |
| `cluster` | cluster-wide admin actions (GetAppConfig, CreateTenant, DeleteZnode, ...)                                                                      |

Access types cover Pinot's REST verb vocabulary — `Query`, `CreateTable`, `DeleteSegment`, `CancelQuery`,
`GetIdealState`, `CreateUser`, etc. — plus the generic `create`/`read`/`update`/`delete` that tag policies reduce to.

Policy examples:

- **Table ACL**: resource `table=myTable`, allow `query` to group `analysts` → broker allows the group's queries
- **Row filter**: `rowFilterDef` policy on `table=orders` with filter `region = 'emea'` → every query by a matching user
  is rewritten with that predicate
- **Tag policy**: tag `sensitive` on `table=customers`, deny `query` to `everyone` → applies without a per-table ACL

## Identity derivation caveat

Pinot's broker `RequesterIdentity` carries no user/groups concept; the broker-side authorizer currently derives the
principal from request headers (see `broker/RangerPinotAccessControl#deriveUser`), and the controller identifies the
user from its own auth layer. Pair the plugin with real broker-side authentication (e.g. basic auth / TLS cert in front)
for production-grade identity.

## Integration tests

The `ranger-pinot-plugin-it` module runs end-to-end tests against a real stack: docker-compose brings up Ranger Admin
(db + solr), ZooKeeper, and a derived Pinot image with the plugin pre-installed, then `PinotRangerIT` exercises
allow/deny/audit/row-filter paths.

```bash
cd ranger-pinot-plugin-it
mvn clean verify -Pintegration                      # Pinot 1.4.0
mvn clean verify -Pintegration -Dit.pinot.version=1.5.1
```

CI runs the matrix (`1.4.0`, `1.5.1`) on every push/PR ([ci.yml](.github/workflows/ci.yml)).

## Project layout

```
ranger-pinot-plugin         # impl: authorizer, service, service-def (runs in its own classloader)
ranger-pinot-plugin-shim    # thin shim: loads impl via RangerPluginClassLoader
ranger-pinot-plugin-distro  # tarball assembly, conf templates, install/enable/disable scripts
ranger-pinot-plugin-it      # docker-compose + failsafe E2E tests (CI only)
```

## License

Apache License 2.0 — see [LICENSE](LICENSE). This is an independent project, not an Apache Software Foundation product.
