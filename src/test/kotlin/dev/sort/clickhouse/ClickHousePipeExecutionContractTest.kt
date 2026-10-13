package dev.sort.clickhouse

import dev.brikk.house.sql.intellij.testing.PipeExecutionContract
import dev.sort.clickhouse.pipe.ClickHousePipeDialect

class ClickHousePipeExecutionContractTest : ClickHousePipeTestCase() {
    private fun contract() = PipeExecutionContract(
        ClickHousePipeDialect, "clickhouse-sortdev", "mysql", { settings.enabled = it }, ::open,
    )

    fun testDeclinesWhenNotOwned() = contract().declinesWhenNotOwned()
    fun testSelectsStatementsLikeStockExecute() = contract().selectsStatementsLikeStockExecute()
    fun testRefusesWholeScriptsItCannotRun() = contract().refusesWholeScriptsItCannotRun()
    fun testClaimsOnlyPipes() = contract().claimsOnlyPipes()
    fun testRefusesStaleInput() = contract().refusesStaleInput()
    fun testPropagatesCancellation() = contract().propagatesCancellation()
    fun testLowersNestedOnlyPipelines() = contract().lowersNestedOnlyPipelines()
    fun testRunsTopLevelStagesToTheCaret() = contract().runsTopLevelStagesToTheCaret()
    fun testRefusesDestinationChangesDuringPrompting() = contract().refusesDestinationChangesDuringPrompting()
    fun testRefusesNamespaceChangesDuringPrompting() = contract().refusesNamespaceChangesDuringPrompting()
    fun testNotifiesRefusals() = contract().notifiesRefusals()
}
