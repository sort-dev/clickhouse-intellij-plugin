package dev.sort.clickhouse.catalog

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dialects.clickhouse.model.CHouseTable
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.util.PairConsumer
import dev.brikk.house.sql.intellij.catalog.ShowCreateDefinitionProvider
import dev.brikk.house.sql.shape.DefinedObject

class ClickHouseDefinitionProvider : ShowCreateDefinitionProvider(
    "clickhouse", mapOf(
        ObjectKind.TABLE to DefinedObject.TABLE,
        ObjectKind.VIEW to DefinedObject.VIEW,
        ObjectKind.MAT_VIEW to DefinedObject.MATERIALIZED_VIEW,
        ObjectKind.SCHEMA to DefinedObject.SCHEMA,
    ), listOf(ObjectKind.SCHEMA),
) {
    private val dictionaries = object : ShowCreateDefinitionProvider(
        "clickhouse", mapOf(ObjectKind.TABLE to DefinedObject.DICTIONARY), listOf(ObjectKind.SCHEMA),
    ) {
        public override fun fetchSources(objects: Iterable<DasObject>, connection: DatabaseConnectionCore, consumer: PairConsumer<DasObject, Any>) =
            super.fetchSources(objects, connection, consumer)
    }

    private fun isDictionary(obj: DasObject): Boolean = (obj as? CHouseTable)?.engine.equals("Dictionary", true)

    internal fun definitionQueryFor(obj: DasObject) = if (isDictionary(obj)) dictionaries.query(obj) else query(obj)

    override fun isSupported(`object`: DasObject): Boolean = definitionQueryFor(`object`) != null

    public override fun fetchSources(objects: Iterable<DasObject>, connection: DatabaseConnectionCore, consumer: PairConsumer<DasObject, Any>) {
        // The stock ClickHouse model represents dictionaries as tables with engine Dictionary.
        val (dictionaryObjects, otherObjects) = objects.partition(::isDictionary)
        dictionaries.fetchSources(dictionaryObjects, connection, consumer)
        super.fetchSources(otherObjects, connection, consumer)
    }
}
