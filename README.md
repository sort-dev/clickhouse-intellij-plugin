# SQL Dialect for ClickHouse

ClickHouse SQL editing for Database Tools by Sortdev SRL. Apache-2.0.
Choose the **ClickHouse (sort.dev)** dialect for a `.sql` file, or its datasource
driver for consoles. Language id `ClickHouseSQL`, DBMS `CLICKHOUSE_BRIKK`.
Native introspection, DDL, types and server error handling come from stock ClickHouse.
The driver uses ClickHouse's [documented JDBC facade](https://clickhouse.com/docs/en/integrations/language-clients/java/jdbc),
`com.clickhouse.jdbc.ClickHouseDriver`, rather than choosing a V1/V2 implementation
itself. Database Tools manages the stock `ClickHouse` artifact's version and
packaging; no separate JDBC implementation is bundled or pinned by this plugin.

The lexer masks the unsupported PARTITION BY clause of INSERT INTO FUNCTION so
the owner-shaped s3 export keeps structured, error-free stock PSI as one statement.
Core ranges bound raw FORMAT data. Non-Values data ends at a blank line, a line
holding only `;`, or EOF; a semicolon inside data is data. CSV quoting follows
settings written in that INSERT, not invisible session settings.

Build with `./gradlew buildPlugin check test262 test263`. Compiles on 261;
runtime lanes cover 261/262/263. Core/metadata 0.20.0-SNAPSHOT and
support/test-support 0.2.0-SNAPSHOT resolve exclusively from Maven Local.
Build conventions are vendored with
`../intellij-sql-plugin-gradle-conventions/sync-into.sh "$PWD"`.

Completion uses ClickHouse 26.5.1.1 metadata. Functions retain catalog case;
keywords insert UPPER. Missing function signatures are not guessed.
Enable **ClickHouse PIPE support** under Settings > Tools > ClickHouse PIPE.
PIPE defaults off; `-Dclickhouse.pipes=false` vetoes it. SELECT-first and FROM-first
pipelines use shared Execute, preview, run-to-stage, completion and coloring.
PIPE execution still has no engine verdict; its editor syntax is checked locally.
ClickHouse `{name:Type}` parameters pass through unchanged for the server;
IDE `:name` binds are prompted and substituted before lowering.
IDE `:name` inside masked `PARTITION BY` is prompted; such statements use lenient PSI and lose structured INSERT/SELECT nodes until clause-level parsing is available.
Unsupported CALL, NATURAL JOIN, STRAIGHT_JOIN, star RENAME, PIVOT and UNPIVOT
are refused or blocked rather than submitted.

Local PSI/core checks run first. While the file's data source is connected, a
separate helper asks its server to parse SQL using `formatQuery` over hex data.
It runs no startup script and never executes, binds or reads the submitted SQL;
no chDB is bundled. Server acceptance clears local false positives in that unit;
unavailable verification preserves local errors. IDE binds are masked at equal
length; typed ClickHouse parameters stay unchanged. Compression is disabled on
helpers, and `ignore_unknown_config_key=true` tolerates the IDE's `session_id`.
With the 0.9.8 JDBC driver, readonly=1 logins can yield Unavailable because the
driver sends settings the server forbids; this never means the SQL is clean.
New local core diagnostics can flag a destination column list after PARTITION BY;
the server's acceptance clears that local false positive in the verified statement.
Where IDE binds cannot be masked with certainty, verification gives no verdict
and keeps local errors; uncertain text is never sent to the server.

The real-server IDE test runs only with `BRIKK_CLICKHOUSE_URL`. Start the task-only
server with the library's `tools/clickhouse-verify-server.sh up` from this checkout,
set the URL to `jdbc:clickhouse://127.0.0.1:18123/default` and
`BRIKK_CLICKHOUSE_CONTAINER=brikk-ch-verify`, then run `ClickHouseServerVerificationTest`.
The test uses synthetic logins, checks the query log and verifies no output file
was written. Finish with the script's `down` command. Driver fixtures stay in `build/`.
