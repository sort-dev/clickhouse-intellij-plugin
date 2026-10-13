package dev.sort.clickhouse.pipe

import com.intellij.database.console.session.DatabaseSession
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.sort.clickhouse.ClickHouseDbms
import dev.sort.clickhouse.sql.ClickHouseDialect

object ClickHousePipeDialect : PipeDialect {
    override val dialect = "clickhouse"
    override val label = "ClickHouse PIPE"
    override val vetoProperty = "clickhouse.pipes"
    override val language get() = ClickHouseDialect
    override fun isProjectEnabled(project: Project): Boolean = project.service<ClickHousePipeSettings>().enabled
    override fun ownsSession(session: DatabaseSession): Boolean = session.connectionPoint.dbms === ClickHouseDbms.CLICKHOUSE_BRIKK
    override val validator = object : NativeSqlValidator {
        override val timing = ValidationTiming.Lowering
        override fun validate(sql: String, file: PsiFile?): NativeVerdict = NativeVerdict.Unavailable
    }
}
