# SQL Dialect for ClickHouse

ClickHouse SQL editing for Database Tools by Sortdev SRL. Apache-2.0.
Choose the **ClickHouse (sort.dev)** dialect for a `.sql` file, or its datasource
driver for consoles. Language id `ClickHouseSQL`, DBMS `CLICKHOUSE_BRIKK`.
Native introspection, DDL, types and server error handling come from stock ClickHouse.
The driver uses ClickHouse's [documented JDBC facade](https://clickhouse.com/docs/en/integrations/language-clients/java/jdbc),
`com.clickhouse.jdbc.ClickHouseDriver`, rather than choosing a V1/V2 implementation
itself. Database Tools manages the stock `ClickHouse` artifact's version and
packaging; no separate JDBC implementation is bundled or pinned by this plugin.

On connection, read-only system-catalog queries refresh scalar, aggregate and table
function names. Completion chooses live inventory, then identity-keyed disk cache,
then bundled metadata. Missing signatures are not invented. Refresh ClickHouse
Catalog updates it manually; function DDL schedules refreshes. Session facts stay
in memory, never in the disk cache. Helpers disable compression without changing
the data source. ClickHouse's client transaction flag is not treated as a real transaction.
Database switching and console namespace read-back retain stock ClickHouse behavior.
With JDBC 0.9.8, raw USE does not persist across HTTP requests; the stock namespace picker uses JDBC setSchema.
The shared one-level auto-introspection kit loads a listed database when completion
needs its children, with targeted refreshes and a per-file progress banner.
Native introspection uses the stock ClickHouse factory through a narrow connection-family
adapter, since extension fallback alone does not satisfy its DBMS identity check.
The ClickHouse dialect and model facade are registered directly for `CLICKHOUSE_BRIKK`.
Object sources use shared SHOW CREATE reads for databases, tables, views,
materialized views and dictionaries. Dictionary objects use the stock model's
Dictionary engine marker; materialized views use SHOW CREATE TABLE.

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
Kotlin is optional. Resolved `@BrikkSql` / `Sql.clickhouse` strings inject
`ClickHouseSQL` through the shared Brikk host layer, overriding the runtime's
older `BrikkSQL` annotation. Trims and interpolation offsets are preserved;
remembered pending strings use placeholders, and cold pending hosts claim without injection.
Shared relation analysis combines Brikk `Rel<T>` contracts with physical datasource
facts for column completion, resolve, diagnostics and navigation to Kotlin properties.
Brikk and pending Brikk views are inspection-only: direct Execute and run-to-stage
are blocked; preview is marked inspection-only. Execute them through the Brikk runtime.
The platform's Run query in console intention is also vetoed for these views.
Brikk strings have a bricks gutter marker. Pending markers show the typed cause
when the shared host claim carries it. Cold pending claims currently have only
a generic recognition status because the shared unavailable claim has no typed payload.
Editor action banners come only from the shared helper's `BrikkHosts.actionNeeded`.

Relation corpus v2.2 currently has no `clickhouse`-tagged cases. The ClickHouse
Brikk fixtures cover contract completion/navigation, nullable PIPE output,
hidden aliases, missing-column ranges, CTE projection and SELECT-first PIPE.
Suggested applicability additions are `ordinary-alias`, `alias-hides-slot`,
`ordinary-missing`, `cte-projection`, `pipe-where` and `select-first-pipe`.

### Working-plugin compatibility gate

Plugin Verifier 1.410 reports Compatible on DataGrip 261.24374.56 and
262.10315.132, and IDEA 262.10968.63, 263.4732.28 and 263.5701.42.
The shared adapters account for 15 internal API usages in `GenericJdbcIntrospectorGate`,
574 experimental usages and 6–7 deprecated/removal usages. The connection-interceptor
delegating stubs are documented in the shared support README. No compatibility,
structure or dependency failures were reported; these API-usage notices remain visible.

The runtime smoke gate passes with brikk-sql `12162f8` and Kotlin helper
`507fe04`. Valid INSERT FORMAT payloads no longer receive a false local-parser
error, and Kotlin relation facts link correctly. All regressions remain enabled.
Kotlin-absent lanes pass 84 tests each on 261/262/263; Kotlin-present lanes pass
102 each on 262/263. Five-plugin isolation passes 5 tests per lane and real-server
smoke passes 2 per lane on 261/262/263, with no failures, errors or skips.
Model-backed column insertion works. Table insertion is not proven by the
offline fixture, which offers no table under either this dialect or stock ClickHouse.
Real-server smoke checks live-function insertion, namespace switching, definitions
and parse-only export validation without running the submitted SQL.
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
Where IDE binds cannot be masked with certainty, verification gives no verdict
and keeps local errors; uncertain text is never sent to the server.

The real-server IDE test runs only with `BRIKK_CLICKHOUSE_URL`.
Run `./gradlew fetchClickHouseTestDriver` to fetch `clickhouse-jdbc-0.9.8-all.jar`
and `slf4j-api-2.0.7.jar` into `build/clickhouse-driver/`; neither is bundled.
Start the task-only server with the library's `tools/clickhouse-verify-server.sh up` from this checkout,
set the URL to `jdbc:clickhouse://127.0.0.1:18123/default` and
`BRIKK_CLICKHOUSE_CONTAINER=brikk-ch-verify`, then run `ClickHouseServerVerificationTest`.
The test uses synthetic logins, checks the query log and verifies no output file
was written. Finish with the script's `down` command. Driver fixtures stay in `build/`.
