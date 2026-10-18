# Test fixtures

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
