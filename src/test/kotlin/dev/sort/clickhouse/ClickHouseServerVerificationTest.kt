package dev.sort.clickhouse

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseConnectionPoint
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import com.intellij.testFramework.PlatformTestUtil
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.verify.VerifyResult
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.validation.ClickHouseNativeErrorProfile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

class ClickHouseServerVerificationTest : ClickHousePlatformTestCase() {
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
        adminQuery("GRANT SELECT, INSERT, FILE, S3 ON *.* TO $user")

        val jar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER") ?: System.getProperty("clickhouse.driver.fixture"))
        assertTrue("Missing driver fixture: $jar", Files.isRegularFile(jar))
        project.basePath?.let { Files.createDirectories(Path.of(it)) }
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
            this.url = url
            username = user
            passwordStorage = LocalDataSource.Storage.MEMORY
            isAutoSynchronize = false
            isKeepAlive = false
        }
        DatabaseCredentials.getInstance().setPassword(source, OneTimeString(password))
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        Disposer.register(testRootDisposable) { LocalDataSourceManager.getInstance(project).removeDataSource(source) }
        val properties = mapOf("compress" to "false", "decompress" to "false", "ignore_unknown_config_key" to "true")
        val point = object : DatabaseConnectionPoint.Adapter(source) {
            override fun getConnectionProperties(): Properties = Properties().also {
                it.putAll(super.getConnectionProperties())
                it.putAll(properties)
            }
        }
        val session = runBlocking {
            DatabaseConnectionManager.getInstance().build(project, point).setRequestor(ConnectionRequestor.Anonymous()).setAskPassword(false).create()
        }
        requireNotNull(session).use {
            source.initScript = "INSERT INTO FUNCTION file('$marker-startup.tsv', 'TSV', 'id UInt64') SELECT 7"
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
