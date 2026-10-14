package dev.sort.clickhouse

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dialects.clickhouse.introspector.CHouseIntrospector
import com.intellij.database.introspection.DBIntrospectionContext
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.model.ModelFactory
import com.intellij.openapi.Disposable

/** The platform's final native introspector validates CLICKHOUSE, not extension-fallback aliases. */
class ClickHouseIntrospectorFactory : DBIntrospector.Factory by CHouseIntrospector.Factory() {
    override fun createIntrospector(context: DBIntrospectionContext, dbms: Dbms, modelFactory: ModelFactory): DBIntrospector {
        require(dbms === ClickHouseDbms.CLICKHOUSE_BRIKK)
        return ClickHouseIntrospector(CHouseIntrospector.Factory().createIntrospector(context, Dbms.CLICKHOUSE, modelFactory))
    }
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
