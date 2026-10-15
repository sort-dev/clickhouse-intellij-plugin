package dev.sort.clickhouse

import com.intellij.codeInsight.completion.CompletionConfidenceEP
import com.intellij.codeInsight.completion.CompletionPhase
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.lang.LanguageExtensionPoint
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseCompletionConfidence
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHouseCompletionConfidenceTest : ClickHousePlatformTestCase() {
    fun testRegisteredConfidenceInstantiatesThroughThePlatformLanguageExtension() {
        assertNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.sort.clickhouse-intellij-plugin")))
        val registered = CompletionConfidenceEP.forLanguage(ClickHouseDialect)
        assertEquals(1, registered.count { it is ClickHouseCompletionConfidence })
    }

    fun testActualAutopopupGateLoadsTheRegisteredConfidence() {
        val file = myFixture.configureByText("confidence.sql", "SELECT to<caret>")
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        assertFalse(CompletionPhase.CommittingDocuments.shouldSkipAutoPopup(myFixture.editor, myFixture.file))
    }

    fun testOtherKeyedLanguageRegistrationsInstantiateFromTheirActualBeans() {
        for (name in listOf("lang.parserDefinition", "lang.syntaxHighlighterFactory", "lang.commenter", "lang.quoteHandler",
            "externalAnnotator", "completion.contributor", "completion.confidence", "annotator")) {
            val beans = ExtensionPointName.create<LanguageExtensionPoint<*>>("com.intellij.$name").extensionList
                .filter { it.language == ClickHouseDialect.id }
            assertTrue("Missing ClickHouse registration for $name", beans.isNotEmpty())
            for (bean in beans) {
                assertNotNull("$name must specify implementationClass", bean.implementationClass)
                assertEquals(bean.implementationClass, bean.instance.javaClass.name)
            }
        }
    }
}
