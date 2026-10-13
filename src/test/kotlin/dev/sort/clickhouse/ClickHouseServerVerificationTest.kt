package dev.sort.clickhouse

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.dialects.DatabaseDialects
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.DasObject
import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.util.PairConsumer
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import com.intellij.testFramework.PlatformTestUtil
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.catalog.CatalogRowSource
import dev.brikk.house.sql.intellij.testing.JdbcCatalogRows
import dev.sort.clickhouse.catalog.ClickHouseCatalogHarvester
import dev.sort.clickhouse.catalog.ClickHouseLiveCatalogService
import dev.sort.clickhouse.catalog.ClickHouseDefinitionProvider
import dev.sort.clickhouse.sql.ClickHouseCompletionProfile
import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.intellij.catalog.RefreshOutcome
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.verify.VerifyResult
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.validation.ClickHouseNativeErrorProfile
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.sql.Driver
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class ClickHouseServerVerificationTest : ClickHousePlatformTestCase() {
    fun testCatalogHarvesterReadsOnlySmallSystemInventories() {
        val url = requireNotNull(System.getenv("BRIKK_CLICKHOUSE_URL")?.takeIf(String::isNotBlank))
        val jar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER") ?: System.getProperty("clickhouse.driver.fixture"))
        val jars = arrayOf(jar.toUri().toURL(), jar.resolveSibling("slf4j-api-2.0.7.jar").toUri().toURL())
        URLClassLoader(jars, javaClass.classLoader).use { loader ->
            val driver = loader.loadClass("com.clickhouse.jdbc.ClickHouseDriver").getConstructor().newInstance() as Driver
            val properties = Properties().apply {
                setProperty("user", "default")
                setProperty("password", "")
                setProperty("compress", "false")
                setProperty("decompress", "false")
            }
            requireNotNull(driver.connect(url, properties)).use { connection ->
                val jdbc = JdbcCatalogRows(connection, 5)
                val queried = linkedMapOf<String, MutableList<List<String?>>>()
                val started = System.nanoTime()
                val harvest = requireNotNull(ClickHouseCatalogHarvester.harvest(CatalogRowSource { sql, read ->
                    val values = queried.getOrPut(sql, ::ArrayList)
                    jdbc.forEachRow("$sql SETTINGS readonly = 1") { row ->
                        val columns = if (sql == ClickHouseCatalogHarvester.SQL_FUNCTIONS) 3 else 1
                        values += (1..columns).map(row::string)
                        read(row)
                    }
                }))
                assertSameElements(queried.keys, listOf(ClickHouseCatalogHarvester.SQL_VERSION, ClickHouseCatalogHarvester.SQL_FUNCTIONS, ClickHouseCatalogHarvester.SQL_TABLE_FUNCTIONS))
                val expected = queried.getValue(ClickHouseCatalogHarvester.SQL_FUNCTIONS).filter { !it[0].orEmpty().startsWith("__") || it[2] == "SQLUserDefined" }
                    .map { requireNotNull(it[0]) to if (it[1] == "1") FunctionKind.AGGREGATE else FunctionKind.SCALAR } +
                    queried.getValue(ClickHouseCatalogHarvester.SQL_TABLE_FUNCTIONS).mapNotNull { row -> row[0]?.takeUnless { it.startsWith("__") }?.let { it to FunctionKind.TABLE_VALUED } }
                assertSameElements(expected.distinct(), harvest.shared.functions.map { it.def.name to it.def.kind })
                assertFalse(harvest.shared.signatures)
                println("ClickHouse ${harvest.shared.serverVersion}: harvested ${harvest.shared.functions.size} name/kind pairs in ${(System.nanoTime() - started) / 1_000_000} ms using read-only system queries")
            }
        }
    }

    fun testStoredLoginVerifiesExportSyntaxWithoutWritingAnObject() {
        val url = requireNotNull(System.getenv("BRIKK_CLICKHOUSE_URL")?.takeIf(String::isNotBlank))
        val endpoint = URI.create("http:" + url.removePrefix("jdbc:clickhouse:"))
        val admin = URI.create("http://${endpoint.rawAuthority}/")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val user = "ch1_user_$suffix"
        val marker = "ch1_export_$suffix"
        val password = "ch1-synthetic-password"
        fun adminQuery(sql: String): String = HttpClient.newHttpClient().use { client ->
            val response = client.send(HttpRequest.newBuilder(admin).POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { response.body() }
            response.body().trim()
        }
        adminQuery("CREATE USER $user IDENTIFIED WITH plaintext_password BY '$password'")
        Disposer.register(testRootDisposable) { adminQuery("DROP USER IF EXISTS $user") }
        adminQuery("GRANT SELECT, INSERT, FILE, S3, SHOW TABLES, SHOW DICTIONARIES ON *.* TO $user")
        val namespace = "ch2_namespace_$suffix"
        adminQuery("CREATE DATABASE $namespace")
        Disposer.register(testRootDisposable) { adminQuery("DROP DATABASE IF EXISTS $namespace") }
        adminQuery("CREATE TABLE $namespace.definition_table (id UInt64, value String) ENGINE=Memory")
        adminQuery("CREATE VIEW $namespace.definition_view AS SELECT id, value FROM $namespace.definition_table")
        adminQuery("CREATE MATERIALIZED VIEW $namespace.definition_mv ENGINE=Memory AS SELECT id, value FROM $namespace.definition_table")
        adminQuery("CREATE DICTIONARY $namespace.definition_dictionary (id UInt64, value String) PRIMARY KEY id SOURCE(CLICKHOUSE(HOST '127.0.0.1' PORT 9000 USER 'default' DB '$namespace' TABLE 'definition_table')) LAYOUT(HASHED()) LIFETIME(0)")

        val jar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER") ?: System.getProperty("clickhouse.driver.fixture"))
        assertTrue("Missing driver fixture: $jar", Files.isRegularFile(jar))
        val manager = DatabaseDriverManager.getInstance()
        val driver = requireNotNull(manager.getDriver("clickhouse-sortdev")).copy("ch1-driver-$suffix", true) as DatabaseDriverImpl
        driver.artifacts = emptyList()
        driver.additionalClasspathElements = listOf(jar, jar.resolveSibling("slf4j-api-2.0.7.jar"))
            .flatMap { SimpleClasspathElementFactory.createElements("jar://$it!/") }
        manager.updateDriver(driver)
        Disposer.register(testRootDisposable) { manager.removeDriver(driver) }
        val source = LocalDataSource().apply {
            name = "CH.1 server test $suffix"
            databaseDriver = driver
            this.url = url + if ('?' in url) "&compress=true&decompress=true" else "?compress=true&decompress=true"
            username = user
            passwordStorage = LocalDataSource.Storage.MEMORY
            isAutoSynchronize = false
            isKeepAlive = false
        }
        DatabaseCredentials.getInstance().setPassword(source, OneTimeString(password))
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        Disposer.register(testRootDisposable) { LocalDataSourceManager.getInstance(project).removeDataSource(source) }
        val properties = mapOf("compress" to "false", "decompress" to "false", "ignore_unknown_config_key" to "true")
        val point = HelperConnectionPoint(source, properties)
        val session = runBlocking {
            DatabaseConnectionManager.getInstance().build(project, point).setRequestor(ConnectionRequestor.Anonymous()).setAskPassword(false).create()
        }
        requireNotNull(session).use {
            val live = ApplicationManager.getApplication().service<ClickHouseLiveCatalogService>()
            val refresh = ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { live.refresh(project, source) } })
            try {
                PlatformTestUtil.waitWithEventsDispatching("ClickHouse live function inventory", { refresh.isDone }, 30)
                assertTrue(refresh.get(1, TimeUnit.SECONDS).toString(), refresh.get() is RefreshOutcome.Refreshed)
            } finally {
                if (!refresh.isDone) refresh.cancel(true)
            }
            assertTrue(live.selection(source).source is CatalogSource.Live)
            val model = ModelFactory.BLACK_HOLE.createModel(ClickHouseDbms.CLICKHOUSE_BRIKK)
            val schema = (model.root as CHouseRoot).schemas.createOrGet(namespace)
            val definitions = listOf(
                schema to "CREATE DATABASE",
                schema.tables.createOrGet("definition_table") to "CREATE TABLE",
                schema.views.createOrGet("definition_view") to "CREATE VIEW",
                schema.matViews.createOrGet("definition_mv") to "CREATE MATERIALIZED VIEW",
                schema.tables.createOrGet("definition_dictionary").apply { engine = "Dictionary" } to "CREATE DICTIONARY",
            )
            val loaded = linkedMapOf<DasObject, Any>()
            ClickHouseDefinitionProvider().fetchSources(definitions.map { it.first }, it.get(), PairConsumer { obj, text -> loaded[obj] = text })
            for ((obj, prefix) in definitions) {
                val definition = loaded[obj]
                assertTrue("$obj returned $definition", definition is String && definition.startsWith(prefix))
            }
            val dialect = requireNotNull(DatabaseDialects.findByDbms(ClickHouseDbms.CLICKHOUSE_BRIKK))
            val path = SearchPath.of(ObjectPath.create(namespace, ObjectKind.SCHEMA))
            val use = requireNotNull(dialect.sqlSetSearchPath(path))
            val statement = requireNotNull(JdbcNativeUtil.computeRemote { it.get().remoteConnection.createStatement() })
            try {
                JdbcNativeUtil.computeRemote { statement.execute(use) }
                println("ClickHouse JDBC 0.9.8 read-back after raw USE: ${dialect.tryToLoadSearchPath(it.get())?.current?.name}")
                assertTrue(dialect.shouldSwitchThroughJdbc(ObjectKind.SCHEMA))
                JdbcNativeUtil.computeRemote { it.get().remoteConnection.setSchema(namespace) }
                assertEquals(namespace, dialect.tryToLoadSearchPath(it.get())?.current?.name)
            } finally {
                JdbcNativeUtil.closeRemoteStatementSafe(statement)
            }
            source.initScript = "INSERT INTO FUNCTION file('$marker-startup.tsv', 'TSV', 'id UInt64') SELECT 7"
            assertTrue(source.url.orEmpty().contains("compress=true"))
            val rejectedSql = "SELECT 'unicode 😀' FRM unknown_table"
            val rejectedFile = myFixture.configureByText("server-rejection-$suffix.sql", rejectedSql)
            setSqlDialectMapping(project, rejectedFile.virtualFile, ClickHouseDialect)
            val rejected = myFixture.doHighlighting(HighlightSeverity.ERROR)
                .filter { it.description.orEmpty().startsWith("ClickHouse:") }
            assertEquals(rejected.toString(), 1, rejected.size)
            val failedToken = rejectedSql.indexOf("unknown_table")
            assertEquals(failedToken, rejected.single().startOffset)
            assertEquals(failedToken + "unknown_table".length, rejected.single().endOffset)
            val statements = listOf(
                "INSERT INTO FUNCTION s3('https://objects.example.invalid/$marker/{_partition_id}/export.parquet', 'CH1_FAKE_ACCESS', 'CH1_FAKE_SECRET', 'Parquet')",
                "INSERT INTO FUNCTION file('$marker-{_partition_id}.parquet', 'Parquet', 'id UInt64, ts DateTime')",
            ).map { head -> "$head PARTITION BY concat('month=', toString(toMonth(ts))) SELECT number AS id, toDateTime('2026-02-03') AS ts FROM numbers(1) SETTINGS max_threads = 1 FORMAT Parquet;" }
            for (sql in statements) {
                val file = myFixture.configureByText("server-$suffix.sql", sql)
                setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
                assertContainsElements(ClickHouseCompletionProfile.functions(myFixture.file).map { it.name }, "toDateTime", "numbers", "s3")
                assertFalse(password in source.url.orEmpty())
                val validator = requireNotNull(ClickHouseNativeErrorProfile.validator(myFixture.file))
                val checkedSql = ClickHouseNativeErrorProfile.verificationText(myFixture.file, sql.removeSuffix(";"))
                val verdict = ApplicationManager.getApplication().executeOnPooledThread(Callable { validator.verify(checkedSql, null) })
                try {
                    PlatformTestUtil.waitWithEventsDispatching("ClickHouse parse-only verification", { verdict.isDone }, 30)
                    assertEquals(VerifyResult.Accepted, verdict.get(1, TimeUnit.SECONDS))
                } finally {
                    if (!verdict.isDone) verdict.cancel(true)
                }
                assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
                adminQuery("SYSTEM FLUSH LOGS")
                val hex = checkedSql.toByteArray(Charsets.UTF_8).toHexString(HexFormat.UpperCase)
                val logged = adminQuery("SELECT count() FROM system.query_log WHERE user = '$user' AND type = 'QueryFinish' AND startsWith(query, 'SELECT formatQuery(unhex(') AND position(query, '$hex') > 0")
                assertTrue("Verification did not use the stored login", logged.toLong() > 0)
            }
            adminQuery("SYSTEM FLUSH LOGS")
            assertEquals("0", adminQuery("SELECT count() FROM system.query_log WHERE user = '$user' AND position(query, '$marker') > 0"))
            System.getenv("BRIKK_CLICKHOUSE_CONTAINER")?.let { container ->
                val check = ProcessBuilder("docker", "exec", container, "find", "/var/lib/clickhouse/user_files", "-name", "$marker*", "-print")
                    .redirectErrorStream(true).start()
                val files = check.inputStream.bufferedReader().use { it.readText() }
                assertEquals(files, 0, check.waitFor())
                assertTrue("Verification wrote an object: $files", files.isBlank())
            }
        }
    }
}
