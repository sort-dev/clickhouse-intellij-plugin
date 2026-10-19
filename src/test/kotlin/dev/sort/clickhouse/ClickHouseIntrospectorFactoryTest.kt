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
        // The production factory keeps the adapter instead of returning that incompatible stock object.
        val adapted = ClickHouseIntrospectorFactory().createIntrospector(context, ClickHouseDbms.CLICKHOUSE_BRIKK, ModelFactory.BLACK_HOLE)
        assertTrue(Proxy.isProxyClass(adapted.javaClass))
        assertNotSame(stock, adapted)
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
        val wrapper = ClickHouseIntrospector.of(recording)
        wrapper.attachToDB(original)
        @Suppress("DEPRECATION") wrapper.introspectServerObjects()
        assertSame(ClickHouseDbms.CLICKHOUSE_BRIKK, original.dbms)
        assertEquals(1, attachments)
        assertEquals(1, serverObjects)
    }

    /**
     * Every method of the running IDE's interface is implemented, including ones the compile SDK lacks
     * (263's DBIntrospector.getDiagnosticSink, which made the old `by`-delegating class binary-incompatible).
     */
    fun testTheAdapterImplementsEveryMethodOfTheRunningInterface() {
        var forwarded = 0
        val stock = Proxy.newProxyInstance(DBIntrospector::class.java.classLoader, arrayOf(DBIntrospector::class.java)) { _, _, _ ->
            forwarded++
            null
        } as DBIntrospector
        val adapter = ClickHouseIntrospector.of(stock)
        val methods = DBIntrospector::class.java.methods.filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) && it.name != "attachToDB" }
        for (method in methods) {
            val args = method.parameterTypes.map { type -> if (type.isPrimitive) java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0) else null }
            method.invoke(adapter, *args.toTypedArray())
        }
        assertEquals(methods.size, forwarded)
    }

    fun testTheConnectionViewKeepsTheConnectionsLifecycle() {
        var disposed = 0
        val connection = Proxy.newProxyInstance(DatabaseConnectionCore::class.java.classLoader,
            arrayOf(DatabaseConnectionCore::class.java, com.intellij.openapi.Disposable::class.java)) { _, method, _ ->
            when (method.name) {
                "getDbms" -> ClickHouseDbms.CLICKHOUSE_BRIKK
                "dispose" -> { disposed++; null }
                else -> error("Unexpected connection access: $method")
            }
        } as DatabaseConnectionCore
        val view = ClickHouseIntrospector.clickHouseView(connection)
        assertSame(Dbms.CLICKHOUSE, view.dbms)
        (view as com.intellij.openapi.Disposable).dispose()
        assertEquals(1, disposed)
        // A pre-263 connection that is not Disposable is never disposed through the view.
        (ClickHouseIntrospector.clickHouseView(aliasConnection()) as com.intellij.openapi.Disposable).dispose()
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
