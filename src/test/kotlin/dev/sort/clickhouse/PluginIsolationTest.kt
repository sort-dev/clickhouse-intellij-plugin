package dev.sort.clickhouse

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.DynamicActionConfigurationCustomizer
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.LoggedErrorProcessor
import java.util.concurrent.CopyOnWriteArrayList

// The isolation worker excludes Brikk jars from its test classpath.
class PluginIsolationTest : BasePlatformTestCase() {
    private val backgroundErrors = CopyOnWriteArrayList<Throwable>()
    private var logging: AccessToken? = null
    private var expectedProbe: Throwable? = null

    override fun setUp() {
        super.setUp()
        logging = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> {
                backgroundErrors += t ?: AssertionError(message + "\n" + details.joinToString("\n"))
                return if (t === expectedProbe && t != null) Action.NONE else super.processError(category, message, details, t)
            }
        })
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            logging?.finish()
            if (backgroundErrors.isNotEmpty()) {
                throw AssertionError("Unexpected logged errors during isolation").apply { backgroundErrors.forEach(::addSuppressed) }
            }
        }
    }

    fun testErrorsLoggedOnPooledThreadsAreCaptured() {
        val probe = AssertionError("Expected isolation logging probe")
        expectedProbe = probe
        try {
            ApplicationManager.getApplication().executeOnPooledThread {
                Logger.getInstance(javaClass).error("Expected isolation logging probe", probe)
            }.get()
            assertTrue(backgroundErrors.contains(probe))
        } finally {
            expectedProbe = null
            backgroundErrors.remove(probe)
        }
    }

    fun testSharedLibrariesBelongToTheirBundlingPlugin() {
        val plugins = listOf("clickhouse") + System.getProperty("test.siblingPlugins", "")
            .split(',').filter(String::isNotBlank)
        val supportCopies = plugins.map { name ->
            val descriptor = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.sort.$name-intellij-plugin")))
            val loader = requireNotNull(descriptor.pluginClassLoader)
            val support = loader.loadClass("dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry")
            assertSame(name, loader, support.classLoader)
            assertSame(name, loader, loader.loadClass("dev.brikk.house.sql.BrikkSql").classLoader)
            support
        }
        for ((index, copy) in supportCopies.withIndex()) {
            for (peer in supportCopies.drop(index + 1)) assertNotSame(copy, peer)
        }
    }

    fun testInstalledExecuteChainsKeepEveryPluginsWrapper() {
        val plugins = listOf("clickhouse") + System.getProperty("test.siblingPlugins", "").split(',').filter(String::isNotBlank)
        for (id in EXECUTE_IDS) {
            var action = requireNotNull(ActionManager.getInstance().getAction(id))
            val chain = mutableListOf<Class<*>>()
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<AnAction, Boolean>())
            while (visited.add(action)) {
                chain += action.javaClass
                action = (action as? ActionWithDelegate<*>)?.delegate as? AnAction ?: break
            }
            for (name in plugins) {
                val loader = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.sort.$name-intellij-plugin"))?.pluginClassLoader)
                assertTrue("$name missing from $id: $chain", chain.any { it === loader.loadClass("dev.brikk.house.sql.intellij.CooperativeExecuteAction") })
            }
            assertTrue(chain.toString(), chain.last().name.startsWith("com.intellij.database.actions.RunQueryAction"))
        }
    }

    fun testRegistrationOrdersDelegateUnownedInputExactlyOnce() {
        val names = listOf("clickhouse") + System.getProperty("test.siblingPlugins", "").split(',').filter(String::isNotBlank)
        val loaders = names.map { name ->
            requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.sort.$name-intellij-plugin"))?.pluginClassLoader)
        }
        val registered = ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<DynamicActionConfigurationCustomizer>("com.intellij.dynamicActionConfigurationCustomizer").extensionList
        val configurations = loaders.map { loader -> registered.single { it.javaClass.classLoader === loader }.javaClass }
        val manager = ActionManager.getInstance()
        val originals = EXECUTE_IDS.associateWith { requireNotNull(manager.getAction(it)) }
        val event = AnActionEvent.createEvent(SimpleDataContext.getProjectContext(project), Presentation(), "ClickHouseIsolation", ActionUiKind.NONE, null)
        try {
            for (order in permutations(configurations)) {
                var delegated = 0
                for (id in EXECUTE_IDS) manager.replaceAction(id, object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        assertSame(event, e)
                        delegated++
                    }
                })
                val adapters = order.map { it.getConstructor().newInstance() as DynamicActionConfigurationCustomizer }
                try {
                    adapters.forEach { it.registerActions(manager) }
                    EXECUTE_IDS.forEach { requireNotNull(manager.getAction(it)).actionPerformed(event) }
                    assertEquals(EXECUTE_IDS.size, delegated)
                    adapters.first().unregisterActions(manager)
                    EXECUTE_IDS.forEach { requireNotNull(manager.getAction(it)).actionPerformed(event) }
                    assertEquals(EXECUTE_IDS.size * 2, delegated)
                } finally {
                    adapters.asReversed().forEach { it.unregisterActions(manager) }
                }
            }
        } finally {
            originals.forEach { (id, action) -> manager.replaceAction(id, action) }
        }
    }
}

private val EXECUTE_IDS = listOf("Console.Jdbc.Execute", "Console.Jdbc.Execute.2", "Console.Jdbc.Execute.3", "Console.Jdbc.Execute.Selection")

private fun <T> permutations(values: List<T>): List<List<T>> =
    if (values.isEmpty()) listOf(emptyList())
    else values.flatMapIndexed { index, value -> permutations(values.filterIndexed { other, _ -> other != index }).map { listOf(value) + it } }
