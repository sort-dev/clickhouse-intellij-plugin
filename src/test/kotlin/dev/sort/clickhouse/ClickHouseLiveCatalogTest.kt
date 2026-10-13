package dev.sort.clickhouse

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.intellij.catalog.LiveCatalogStore
import dev.brikk.house.sql.intellij.catalog.LiveFunction
import dev.brikk.house.sql.intellij.catalog.LiveInventory
import dev.brikk.house.sql.intellij.catalog.SessionFacts
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind
import dev.sort.clickhouse.catalog.ClickHouseLiveCatalogProfile
import java.nio.file.Files
import java.nio.file.Path

class ClickHouseLiveCatalogTest : ClickHousePlatformTestCase() {
    fun testLiveThenDiskThenBundledSelectionIsIdentityScoped() {
        val parent = Files.createDirectories(Path.of(System.getProperty("clickhouse.catalog.fixture")))
        val directory = Files.createTempDirectory(parent, "selection-")
        val source = LocalDataSource().apply {
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("clickhouse-sortdev"))
            url = "jdbc:clickhouse://example.invalid/default"
            username = "first-user"
        }
        val profile = ClickHouseLiveCatalogProfile
        val identity = profile.identity(source)
        val store = LiveCatalogStore(directory)
        assertSame(CatalogSource.Bundled, store.select(source.uniqueId, identity).source)
        val inventory = LiveInventory("26.9.17.8", listOf(LiveFunction(FunctionDef("toDateTime", FunctionKind.SCALAR))), false)
        store.store(source.uniqueId, identity, inventory)
        val session = Any()
        val temporary = SessionFacts(listOf(LiveFunction(FunctionDef("temporary_session_fn", FunctionKind.SCALAR))))
        store.storeSession(session, source.uniqueId, identity, temporary)
        assertTrue(store.select(source.uniqueId, identity, session).source is CatalogSource.Live)
        assertEquals(temporary, store.select(source.uniqueId, identity, session).session)
        val restarted = LiveCatalogStore(directory)
        val disk = restarted.select(source.uniqueId, identity, session)
        assertTrue(disk.source is CatalogSource.Disk)
        assertEquals(SessionFacts.NONE, disk.session)
        assertEquals(inventory, disk.inventory)
        source.username = "second-user"
        assertSame(CatalogSource.Bundled, restarted.select(source.uniqueId, profile.identity(source)).source)
        assertFalse(profile.clientTransactions)
        assertEquals("false", profile.connectionProperties(source)["compress"])
        assertEquals("false", profile.connectionProperties(source)["decompress"])
        store.forget(source.uniqueId)
    }
}
