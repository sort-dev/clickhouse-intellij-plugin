package dev.sort.clickhouse

internal data class BoundaryCase(val name: String, val statements: List<String>) {
    val sql: String = statements.joinToString("\n")
}

internal val boundaryCorpus = listOf(
    BoundaryCase(
        "partitioned s3 insert",
        listOf(
            "INSERT INTO FUNCTION s3('https://example.invalid/archive/{_partition_id}/events.parquet', 'Parquet') " +
                "PARTITION BY concat('month=', toString(toMonth(event_time))) " +
                "SELECT * FROM analytics.events WHERE event_time >= toDate('2026-02-01') " +
                "SETTINGS max_threads = 3, s3_truncate_on_insert = 0;",
        ),
    ),
    BoundaryCase(
        "insert into table function",
        listOf("INSERT INTO TABLE FUNCTION file('events.csv', 'CSV') SELECT id FROM events SETTINGS max_threads = 2;"),
    ),
    BoundaryCase(
        "multiline insert with comments",
        listOf(
            """
            INSERT INTO /* destination */ FUNCTION
                s3('https://example.invalid/log/{_partition_id}/data.parquet', 'Parquet')
            -- partition expression
            PARTITION BY toYYYYMM(ts)
            /* SELECT must stay in the INSERT */ SELECT * FROM logs
            SETTINGS max_threads = 2;
            """.trimIndent(),
        ),
    ),
    BoundaryCase("remote table function", listOf("INSERT INTO FUNCTION remote('example.invalid', 'db', 'events') SELECT * FROM events;")),
    BoundaryCase("null table function", listOf("INSERT INTO FUNCTION null('id UInt64') SELECT id FROM events;")),
    BoundaryCase("settings and format", listOf("SELECT number FROM numbers(5) SETTINGS max_threads = 2 FORMAT JSONEachRow;")),
    BoundaryCase("format tail", listOf("SELECT 1 AS value FORMAT TSV;")),
    BoundaryCase("values data tail", listOf("INSERT INTO events FORMAT Values (1, 'a; b'), (2, 'c');")),
    BoundaryCase("json data tail", listOf("INSERT INTO events FORMAT JSONEachRow\n{\"id\":1,\"label\":\"a; b\"}\n{\"id\":2,\"label\":\"c\"};")),
    BoundaryCase("with expression", listOf("WITH toDate('2026-02-01') AS cutoff SELECT * FROM events WHERE ts >= cutoff;")),
    BoundaryCase("parametric aggregates", listOf("SELECT quantile(0.9)(latency), quantiles(0.5, 0.99)(latency) FROM events;")),
    BoundaryCase("array join", listOf("SELECT id, tag FROM events ARRAY JOIN tags AS tag;")),
    BoundaryCase("final sample limit by", listOf("SELECT user_id, ts FROM events FINAL SAMPLE 0.1 ORDER BY ts DESC LIMIT 2 BY user_id;")),
    BoundaryCase("typed query parameters", listOf("SELECT {value:UInt64}, {filter:Array(String)} FROM {table:Identifier} WHERE id = {id:Nullable(UInt64)};")),
    BoundaryCase("quoted identifiers", listOf("SELECT `semi;colon`, \"double;quoted\" FROM `event-log`;")),
    BoundaryCase("semicolons in trivia", listOf("SELECT 'a; b' /* ; SELECT 9 */ AS value -- ; SELECT 8\nFROM events;")),
    BoundaryCase("multiple statements", listOf("SELECT ';' AS value;", "SELECT /* ; */ 2;", "INSERT INTO FUNCTION null('id UInt64') SELECT 3;")),
    BoundaryCase("values followed by select", listOf("INSERT INTO events FORMAT Values (1, 'x;y');", "SELECT 2;")),
    BoundaryCase("json followed by select", listOf("INSERT INTO events FORMAT JSONEachRow\n{\"id\":1};", "SELECT 2;")),
)
