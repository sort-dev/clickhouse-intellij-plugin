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

/** The platform's final native introspector validates CLICKHOUSE, not extension-fallback aliases. */
@kotlin.jvm.JvmDefaultWithoutCompatibility
class ClickHouseIntrospectorFactory : DBIntrospector.Factory {
    private val native = CHouseIntrospector.Factory()

    override fun createIntrospector(context: DBIntrospectionContext, dbms: Dbms, modelFactory: ModelFactory): DBIntrospector {
        require(dbms === ClickHouseDbms.CLICKHOUSE_BRIKK)
        return ClickHouseIntrospector(native.createIntrospector(context, Dbms.CLICKHOUSE, modelFactory))
    }

    // The stock factory inherits the other capability defaults; forwarding them adds no behavior.
    override fun isSupported(version: Version): Boolean = native.isSupported(version)
    override fun getVersion(kind: ObjectKind): Int = native.getVersion(kind)
    override fun isOutdatedCheckSupported(element: BasicElement?): Boolean = native.isOutdatedCheckSupported(element)
}

private class ClickHouseIntrospector(private val stock: DBIntrospector) : DBIntrospector by stock {
    override fun attachToDB(connection: DatabaseConnectionCore) {
        require(connection.dbms === ClickHouseDbms.CLICKHOUSE_BRIKK)
        // Only the native introspector sees this family identity. Nothing mutates the real datasource,
        // session, credentials, remote connection or platform connection point.
        stock.attachToDB(object : DatabaseConnectionCore by connection, Disposable {
            override fun getDbms(): Dbms = Dbms.CLICKHOUSE
            // DatabaseConnectionCore acquires Disposable in 263; delegate its lifecycle there.
            override fun dispose() {
                (connection as? Disposable)?.dispose()
            }
        })
    }
}
