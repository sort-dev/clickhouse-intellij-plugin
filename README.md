# SQL Dialect for ClickHouse

Development scaffold for a ClickHouse dialect plugin by Sortdev SRL. Apache-2.0.
Requires Database Tools. Compiles on IntelliJ 261; runtime tests cover 261, 262 and 263.

Build with `./gradlew buildPlugin verifyDistribution test test262 test263`.
Brikk SQL core/metadata 0.20.0-SNAPSHOT and IntelliJ support/test-support
0.2.0-SNAPSHOT resolve exclusively from Maven Local. Build conventions are vendored
with `../intellij-sql-plugin-gradle-conventions/sync-into.sh "$PWD"`.

CH.0 is blocked on shared-library APIs. Support currently provides only
`PgFamilyDialect`, not a `CHouseFamilyDialect(id: String)` that preserves stock
ClickHouse tokens, builtin special forms, operators and system variables while
letting the consumer supply its own DBMS. The registry also needs `ClickHouseSQL`
and `CLICKHOUSE_BRIKK` alongside the stock ClickHouse identities, so shared
datasource resolution recognizes both. No local substitutes are included.
A `CHouseFamilyParserDefinition(dialect: SqlLanguageDialectBase)` must support shared
lenient SQL_STATEMENT nodes: stock's package-private CHouseElementFactory throws
when creating their PSI. A `StatementBoundedSqlLexer(baseFactory: () -> Lexer,
coreDialect: String)` or equivalent hook must bound raw FORMAT tokens using core
script ranges, so they cannot include subsequent statements.

The boundary corpus records two stock CHouse failures: a comment between an
INSERT's PARTITION BY and SELECT splits it; JSONEachRow data consumes a subsequent
statement. Core's script ranges pass the corpus. Custom PSI and console boundaries
remain unverified until the shared ClickHouse family adapter is available.

PIPE also needs core `ParamStyle.NAMED_TYPED` for `{name:Type}` occurrences and
shared parameter handling that excludes those from IDE `:name` prompting/masking.
Today core marks both spellings NAMED_COLON, so the shared PIPE defaults conflate
them. Typed parameters must stay unchanged for ClickHouse; `:name` must use IDE
substitution before lowering. No execution integration is enabled with this gap.

Core PIPE probes cover every advertised stage phrase. PIVOT/UNPIVOT return
blockers; NATURAL JOIN is advertised but refused. Other fixtures lower and reparse
in core, including SELECT-first and nested-only queries. This is not native
validation or proof that arbitrary CALL table functions exist on a server.

Planned defaults: DBMS `CLICKHOUSE_BRIKK`, language `ClickHouseSQL`, display
`ClickHouse (sort.dev)`, stock `CLICKHOUSE` extension fallback. Function insertion
keeps catalog case; keywords use UPPER. ClickHouse PIPE is opt-in, permits SELECT
first, and has no native validator. These integrations are not enabled yet.
