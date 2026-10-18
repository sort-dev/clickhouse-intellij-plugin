# SQL Dialect for ClickHouse

ClickHouse SQL support for **IntelliJ IDEA** and **DataGrip**, by Sortdev SRL.

## What it does

- **Validated by real ClickHouse.** While your data source is connected, your SQL is
  checked by the ClickHouse server's own parser — parse-only, never executed, and no
  data is read or sent back. Server acceptance clears local false positives.
- **More ClickHouse syntax than the built-in dialect.** Full table-function coverage
  in FROM/JOIN/DESCRIBE/INSERT, EXISTS and ALL/ANY predicates, INSERT INTO FUNCTION
  exports, and raw FORMAT data handling the stock parser misses.
- **Live catalog completion.** Function names are read from the connected server's
  system catalog and merged with bundled ClickHouse metadata, so completion and
  documentation match the version you actually run. Use **Refresh ClickHouse Catalog**
  from the editor menu to update manually.
- **Optional PIPE syntax.** Off by default. Enable under
  **Settings > Tools > Database > ClickHouse PIPE** to preview generated SQL,
  execute pipelines, and run stages up to the caret.

Introspection, DDL, object sources and server error reporting come from the stock
ClickHouse support, over ClickHouse's official JDBC driver managed by the IDE.

## Getting started

1. Open **Settings > SQL Dialects** and choose **ClickHouse (sort.dev)** for your
   `.sql` files — or create a data source with the ClickHouse (sort.dev) driver,
   which selects the dialect for its consoles automatically.
2. Connect the data source to unlock live function inventory and server-side
   validation. When the server is unreachable, local checks still apply.

## Requirements

- IntelliJ IDEA or DataGrip, IDE builds 261–263, with the Database Tools plugin.

## Notes

- IDE `:name` parameters are prompted and substituted before validation; typed
  ClickHouse `{name:Type}` parameters pass through to the server unchanged.

## License

Apache-2.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
