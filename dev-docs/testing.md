# Test fixtures

## Brikk snapshot candidates

This checkout overrides the vendored catalog with SQL `0.22.0-SNAPSHOT` and
IntelliJ support `0.2.5-SNAPSHOT`. The shared conventions resolve Brikk snapshots
from Maven Local. Install the candidates there before building, and compare their
JAR hashes with the published candidates at
`https://central.sonatype.com/repository/maven-snapshots/`.

The explicit strict dependency versions prevent stable transitive SQL dependencies
from replacing the snapshots during conflict resolution. Keep those constraints
aligned with the catalog overrides in `settings.gradle.kts`.

```sh
./gradlew test test262 test263 verifyDistribution verifyBundledSnapshotHashes --refresh-dependencies
./gradlew test test262 test263 -Ptest.kotlin=absent
./gradlew verifyPlugin
```

`verifyBundledSnapshotHashes` compares the actual plugin ZIP with Maven Local.
Snapshot builds are for testing only; the release guard rejects them. Replace the
candidate versions with the verified stable release and update the third-party
notices before releasing. Remove the overrides only after the shared catalog pins
that release. Do not modify the vendored build conventions or their catalog.

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
