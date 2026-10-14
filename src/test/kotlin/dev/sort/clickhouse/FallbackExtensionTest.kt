package dev.sort.clickhouse

import dev.brikk.house.sql.intellij.testing.assertFallbackExtensionsAudited

class FallbackExtensionTest : ClickHousePlatformTestCase() {
    fun testInheritedExtensionsAreAudited() {
        val allowed = mapOf(
            "com.intellij.database.dataImporter" to "Stock ClickHouse import formats and target types are native to this DBMS.",
            "com.intellij.database.databaseViewStructureExtension" to "Stock ClickHouse tree grouping matches the inherited model.",
            "com.intellij.database.dmlHelper" to "Stock ClickHouse grid DML policies match the target engine.",
            "com.intellij.database.domainRegistry" to "Stock ClickHouse domains belong to its type system.",
            "com.intellij.database.errorHandler" to "Stock ClickHouse server error decoding is the correct native handler.",
            "com.intellij.database.executionEnvironmentHelper" to "Generic console environment handling needs no engine-specific override.",
            "com.intellij.database.explainPlanProvider" to "Stock ClickHouse EXPLAIN uses ClickHouse syntax and result formats.",
            "com.intellij.database.geoHelper" to "Generic geometry presentation is sufficient; no custom geometry encoding is introduced.",
            "com.intellij.database.gridColumnsManagerFactory" to "Generic result-grid column management is independent of the editor dialect.",
            "com.intellij.database.gridHelper" to "Generic result-grid editing remains the platform default.",
            "com.intellij.database.hookUpHelper" to "Generic editor-to-datasource association works with the own DBMS.",
            "com.intellij.database.jdbcHelper" to "Stock ClickHouse JDBC properties and connection handling match the server.",
            "com.intellij.database.jdbcMetadataWrapper" to "Generic JDBC metadata wrapping preserves the native driver metadata.",
            "com.intellij.database.jdbcSourceLoader" to "Stock ClickHouse source queries retrieve native object definitions.",
            "com.intellij.database.namingService" to "Stock ClickHouse identifier naming matches the target engine.",
            "com.intellij.database.objectEditorFactory" to "Generic object editors consume the inherited ClickHouse editor models.",
            "com.intellij.database.objectEditorModelFactory" to "Stock ClickHouse object properties require its native editor model.",
            "com.intellij.database.predicatesHelper" to "Stock ClickHouse predicate generation uses native types and syntax.",
            "com.intellij.database.scriptGenerator" to "Stock ClickHouse DDL generation targets the same server.",
            "com.intellij.database.sqlObjectBuilder" to "Stock ClickHouse object construction matches the retained structured PSI.",
            "com.intellij.database.typeSystem" to "Stock ClickHouse types match the grammar and the native model.",
            "com.intellij.sql.navigationHelper" to "Generic SQL PSI navigation works for the inherited grammar.",
        )
        assertFallbackExtensionsAudited(ClickHouseDbms.CLICKHOUSE_BRIKK, allowed)
    }
}
