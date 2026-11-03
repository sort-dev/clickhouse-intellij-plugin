# ClickHouse support for DataGrip & IntelliJ IDEA

A plugin that adds a fuller [ClickHouse](https://clickhouse.com) SQL dialect to DataGrip and
IntelliJ IDEA Ultimate. It checks your SQL with your ClickHouse server's own parser and completes
the functions your server actually has.

> [!IMPORTANT]
> **Not affiliated with ClickHouse.** This is an independent plugin by Sortdev SRL. It is not
> developed, endorsed or sponsored by ClickHouse, Inc. "ClickHouse" is a registered trademark of
> ClickHouse, Inc., used here only to name the database this plugin works with.

## Why

The IDE's built-in ClickHouse dialect misses a lot of ClickHouse syntax and shows false errors for
valid SQL. This plugin parses more of it, checks your SQL against your own server, and keeps
completion in line with the ClickHouse version you run. The database tree, DDL and object sources
still come from the IDE's built-in ClickHouse support.

## Features

- **Checked by your ClickHouse server.** While connected, the server's own parser checks your SQL.
  Your statement is parsed, not executed, and checking it does not read your tables. When the
  server accepts a statement, local false errors go away.
- **More ClickHouse syntax** than the built-in dialect: table functions in `FROM`, `JOIN`,
  `DESCRIBE` and `INSERT`, `EXISTS` and `ALL`/`ANY` predicates, `INSERT INTO FUNCTION` exports, and
  raw `FORMAT` data. See [SQL coverage](#clickhouse-sql-coverage) for supported forms and limits.
- **Completion from your server.** Function names come from the connected server's system catalog,
  merged with the plugin's bundled function list, so completion and docs match your version.
- **ClickHouse PIPE (optional).** Write pipe-syntax queries and run them on ClickHouse. See
  [ClickHouse PIPE](#clickhouse-pipe).

## ClickHouse SQL coverage

The coverage below describes version **0.3.0**. See the
[published release notes](https://github.com/sort-dev/clickhouse-intellij-plugin/releases)
for the version you are installing.

### Versions and catalogs

Bundled function catalogs cover **26.10.2.103 stable**, **26.8.34.13 LTS** and
**26.5.1.1**. An unknown server version uses the 26.10 catalog; a known version selects
the nearest available bundled catalog, supplemented by the connected server's function inventory.
Historical functions remain available in older catalogs rather than being suggested for
current servers.

These are catalog baselines, not a requirement to run one of those exact builds. Syntax
availability and required settings still depend on your ClickHouse server.

### Syntax covered

- Queries with `SELECT`, `WITH`, subqueries, joins, aggregation, window functions and lambdas.
- ClickHouse query clauses such as `PREWHERE`, `FINAL`, `SAMPLE`, `ALL`/`ANY`/`ASOF` joins,
  `ARRAY JOIN`, `LIMIT BY` and parametric aggregates.
- Table functions in query and export positions, including `INSERT INTO FUNCTION` with
  `PARTITION BY`, typed `{name:Type}` parameters, and raw `INSERT ... FORMAT` payloads.
  Statement selection keeps inline data and embedded semicolons with their owning statement.
- Newer forms including `STREAM`, boundary `LIMIT ... AFTER ... UNTIL`, `NATURAL JOIN`,
  `AT LOCAL`, compound intervals, `remote`/`cluster` function `SETTINGS`, native `TRY_CAST`, Tuple
  aggregate combinators, and `QBit`/`MultiPoint` type syntax.
- DDL and administration forms including refreshable materialized views, projections,
  `SYSTEM` and `EXPLAIN`, with the limits below.

### Limits

Coverage is broad, not complete ClickHouse grammar or semantic validation. Recognizing a
statement does not mean every part supports completion, navigation, refactoring or type
inference. Some authorization, administration and `EXPLAIN` forms remain partial, and some
higher-order and aggregate expressions still have unknown inferred types.

DDL validation is parse-only. You can still execute DDL normally through the console, but editor
acceptance is not a migration plan or a guarantee of success. Server parser acceptance alone
does not prove table or column existence, permissions, argument types or query results.
Schema-aware assistance also depends on the schemas the IDE has introspected.

Features such as `STREAM` and experimental `LATERAL` still require a server version and settings
that permit them. No extra settings/property key-value completion is added beyond the IDE's
own suggestions. When the server cannot be reached, local checks remain active, but no server
acceptance is implied.

## Requirements

- DataGrip or IntelliJ IDEA Ultimate, **2026.1 to 2026.3**.

## Install

Download the zip from the
[Releases](https://github.com/sort-dev/clickhouse-intellij-plugin/releases) page, then use
**Settings → Plugins → ⚙ → Install Plugin from Disk…** and restart.

## Getting started

1. Create a data source with the **ClickHouse (sort.dev)** driver. Its consoles use the
   **ClickHouse (sort.dev)** dialect automatically. For `.sql` files, pick the dialect in
   **Settings → SQL Dialects**.
2. Connect the data source to turn on server-side checking and server function completion. If the
   server is unreachable, local checks still run.
3. To reload function names by hand, right-click in the editor and choose **Refresh ClickHouse
   Catalog**.

## ClickHouse PIPE

Write queries in [pipe syntax](https://cloud.google.com/bigquery/docs/pipe-syntax-guide) and run
them on ClickHouse. When you run one, the plugin converts it to plain ClickHouse SQL and sends that
to the server.

**Turn it on** in **Settings → Tools → Database → ClickHouse PIPE**. It's off by default.

What you get:

- Right-click → **ClickHouse PIPE → Preview Generated SQL** shows the SQL before you run it.
- Right-click → **ClickHouse PIPE → Run Stages up to Caret** runs the pipeline only up to the stage
  at your cursor.
- Stage completion includes supported ClickHouse joins, including `NATURAL JOIN`.

PIPE is built into this plugin and does not require SQL Transpiler. It is editor syntax converted
to ordinary SQL, not a server-side ClickHouse extension. Unsupported stages and translations
reported as lossy are refused rather than sent to the server.

## Notes

- The IDE asks for `:name` parameters and fills them in before checking. ClickHouse's typed
  `{name:Type}` parameters go to the server unchanged.

## Related plugins and tools

- [Apache Doris SQL](https://plugins.jetbrains.com/plugin/32777-sql-dialect-for-apache-doris)
  dialect for JetBrains IDEs.
- [DuckDB SQL](https://plugins.jetbrains.com/plugin/33098-sql-dialect-for-duckdb-embedded--quack-)
  dialect for JetBrains IDEs, for embedded DuckDB and Quack servers.
- [Trino SQL](https://plugins.jetbrains.com/plugin/33124-sql-dialect-for-trino) dialect for
  JetBrains IDEs.
- [SQL Transpiler](https://plugins.jetbrains.com/plugin/32900-sql-transpiler) converts SQL between
  dialects in JetBrains IDEs.
- [Trino Doris connector](https://github.com/brikk/trino-doris-connector) lets Trino query Doris.
- [Trino DuckLake catalog](https://github.com/brikk/ducklake-integrations/tree/main/jvm/trino-ducklake)
  with read and write support.
- [Trino Duckbridge](https://github.com/brikk/duckbridge) gives Trino remote access to DuckDB.

## Feedback

Found a bug or SQL the plugin gets wrong?
[Open an issue](https://github.com/sort-dev/clickhouse-intellij-plugin/issues). A small, anonymized
SQL sample helps a lot. Want to build or change the plugin? See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache License 2.0, see [LICENSE](LICENSE). Copyright 2026 Sortdev SRL. Bundled third-party parts
are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
