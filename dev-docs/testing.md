# Test fixtures

## Shared Brikk libraries

This checkout overrides the vendored catalog with released SQL `0.23.0` and
IntelliJ support `0.4.0`, resolved from Maven Central. Strict dependency versions
keep SQL core, metadata, verification API and IntelliJ support aligned with the
overrides in `settings.gradle.kts`.

```sh
./gradlew test test262 test263 verifyDistribution --refresh-dependencies
./gradlew test test262 test263 -Ptest.kotlin=absent
./gradlew verifyPlugin
```

When testing snapshot candidates, the shared conventions resolve them only from
Maven Local. `verifyBundledSnapshotHashes` compares those JARs with the actual
plugin ZIP; it does not check released libraries. Snapshot builds are for testing
only, and the release guard rejects them. Keep the third-party notices aligned
with the resolved versions. Remove catalog overrides only after the shared catalog
pins the intended release. Do not modify the vendored build conventions or their
catalog.

## Live ClickHouse server verification

`ClickHouseServerVerificationTest` runs only when `BRIKK_CLICKHOUSE_URL` is set. It
checks that validation against a real server is parse-only: synthetic logins, query-log
inspection, and no output files written.

Setup:

```sh
./gradlew fetchClickHouseTestDriver          # fetches JDBC jars into build/clickhouse-driver/ (never bundled)
../path/to/tools/clickhouse-verify-server.sh up
export BRIKK_CLICKHOUSE_URL=jdbc:clickhouse://127.0.0.1:18123/default
export BRIKK_CLICKHOUSE_CONTAINER=brikk-ch-verify
./gradlew test --tests ClickHouseServerVerificationTest
../path/to/tools/clickhouse-verify-server.sh down
```

Cancellation and console-recovery coverage is tested with JDBC `0.10.0`. To use it
instead of the default `0.9.8` fixture, download `clickhouse-jdbc-0.10.0-all.jar`
beside the fetched `slf4j-api-2.0.7.jar` and set `BRIKK_CLICKHOUSE_DRIVER` to its
absolute path. Neither driver is bundled in the plugin. The cancellation test checks
the server's query log and the next query's own completion/errors, not the IDE's
busy flag or a late cancellation event from the previous request.

Known limitation: with the 0.9.8 JDBC driver, `readonly=1` logins can yield
`Unavailable` because the driver sends settings the server forbids; an unavailable
verdict never means the SQL is clean, and local errors are preserved.

## Local SQL corpus scan

An opt-in scan over a directory of real SQL, with no server connections:

```sh
./gradlew test -Ptest.kotlin=absent -PrealSql.dir=<directory>
```

It reads SQL without modifying it, builds a native model from the DDL found, and runs
real highlighting with and without that model, PIPE off and on. Samples, findings, logs
and IDE state stay under `build/realsql/` — delete it after review. Only counts belong
in reports; only anonymized minimal cases belong in committed tests.

## PIPE kill switch

PIPE support is opt-in (Settings > Tools > Database > ClickHouse PIPE) and can also be
vetoed with `-Dclickhouse.pipes=false`.
