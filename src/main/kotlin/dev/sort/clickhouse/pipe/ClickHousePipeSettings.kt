package dev.sort.clickhouse.pipe

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.ToggleConfigurable
import dev.brikk.house.sql.intellij.ToggleSettings
import dev.sort.clickhouse.sql.ClickHouseDialect

@Service(Service.Level.PROJECT)
@State(name = "ClickHousePipeSettings", storages = [Storage(ToggleSettings.STORAGE)])
class ClickHousePipeSettings(project: Project) : ToggleSettings(project, ClickHouseDialect)

class ClickHousePipeConfigurable(project: Project) : ToggleConfigurable(
    "dev.sort.clickhouse.pipe", ClickHousePipeDialect.label, "ClickHouse PIPE support",
    help = "Preview and execute queries supported by ClickHouse PIPE syntax.",
    vetoProperty = ClickHousePipeDialect.vetoProperty,
) {
    override val settings = project.service<ClickHousePipeSettings>()
}
