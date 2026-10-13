package dev.sort.clickhouse.pipe

import com.intellij.database.console.session.DatabaseSession
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.sort.clickhouse.ClickHouseDbms
import dev.sort.clickhouse.sql.ClickHouseDialect

object ClickHousePipeDialect : PipeDialect {
    override val dialect = "clickhouse"
    override val label = "ClickHouse PIPE"
    override val vetoProperty = "clickhouse.pipes"
    override val language get() = ClickHouseDialect
    override fun isProjectEnabled(project: Project): Boolean = project.service<ClickHousePipeSettings>().enabled
    override fun ownsSession(session: DatabaseSession): Boolean = session.connectionPoint.dbms === ClickHouseDbms.CLICKHOUSE_BRIKK
}
