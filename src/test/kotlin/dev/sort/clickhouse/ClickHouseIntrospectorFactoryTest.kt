package dev.sort.clickhouse

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dialects.base.introspector.BaseIntrospector
import com.intellij.database.dialects.clickhouse.introspector.CHouseIntrospector
import com.intellij.database.introspection.DBIntrospectionContext
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.introspection.DbmsMismatchException
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.Version
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Proxy

class ClickHouseIntrospectorFactoryTest : BasePlatformTestCase() {
    fun testFactoryPreservesStockCapabilitiesWithoutDefaultForwarders() {
        val factory = ClickHouseIntrospectorFactory()
        val stock = CHouseIntrospector.Factory()
        assertEquals(stock.isNative(), factory.isNative())
        assertEquals(stock.isIncremental(), factory.isIncremental())
        assertEquals(stock.supportsFragmentIntrospection, factory.supportsFragmentIntrospection)
        assertEquals(stock.supportsMultilevelIntrospection, factory.supportsMultilevelIntrospection)
        assertEquals(stock.isSupported(Version.of(26, 9)), factory.isSupported(Version.of(26, 9)))
        assertEquals(stock.isOutdatedCheckSupported(null), factory.isOutdatedCheckSupported(null))
        for (kind in listOf(ObjectKind.ROOT, ObjectKind.SCHEMA, ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.ROUTINE)) {
            assertEquals(stock.getVersion(kind), factory.getVersion(kind))
        }
        assertFalse(factory.javaClass.declaredMethods.any {
            it.name in setOf("isNative", "isIncremental", "supportsFragmentIntrospection", "supportsMultilevelIntrospection")
        })
    }

    fun testStockFactoryStillNeedsTheAliasAttachmentAdapter() {
        val context = object : DBIntrospectionContext {
            override val project get() = this@ClickHouseIntrospectorFactoryTest.project
            override fun getUserNotifier(dataSourceId: String): DBIntrospectionContext.UserNotifier =
                error("No notifier needed for this offline check")
        }
        val stock = CHouseIntrospector.Factory().createIntrospector(context, ClickHouseDbms.CLICKHOUSE_BRIKK, ModelFactory.BLACK_HOLE)
        assertSame(Dbms.CLICKHOUSE, (stock as BaseIntrospector<*>).dbms)
        assertThrows(DbmsMismatchException::class.java) { stock.attachToDB(aliasConnection()) }
        // The production factory keeps the wrapper instead of returning that incompatible stock object.
        val adapted = ClickHouseIntrospectorFactory().createIntrospector(context, ClickHouseDbms.CLICKHOUSE_BRIKK, ModelFactory.BLACK_HOLE)
        assertEquals("dev.sort.clickhouse.ClickHouseIntrospector", adapted.javaClass.name)
    }

    fun testWrapperPreservesConnectionIdentityAndForwardsServerObjects() {
        val original = aliasConnection()
        var attachments = 0
        var serverObjects = 0
        val recording = Proxy.newProxyInstance(DBIntrospector::class.java.classLoader, arrayOf(DBIntrospector::class.java)) { _, method, args ->
            when (method.name) {
                "attachToDB" -> {
                    attachments++
                    val view = args!![0] as DatabaseConnectionCore
                    assertNotSame(original, view)
                    assertSame(Dbms.CLICKHOUSE, view.dbms)
                    null
                }
                "introspectServerObjects" -> { serverObjects++; null }
                else -> error("Unexpected introspector call: $method")
            }
        } as DBIntrospector
        // Construct our private wrapper with a recording delegate; no reflection into platform internals.
        val constructor = Class.forName("dev.sort.clickhouse.ClickHouseIntrospector").getDeclaredConstructor(DBIntrospector::class.java)
        constructor.isAccessible = true
        val wrapper = constructor.newInstance(recording) as DBIntrospector
        wrapper.attachToDB(original)
        wrapper.introspectServerObjects()
        assertSame(ClickHouseDbms.CLICKHOUSE_BRIKK, original.dbms)
        assertEquals(1, attachments)
        assertEquals(1, serverObjects)
    }

    private fun aliasConnection(): DatabaseConnectionCore = Proxy.newProxyInstance(
        DatabaseConnectionCore::class.java.classLoader, arrayOf(DatabaseConnectionCore::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getDbms" -> ClickHouseDbms.CLICKHOUSE_BRIKK
            "toString" -> "offline ClickHouse alias"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args!![0]
            else -> error("Unexpected connection access: $method")
        }
    } as DatabaseConnectionCore
}
