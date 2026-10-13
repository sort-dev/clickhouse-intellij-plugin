# SQL Dialect for ClickHouse

ClickHouse SQL editing for Database Tools by Sortdev SRL. Apache-2.0.
Choose the **ClickHouse (sort.dev)** dialect for a `.sql` file, or its datasource
driver for consoles. Language id `ClickHouseSQL`, DBMS `CLICKHOUSE_BRIKK`.
Native introspection, DDL, types and server error handling come from stock ClickHouse.

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
There is no native validator; generated SQL has no engine verdict, never Accepted.
ClickHouse `{name:Type}` parameters pass through unchanged for the server;
IDE `:name` binds are prompted and substituted before lowering.
Unsupported CALL, NATURAL JOIN, STRAIGHT_JOIN, star RENAME, PIVOT and UNPIVOT
are refused or blocked rather than submitted.
