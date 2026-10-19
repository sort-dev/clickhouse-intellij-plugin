package dev.sort.clickhouse

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dialects.clickhouse.introspector.CHouseIntrospector
import com.intellij.database.introspection.DBIntrospectionContext
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.util.Version
import com.intellij.openapi.Disposable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** The platform's final native introspector validates CLICKHOUSE, not extension-fallback aliases. */
@kotlin.jvm.JvmDefaultWithoutCompatibility
class ClickHouseIntrospectorFactory : DBIntrospector.Factory {
    private val native = CHouseIntrospector.Factory()

    override fun createIntrospector(context: DBIntrospectionContext, dbms: Dbms, modelFactory: ModelFactory): DBIntrospector {
        require(dbms === ClickHouseDbms.CLICKHOUSE_BRIKK)
        return ClickHouseIntrospector.of(native.createIntrospector(context, Dbms.CLICKHOUSE, modelFactory))
    }

    // isSupported(Version) is abstract on 261 and these two have no defaults; the rest are inherited.
    override fun isSupported(version: Version): Boolean = native.isSupported(version)
    override fun getVersion(kind: ObjectKind): Int = native.getVersion(kind)
    override fun isOutdatedCheckSupported(element: BasicElement?): Boolean = native.isOutdatedCheckSupported(element)
}

/**
 * The stock introspector, attached through a view of the connection that reports CLICKHOUSE. Both are
 * runtime proxies, not Kotlin `by` delegation: a delegating class implements only the methods of the SDK
 * it was compiled against, and newer IDEs add abstract ones (263 adds `DBIntrospector.getDiagnosticSink`).
 * A proxy implements the running IDE's interface, whatever it holds. Nothing mutates the real data
 * source, session, credentials, remote connection or platform connection point.
 */
internal object ClickHouseIntrospector {
    fun of(stock: DBIntrospector): DBIntrospector = proxy(DBIntrospector::class.java, stock) { method, args ->
        if (method.name == "attachToDB" && args?.size == 1) {
            val connection = args[0] as DatabaseConnectionCore
            require(connection.dbms === ClickHouseDbms.CLICKHOUSE_BRIKK)
            stock.attachToDB(clickHouseView(connection))
            Handled(null)
        } else null
    }

    /** [connection] reporting CLICKHOUSE; its lifecycle (Disposable from 263) stays the connection's. */
    fun clickHouseView(connection: DatabaseConnectionCore): DatabaseConnectionCore =
        proxy(DatabaseConnectionCore::class.java, connection, Disposable::class.java) { method, args ->
            when {
                method.name == "getDbms" && args.isNullOrEmpty() -> Handled(Dbms.CLICKHOUSE)
                method.name == "dispose" && args.isNullOrEmpty() -> Handled((connection as? Disposable)?.dispose())
                else -> null
            }
        }

    private class Handled(val result: Any?)

    /** A proxy of [type] (and [extra]) forwarding every call to [target] that [override] does not handle. */
    private fun <T : Any> proxy(type: Class<T>, target: T, vararg extra: Class<*>, override: (Method, Array<out Any?>?) -> Handled?): T {
        val handler = InvocationHandler { proxy, method, args ->
            when {
                method.name == "equals" && args?.size == 1 -> proxy === args[0]
                method.name == "hashCode" && args.isNullOrEmpty() -> System.identityHashCode(proxy)
                else -> {
                    val handled = override(method, args)
                    if (handled != null) handled.result else try {
                        method.invoke(target, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.cause ?: e
                    }
                }
            }
        }
        val interfaces = (listOf(type) + extra).distinct().toTypedArray()
        return type.cast(Proxy.newProxyInstance(ClickHouseIntrospector::class.java.classLoader, interfaces, handler))
    }
}
