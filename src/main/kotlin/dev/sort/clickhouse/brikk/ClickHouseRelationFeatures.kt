package dev.sort.clickhouse.brikk

import com.intellij.openapi.extensions.ExtensionPointName
import dev.brikk.house.sql.intellij.catalog.DasRelationFacts
import dev.brikk.house.sql.intellij.relation.ExternalRelationAnnotator
import dev.brikk.house.sql.intellij.relation.ExternalRelationCompletionContributor
import dev.brikk.house.sql.intellij.relation.ExternalRelationFacts
import dev.brikk.house.sql.intellij.relation.ExternalRelationGotoDeclarationHandler
import dev.brikk.house.sql.intellij.relation.ExternalRelationInspectionSuppressor
import dev.brikk.house.sql.intellij.relation.ExternalRelationResolveExtension
import dev.brikk.house.sql.intellij.relation.RelationScopeProfile
import dev.brikk.house.sql.intellij.relation.TableFunctionFacts
import dev.sort.clickhouse.sql.ClickHouseCompletionProfile
import dev.sort.clickhouse.catalog.ClickHouseCatalogProfile
import dev.sort.clickhouse.sql.ClickHouseDialect

object ClickHouseRelationProfile : RelationScopeProfile {
    private val facts = ExtensionPointName.create<ExternalRelationFacts>("dev.sort.clickhouse.relationFacts")
    private val physical = DasRelationFacts(ClickHouseCatalogProfile.dataSources)
    private val tableFunctions = TableFunctionFacts(ClickHouseCompletionProfile::snapshot)
    override val language get() = ClickHouseDialect
    override val dialect = "clickhouse"
    override val providers get() = facts.extensionList + physical + tableFunctions
    override val hostSql get() = ClickHouseHostSql
}

class ClickHouseRelationResolveExtension : ExternalRelationResolveExtension(ClickHouseRelationProfile)
class ClickHouseRelationInspectionSuppressor : ExternalRelationInspectionSuppressor(ClickHouseRelationProfile)
class ClickHouseRelationCompletionContributor : ExternalRelationCompletionContributor(ClickHouseRelationProfile)
class ClickHouseRelationGotoDeclarationHandler : ExternalRelationGotoDeclarationHandler(ClickHouseRelationProfile)
class ClickHouseRelationAnnotator : ExternalRelationAnnotator(ClickHouseRelationProfile)
