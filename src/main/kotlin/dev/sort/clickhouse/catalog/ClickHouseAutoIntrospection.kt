package dev.sort.clickhouse.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectService
import dev.brikk.house.sql.intellij.catalog.CatalogProfile
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.IntrospectBannerProvider
import dev.brikk.house.sql.intellij.catalog.QualifiedPathIntrospectContributor
import dev.sort.clickhouse.ClickHouseDbms
import kotlinx.coroutines.CoroutineScope

internal val clickHouseDataSources = DialectDataSourceResolver(ClickHouseDbms.CLICKHOUSE_BRIKK)

object ClickHouseCatalogProfile : CatalogProfile {
    override val dbms get() = ClickHouseDbms.CLICKHOUSE_BRIKK
    override val productLabel = "ClickHouse"
    override val quoteChars = Dialects.forName("clickhouse").tokenizerConfig.identifiers.keys.joinToString("")
    override val levels = listOf(ObjectKind.SCHEMA)
    override val usesCurrentNamespace = true
    override val maxDeepenDepth = 2
    override val autoIntrospect = true
    override val dataSources get() = clickHouseDataSources
}

@Service(Service.Level.PROJECT)
class ClickHouseAutoIntrospectService(project: Project, scope: CoroutineScope) : AutoIntrospectService(project, scope) {
    override val profile = ClickHouseCatalogProfile
}

class ClickHouseIntrospectBannerProvider : IntrospectBannerProvider(ClickHouseAutoIntrospectService::class.java)
class ClickHouseQualifiedPathIntrospectContributor : QualifiedPathIntrospectContributor(ClickHouseAutoIntrospectService::class.java)
