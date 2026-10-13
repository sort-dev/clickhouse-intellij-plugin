package dev.sort.clickhouse.catalog

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.catalog.ExecutionObserver
import dev.brikk.house.sql.intellij.catalog.ExecutionObserverProfile
import dev.brikk.house.sql.intellij.catalog.ExecutionObserverStartup
import dev.brikk.house.sql.intellij.catalog.HarvestConnectionInterceptor
import dev.brikk.house.sql.intellij.catalog.LiveCatalogCleanup
import dev.brikk.house.sql.intellij.catalog.LiveCatalogProfile
import dev.brikk.house.sql.intellij.catalog.LiveCatalogHarvester
import dev.brikk.house.sql.intellij.catalog.LiveCatalogService
import dev.brikk.house.sql.intellij.catalog.RefreshLiveCatalogAction
import dev.brikk.house.sql.intellij.catalog.StatementEffect
import dev.brikk.house.sql.intellij.catalog.StatementHeadDetector
import dev.brikk.house.sql.intellij.catalog.StatementRule
import dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA
import dev.sort.clickhouse.ClickHouseDbms
import kotlinx.coroutines.CoroutineScope

object ClickHouseLiveCatalogProfile : LiveCatalogProfile {
    override val dbms get() = ClickHouseDbms.CLICKHOUSE_BRIKK
    override val productLabel = "ClickHouse"
    override val metadata = CLICKHOUSE_METADATA
    override val harvester: LiveCatalogHarvester get() = ClickHouseCatalogHarvester
    override val dataSources get() = clickHouseDataSources
    override val persistenceId = "clickhouse-sortdev"
    override val clientTransactions = false
    override val notificationGroupId = "ClickHouse catalog"
    override fun connectionProperties(dataSource: LocalDataSource): Map<String, String> =
        mapOf("compress" to "false", "decompress" to "false", "ignore_unknown_config_key" to "true")
}

@Service(Service.Level.APP)
class ClickHouseLiveCatalogService(scope: CoroutineScope) : LiveCatalogService(scope) {
    override val profile = ClickHouseLiveCatalogProfile
}

class ClickHouseHarvestConnectionInterceptor : HarvestConnectionInterceptor(ClickHouseLiveCatalogService::class.java)
class ClickHouseLiveCatalogCleanup : LiveCatalogCleanup(ClickHouseLiveCatalogService::class.java)
class ClickHouseRefreshCatalogAction : RefreshLiveCatalogAction(ClickHouseLiveCatalogService::class.java)

object ClickHouseExecutionObserverProfile : ExecutionObserverProfile {
    override val dbms get() = ClickHouseDbms.CLICKHOUSE_BRIKK
    override val dataSources get() = clickHouseDataSources
    override val liveCatalog = ClickHouseLiveCatalogService::class.java
    override val autoIntrospect = ClickHouseAutoIntrospectService::class.java
    override val statements = StatementHeadDetector(listOf(
        StatementRule("CREATE [OR REPLACE] FUNCTION", StatementEffect.FunctionInventory()),
        StatementRule("DROP FUNCTION", StatementEffect.FunctionInventory()),
        StatementRule("CREATE DATABASE", StatementEffect.Namespace(1)),
        StatementRule("DROP DATABASE", StatementEffect.Namespace(1)),
    ), "clickhouse")
}

@Service(Service.Level.PROJECT)
class ClickHouseExecutionObserver(project: Project, scope: CoroutineScope) : ExecutionObserver(project, scope) {
    override val profile = ClickHouseExecutionObserverProfile
}

class ClickHouseExecutionObserverStartup : ExecutionObserverStartup(ClickHouseExecutionObserver::class.java)
