package dev.sort.clickhouse

import com.intellij.credentialStore.OneTimeString
import com.intellij.database.access.DatabaseCredentials
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseDriverImpl
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.DataSourceConfigurable
import com.intellij.database.dataSource.DataSourceSchemasPanel
import com.intellij.database.dataSource.DataSourceScopeEditor
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.util.LoaderContext
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.psi.DataSourceManager
import com.intellij.database.introspection.IntrospectionScopes
import com.intellij.database.view.DbNamespaceNode
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.util.ui.UIUtil
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.dialects.DatabaseDialects
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.Dbms
import com.intellij.database.DataBus
import com.intellij.database.datagrid.DataAuditor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.connection.throwable.info.ErrorInfo
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.intellij.database.util.TreePattern
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.DasObject
import com.intellij.database.model.basic.BasicModel
import com.intellij.database.psi.DbTable
import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.session.DatabaseSessionManager
import com.intellij.database.console.session.DatabaseSessionViewService
import com.intellij.database.editor.DatabaseCompositeTableEditorProvider
import com.intellij.database.vfs.DatabaseElementVirtualFileImpl
import com.intellij.database.editor.DataGridContainer
import com.intellij.database.editor.DataHookupContainer
import com.intellij.database.datagrid.GridRequestSource
import com.intellij.database.datagrid.DataGridSessionClient
import com.intellij.database.extractors.DbExtractorsHelper
import com.intellij.database.extractors.ExtractionConfig
import com.intellij.database.extractors.FormatExtractorFactory
import com.intellij.database.csv.CsvFormats
import com.intellij.database.util.Out
import com.intellij.database.util.DdlBuilder
import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.run.ui.DataAccessType
import com.intellij.database.script.generator.ScriptCategory
import com.intellij.database.script.generator.ScriptingSingleModelTaskBuilder
import com.intellij.database.script.generator.ScriptGenerators
import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.util.PairConsumer
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import dev.brikk.house.sql.metadata.FunctionPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import com.intellij.testFramework.PlatformTestUtil
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.intellij.catalog.HelperConnectionPoint
import dev.brikk.house.sql.intellij.catalog.CatalogRowSource
import dev.brikk.house.sql.intellij.testing.JdbcCatalogRows
import dev.sort.clickhouse.catalog.ClickHouseCatalogHarvester
import dev.sort.clickhouse.catalog.ClickHouseLiveCatalogService
import dev.sort.clickhouse.catalog.ClickHouseDefinitionProvider
import dev.sort.clickhouse.catalog.ClickHouseAutoIntrospectService
import dev.sort.clickhouse.catalog.ClickHouseReferencedSchemaIntrospector
import dev.sort.clickhouse.catalog.ClickHouseIntrospectBannerProvider
import dev.sort.clickhouse.sql.ClickHouseCompletionProfile
import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.intellij.catalog.RefreshOutcome
import dev.brikk.house.sql.intellij.catalog.isHelperRequestor
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.verify.VerifyResult
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.validation.ClickHouseNativeErrorProfile
import java.net.URI
import java.net.URLClassLoader
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.sql.Driver
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import kotlinx.coroutines.runBlocking

class ClickHouseServerVerificationTest : ClickHousePlatformTestCase() {
    fun testNativeCancelStopsTheRunningRequestAndConsoleRemainsUsable() {
        val url = requireNotNull(System.getenv("BRIKK_CLICKHOUSE_URL"))
        val endpoint = URI.create("http:" + url.removePrefix("jdbc:clickhouse:"))
        require(endpoint.host == "127.0.0.1" && endpoint.port == 18123 && System.getenv("BRIKK_CLICKHOUSE_CONTAINER") == "brikk-ch-verify")
        val admin = URI.create("http://${endpoint.rawAuthority}/")
        fun query(sql: String): String = HttpClient.newHttpClient().use { client ->
            val response = client.send(HttpRequest.newBuilder(admin).timeout(java.time.Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { response.body() }
            response.body().trim()
        }
        val failures = mutableListOf<String>()
        val cancelDialogs = CopyOnWriteArrayList<String>()
        com.intellij.openapi.ui.TestDialogManager.setTestDialog({ message ->
            cancelDialogs += message
            com.intellij.openapi.ui.Messages.CANCEL
        }, testRootDisposable)
        for ((dbms, streaming) in listOf(ClickHouseDbms.CLICKHOUSE_BRIKK to false, Dbms.CLICKHOUSE to false,
            ClickHouseDbms.CLICKHOUSE_BRIKK to true, Dbms.CLICKHOUSE to true)) {
            val tag = "r2_${UUID.randomUUID().toString().replace("-", "")}"
            val password = "synthetic-cancellation-password"
            query("CREATE USER $tag IDENTIFIED WITH plaintext_password BY '$password'")
            query("GRANT SELECT, KILL QUERY ON *.* TO $tag")
            val jar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER") ?: System.getProperty("clickhouse.driver.fixture"))
            val drivers = DatabaseDriverManager.getInstance()
            val driver = requireNotNull(drivers.getDriver("clickhouse-sortdev")).copy(tag, false) as DatabaseDriverImpl
            val language = if (dbms === Dbms.CLICKHOUSE) CHouseDialect.INSTANCE else ClickHouseDialect
            driver.artifacts = emptyList()
            driver.setForcedDbms(dbms)
            driver.setSqlDialect(language.id)
            driver.additionalClasspathElements = listOf(jar, jar.resolveSibling("slf4j-api-2.0.7.jar"))
                .flatMap { SimpleClasspathElementFactory.createElements("jar://$it!/") }
            drivers.updateDriver(driver)
            val source = LocalDataSource().apply {
                name = tag
                databaseDriver = driver
                this.url = "$url?compress=false&decompress=false&ignore_unknown_config_key=true"
                username = tag
                passwordStorage = LocalDataSource.Storage.MEMORY
                isAutoSynchronize = false
                isKeepAlive = false
                model = ModelFactory.BLACK_HOLE.createModel(dbms)
                workingDir = Files.createDirectories(Path.of("build", "clickhouse-native-working-dir")).toAbsolutePath().toString()
            }
            DatabaseCredentials.getInstance().setPassword(source, OneTimeString(password))
            val sources = LocalDataSourceManager.getInstance(project)
            sources.addDataSource(source)
            val file = myFixture.addFileToProject("$tag.sql", "SELECT 1")
            setSqlDialectMapping(project, file.virtualFile, language)
            val session = DatabaseSessionManager.openSession(project, source, tag)
            val console = requireNotNull(com.intellij.database.console.JdbcConsoleProvider.attachConsole(project, session, file.virtualFile))
            val finished = ConcurrentHashMap.newKeySet<DataRequest>()
            val errors = CopyOnWriteArrayList<String>()
            val fetching = java.util.concurrent.atomic.AtomicBoolean()
            DataBus.addRootAuditor(project, object : DataAuditor {
                override fun fetchStarted(context: DataRequest.Context, resultSetIndex: Int) { fetching.set(true) }
                override fun error(context: DataRequest.Context, error: ErrorInfo) { errors += error.toString() }
                override fun requestFinished(context: DataRequest.Context) { finished += context.request }
            }, testRootDisposable)
            var runningId: String? = null
            fun processId(): String = query("SELECT query_id FROM system.processes WHERE user = '$tag' AND position(query, '$tag') > 0 FORMAT TSVRaw")
            fun event() = TestActionEvent.createTestEvent(SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, myFixture.file)
                .add(com.intellij.database.console.client.SessionClientHolder.CLIENT_KEY, console)
                .add(com.intellij.openapi.actionSystem.PlatformCoreDataKeys.CONTEXT_COMPONENT, myFixture.editor.contentComponent)
                .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile).add(CommonDataKeys.EDITOR, myFixture.editor).build())
            fun execute(sql: String) {
                WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText(sql) }
                PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
                FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
                myFixture.editor.caretModel.moveToOffset(0)
                myFixture.editor.selectionModel.removeSelection()
                requireNotNull(ActionManager.getInstance().getAction("Console.Jdbc.Execute")).actionPerformed(event())
            }
            try {
                myFixture.configureFromExistingVirtualFile(file.virtualFile)
                val expression = if (streaming) "sleepEachRow(0.1)" else "sum(sleepEachRow(0.1))"
                execute("SELECT /* $tag */ $expression FROM numbers(600) SETTINGS max_block_size = 1, max_threads = 1, max_execution_time = 25, http_response_buffer_size = 1")
                PlatformTestUtil.waitWithEventsDispatching("Synthetic cancellation request is running on ClickHouse", {
                    processId().also { if (it.isNotEmpty()) runningId = it }.isNotEmpty()
                }, 15)
                if (streaming) PlatformTestUtil.waitWithEventsDispatching("Native console is fetching the streaming result", { fetching.get() }, 10)
                assertFalse(session.isIdle)
                val cancel = requireNotNull(ActionManager.getInstance().getAction("Console.Jdbc.Cancel"))
                cancel.actionPerformed(event())
                val started = System.nanoTime()
                PlatformTestUtil.waitWithEventsDispatching("Cancel ends the native request or reaches the bounded observation deadline", {
                    processId().isEmpty() || System.nanoTime() - started > TimeUnit.SECONDS.toNanos(6)
                }, 8)
                val stopped = processId().isEmpty()
                println("R2_CANCEL dbms=$dbms driver=${jar.fileName} streaming=$streaming fetching=${fetching.get()} serverStopped=$stopped errors=$errors dialogs=$cancelDialogs")
                if (!stopped) {
                    if (dbms === ClickHouseDbms.CLICKHOUSE_BRIKK) {
                        failures += "$dbms streaming=$streaming: native Cancel left its server request running; errors=$errors"
                    }
                } else {
                    PlatformTestUtil.waitWithEventsDispatching("Cancelled console becomes idle", { session.isIdle }, 10)
                    finished.clear()
                    errors.clear()
                    execute("SELECT 42 AS cancellation_probe /* ${tag}_after */")
                    PlatformTestUtil.waitWithEventsDispatching("The same console executes its next request", { finished.isNotEmpty() && session.isIdle }, 10)
                    assertEmpty(errors)
                    query("SYSTEM FLUSH LOGS")
                    assertEquals("1", query("SELECT count() FROM system.query_log WHERE user = '$tag' AND type = 'QueryFinish' AND position(query, '${tag}_after') > 0"))
                    assertEquals("1", query("SELECT count() FROM system.query_log WHERE query_id = '${requireNotNull(runningId)}' AND type = 'ExceptionWhileProcessing' AND exception_code = 394"))
                }
            } finally {
                // Only this synthetic request is eligible for cleanup if the native cancellation failed.
                runningId?.let { id ->
                    require(Regex("[A-Za-z0-9_-]+").matches(id))
                    query("KILL QUERY WHERE query_id = '$id' SYNC")
                }
                PlatformTestUtil.waitWithEventsDispatching("Cancellation fixture releases its console", { session.isIdle }, 15)
                DatabaseSessionViewService.getInstance().destroyView(console)
                Disposer.dispose(console)
                Disposer.dispose(session)
                sources.removeDataSource(source)
                drivers.removeDriver(driver)
                query("DROP USER IF EXISTS $tag")
            }
        }
        assertEmpty(failures)
    }

    fun testNativeDataSourceSyncAndNamespaceSelection() = nativePaths(false)
    fun testReferenceAutoIntrospection() = nativePaths(true)
    fun testStockNativePaths() = nativePaths(false, Dbms.CLICKHOUSE)
    fun testCompletionAutoIntrospectionAfterClearingAllData() = nativePaths(false, clearedModel = true)
    fun testReferenceAutoIntrospectionAfterClearingAllData() = nativePaths(true, clearedModel = true)

    private fun nativePaths(checkReference: Boolean, dbms: Dbms = ClickHouseDbms.CLICKHOUSE_BRIKK, clearedModel: Boolean = false) {
        val language = if (dbms === Dbms.CLICKHOUSE) CHouseDialect.INSTANCE else ClickHouseDialect
        val url = requireNotNull(System.getenv("BRIKK_CLICKHOUSE_URL")?.takeIf(String::isNotBlank))
        val endpoint = URI.create("http:" + url.removePrefix("jdbc:clickhouse:"))
        val admin = URI.create("http://${endpoint.rawAuthority}/")
        fun query(sql: String) = HttpClient.newHttpClient().use { client ->
            val response = client.send(HttpRequest.newBuilder(admin).POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { response.body() }
            response.body()
        }
        val namespace = "native_sync_${UUID.randomUUID().toString().replace("-", "")}"
        val deferredNamespace = "${namespace}_deferred"
        val user = "${namespace}_user"
        val password = "native-sync-synthetic-password"
        query("CREATE USER $user IDENTIFIED WITH plaintext_password BY '$password'")
        Disposer.register(testRootDisposable) { query("DROP USER IF EXISTS $user") }
        query("GRANT SELECT, SHOW DATABASES, SHOW TABLES, SHOW COLUMNS, SHOW DICTIONARIES ON *.* TO $user")
        query("CREATE DATABASE $namespace")
        Disposer.register(testRootDisposable) { query("DROP DATABASE IF EXISTS $namespace") }
        query("CREATE DATABASE $deferredNamespace")
        Disposer.register(testRootDisposable) { query("DROP DATABASE IF EXISTS $deferredNamespace") }
        query("CREATE TABLE $deferredNamespace.deferred_table (id UInt64) ENGINE=Memory")
        query("CREATE TABLE $namespace.sync_table (id UInt64, label String) ENGINE=Memory")
        query("INSERT INTO $namespace.sync_table VALUES (7, 'native path smoke')")
        val jar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER") ?: System.getProperty("clickhouse.driver.fixture"))
        val drivers = DatabaseDriverManager.getInstance()
        val driver = requireNotNull(drivers.getDriver("clickhouse-sortdev")).copy("native-sync-driver-$namespace", false) as DatabaseDriverImpl
        driver.artifacts = emptyList()
        driver.setForcedDbms(dbms)
        driver.setSqlDialect(language.id)
        driver.additionalClasspathElements = listOf(jar, jar.resolveSibling("slf4j-api-2.0.7.jar"))
            .flatMap { SimpleClasspathElementFactory.createElements("jar://$it!/") }
        drivers.updateDriver(driver)
        Disposer.register(testRootDisposable) { drivers.removeDriver(driver) }
        val source = LocalDataSource().apply {
            name = "Native ClickHouse sync"
            databaseDriver = driver
            this.url = url + (if ('?' in url) "&" else "?") + "compress=false&decompress=false&ignore_unknown_config_key=true"
            username = user
            passwordStorage = LocalDataSource.Storage.MEMORY
            isAutoSynchronize = false
            isKeepAlive = false
            workingDir = Files.createDirectories(Path.of("build", "clickhouse-native-working-dir")).toAbsolutePath().toString()
            model = ModelFactory.BLACK_HOLE.createModel(dbms)
        }
        DatabaseCredentials.getInstance().setPassword(source, OneTimeString(password))
        val sources = LocalDataSourceManager.getInstance(project)
        sources.addDataSource(source)
        Disposer.register(testRootDisposable) { sources.removeDataSource(source) }
        assertSame(dbms, source.dbms)
        PlatformTestUtil.waitWithEventsDispatching("ClickHouse datasource model loaded", {
            DataSourceManager.byDataSource(project, source)?.isLoading(source) != true
        }, 30)
        fun sync(context: LoaderContext) {
            val task = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                runBlocking { DataSourceSyncManager.getInstance().tryPerformSync(context, false, false) }
            })
            try {
                PlatformTestUtil.waitWithEventsDispatching("Native ClickHouse introspection", { task.isDone }, 90)
                assertNotNull("Sync must not be skipped", task.get(1, TimeUnit.SECONDS))
            } finally {
                if (!task.isDone) task.cancel(true)
            }
        }
        sync(LoaderContext.selectListNamespacesTask(project, source))
        val root = (source.model as BasicModel).root as CHouseRoot
        assertNotNull(root.schemas.get(namespace))
        val properties = DataSourceConfigurable(sources, source, project)
        try {
            val schemas = DataSourceSchemasPanel(properties)
            schemas.reset(source)
            val scopeEditor = DataSourceSchemasPanel::class.java.getDeclaredField("myScopeEditor").let { field ->
                field.isAccessible = true
                field.get(schemas) as DataSourceScopeEditor
            }
            scopeEditor.setInitialModel(source, source.model as BasicModel)
            val tree = requireNotNull(UIUtil.findComponentOfType(schemas.component, CheckboxTreeBase::class.java))
            PlatformTestUtil.waitWithEventsDispatching("ClickHouse namespace settings tree", {
                (tree.model.root as CheckedTreeNode).breadthFirstEnumeration().asSequence()
                    .filterIsInstance<CheckedTreeNode>().any { (it.userObject as? DbNamespaceNode)?.name == namespace }
            }, 20)
            val node = (tree.model.root as CheckedTreeNode).breadthFirstEnumeration().asSequence()
                .filterIsInstance<CheckedTreeNode>().first { (it.userObject as? DbNamespaceNode)?.name == namespace }
            tree.setNodeState(node, true)
            val deferredNode = (tree.model.root as CheckedTreeNode).breadthFirstEnumeration().asSequence()
                .filterIsInstance<CheckedTreeNode>().first { (it.userObject as? DbNamespaceNode)?.name == deferredNamespace }
            tree.setNodeState(deferredNode, true)
            schemas.saveSettings(source)
            assertTrue(IntrospectionScopes.exportState((source.model as BasicModel).metaModel, source.introspectionScope).contains(namespace))
        } finally {
            properties.disposeUIResources()
        }
        fun open(sql: String): com.intellij.psi.PsiFile {
            val file = myFixture.configureByText("native-auto.sql", sql)
            setSqlDialectMapping(project, file.virtualFile, language)
            SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
                TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(source.uniqueId)))))
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            return myFixture.file
        }
        if (clearedModel) {
            val connection = runBlocking {
                DatabaseConnectionManager.getInstance().build(project, source).setRequestor(ConnectionRequestor.Anonymous())
                    .setAskPassword(false).create()
            }
            requireNotNull(connection).use {
                assertTrue("Cleared-model trigger has a real user connection", DatabaseConnectionManager.getInstance().activeConnections.any {
                    it.connectionPoint.dataSource.uniqueId == source.uniqueId && !isHelperRequestor(it.requestor)
                })
                fun clearAndDiscover() {
                    val clear = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                        // The native ForgetModelAction uses this same clear/update pair, without a sync.
                        source.clearModel()
                        requireNotNull(DataSourceStorage.getProjectStorage(project)).updateDataSource(source)
                    })
                    PlatformTestUtil.waitWithEventsDispatching("Native introspection data is cleared", { clear.isDone }, 30)
                    clear.get(1, TimeUnit.SECONDS)
                    PlatformTestUtil.waitWithEventsDispatching("Cleared datasource model loads locally", {
                        DataSourceManager.byDataSource(project, source)?.isLoading(source) != true
                    }, 30)
                    DbPsiFacade.getInstance(project).clearCaches()
                    val emptyRoot = (source.model as BasicModel).root as CHouseRoot
                    assertTrue("Clear must remove the database listing, not only its tables", emptyRoot.schemas.isEmpty())
                    val auto = project.service<ClickHouseAutoIntrospectService>()
                    val file = open(if (checkReference) "SELECT t.id FROM $namespace.sync_table t" else "SELECT * FROM $namespace.<caret>")
                    assertSame("The trigger must resolve the cleared datasource", source, auto.profile.dataSources.resolve(file).dataSource)
                    val psiRoot = (requireNotNull(DbPsiFacade.getInstance(project).findDataSource(source.uniqueId)).model as BasicModel).root as CHouseRoot
                    assertTrue("The shared path resolver must see an empty model too", psiRoot.schemas.isEmpty())
                    if (checkReference) {
                        val annotator = LanguageAnnotators.INSTANCE.allForLanguage(file.language)
                            .filterIsInstance<ClickHouseReferencedSchemaIntrospector>().single()
                        val holder = AnnotationHolderImpl(AnnotationSession(file), false)
                        PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java)
                            .forEach { reference -> holder.runAnnotatorWithContext(reference, annotator) }
                        holder.assertAllAnnotationsCreated()
                    } else {
                        myFixture.completeBasic()
                    }
                    assertNotNull("Empty-root ${if (checkReference) "reference" else "completion"} must queue discovery before targeted deepening",
                        auto.pending(file.virtualFile))
                    assertNotNull("Root listing must show the registered introspection banner",
                        ClickHouseIntrospectBannerProvider().collectNotificationData(project, file.virtualFile))
                    PlatformTestUtil.waitWithEventsDispatching("Only the referenced database loads after root listing", {
                        auto.pending(file.virtualFile) == null &&
                            ((source.model as BasicModel).root as CHouseRoot).schemas.get(namespace)?.tables?.get("sync_table")?.columns?.size() == 2
                    }, 60)
                    assertTrue("An unrelated database must remain un-introspected",
                        requireNotNull(((source.model as BasicModel).root as CHouseRoot).schemas.get(deferredNamespace)).tables.isEmpty())
                    open("SELECT t.<caret> FROM $namespace.sync_table t")
                    val items = myFixture.completeBasic().orEmpty().filter { it.lookupString in setOf("id", "label") }
                    assertSameElements(listOf("id", "label"), items.map { it.lookupString })
                    for (item in items) {
                        open("SELECT t.<caret> FROM $namespace.sync_table t")
                        myFixture.completeBasic()
                        val lookup = requireNotNull(com.intellij.codeInsight.lookup.LookupManager.getActiveLookup(myFixture.editor))
                        lookup.setCurrentItem(lookup.items.single { it.lookupString == item.lookupString })
                        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
                        assertEquals("SELECT t.${item.lookupString} FROM $namespace.sync_table t", myFixture.editor.document.text)
                    }
                }
                clearAndDiscover()
                clearAndDiscover() // Completed claims from the first round must not survive a clear.
                val auto = project.service<ClickHouseAutoIntrospectService>()
                assertFalse("ClickHouse does not expose client transactions", auto.profile.clientTransactions)
                val missing = "${namespace}_missing"
                val missingFile = open("SELECT * FROM $missing.<caret>")
                repeat(3) {
                    myFixture.completeBasic()
                    assertFalse("Unknown databases must not start another listing", auto.requestPath(missingFile, listOf(missing)))
                    assertFalse("Unknown tables in a loaded database must not restart introspection",
                        auto.requestPath(missingFile, listOf(namespace, "missing_table")))
                    assertNull(auto.pending(missingFile.virtualFile))
                }
                val currentRoot = (source.model as BasicModel).root as CHouseRoot
                assertNull("Missing databases must not be fabricated", currentRoot.schemas.get(missing))
                assertNull("Missing tables must not be fabricated", requireNotNull(currentRoot.schemas.get(namespace)).tables.get("missing_table"))
            }
            return
        }
        assertTrue(requireNotNull(root.schemas.get(namespace)).tables.isEmpty())
        val resolveInspection = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".SqlResolveInspection") }
        myFixture.enableInspections(resolveInspection.instantiateTool() as LocalInspectionTool)
        if (dbms === Dbms.CLICKHOUSE) sync(LoaderContext.selectGeneralTask(project, source))
        val autoFile = open("SELECT * FROM $namespace.<caret>")
        myFixture.completeBasic()
        val auto = project.service<ClickHouseAutoIntrospectService>()
        PlatformTestUtil.waitWithEventsDispatching("Completion introspects the ClickHouse database", {
            auto.pending(autoFile.virtualFile) == null &&
                ((source.model as BasicModel).root as CHouseRoot).schemas.get(namespace)?.tables?.get("sync_table")?.columns?.size() == 2
        }, 60)
        if (checkReference) {
            val userConnection = runBlocking {
                DatabaseConnectionManager.getInstance().build(project, source).setRequestor(ConnectionRequestor.Anonymous())
                    .setAskPassword(false).create()
            }
            requireNotNull(userConnection).use {
                assertTrue("Reference trigger needs a live user connection", DatabaseConnectionManager.getInstance().activeConnections.any {
                    it.connectionPoint.dataSource.uniqueId == source.uniqueId && !isHelperRequestor(it.requestor)
                })
                open("SELECT d.id FROM $deferredNamespace.deferred_table d")
                val references = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlReferenceExpression::class.java)
                assertTrue(references.map { it.text to it.referenceElementType.targetKind }.toString(),
                    references.any { it.name == "deferred_table" && it.qualifierExpression != null })
                val annotator = LanguageAnnotators.INSTANCE.allForLanguage(myFixture.file.language)
                    .filterIsInstance<ClickHouseReferencedSchemaIntrospector>().single()
                val holder = AnnotationHolderImpl(AnnotationSession(myFixture.file), false)
                references.forEach { holder.runAnnotatorWithContext(it, annotator) }
                holder.assertAllAnnotationsCreated()
                PlatformTestUtil.waitWithEventsDispatching("References introspect an unloaded ClickHouse table; kinds=${references.map { it.referenceElementType.targetKind }}", {
                    auto.pending(myFixture.file.virtualFile) == null &&
                        ((source.model as BasicModel).root as CHouseRoot).schemas.get(deferredNamespace)?.tables?.get("deferred_table")?.columns?.size() == 1
                }, 30)
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
            }
        }
        sync(LoaderContext.selectGeneralTask(project, source))
        DbPsiFacade.getInstance(project).clearCaches()
        val schema = requireNotNull(((source.model as BasicModel).root as CHouseRoot).schemas.get(namespace))
        val table = requireNotNull(schema.tables.get("sync_table"))
        assertSameElements(listOf("id", "label"), table.columns.map { it.name })
        val scripting = ScriptingSingleModelTaskBuilder(source.model as BasicModel, ScriptCategory.CREATE_DEFINITION).apply {
            addElements(listOf(table))
            setReformat(false)
        }.build()
        val ddl = ScriptGenerators.makeScript(project, scripting).getScriptText()
        assertTrue(ddl, ddl.contains("CREATE TABLE", ignoreCase = true) && ddl.contains("id UInt64") && ddl.contains("label String"))
        val file = myFixture.addFileToProject("native-console.sql", "SELECT id, label FROM $namespace.sync_table")
        setSqlDialectMapping(project, file.virtualFile, language)
        val consoleSession = DatabaseSessionManager.openSession(project, source, "Native ClickHouse console smoke")
        val console = JdbcConsole.newConsole(project).forFile(file.virtualFile).fromDataSource(source).useSession(consoleSession).build()
        try {
            assertTrue(console.isValid)
            assertSame(dbms, console.dataSource.dbms)
            val finished = ConcurrentHashMap.newKeySet<DataRequest>()
            val errors = CopyOnWriteArrayList<String>()
            DataBus.addRootAuditor(project, object : DataAuditor {
                override fun error(context: DataRequest.Context, error: ErrorInfo) { errors += error.toString() }
                override fun requestFinished(context: DataRequest.Context) { finished += context.request }
            }, testRootDisposable)
            myFixture.configureFromExistingVirtualFile(file.virtualFile)
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.setText("USE $namespace;")
            }
            PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
            FileDocumentManager.getInstance().saveDocument(myFixture.editor.document)
            myFixture.editor.selectionModel.removeSelection()
            myFixture.editor.caretModel.moveToOffset(0)
            val event = TestActionEvent.createTestEvent(SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.PSI_FILE, myFixture.file)
                .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile).add(CommonDataKeys.EDITOR, myFixture.editor).build())
            requireNotNull(ActionManager.getInstance().getAction("Console.Jdbc.Execute")).actionPerformed(event)
            PlatformTestUtil.waitWithEventsDispatching("Native ClickHouse USE finishes", { finished.isNotEmpty() }, 60)
            assertEmpty(errors)
            // The stock HTTP driver does not retain a raw USE across requests. Keep that baseline visible.
            assertEquals("default", console.searchPath?.current?.name)
            console.setSearchPath(SearchPath.of(ObjectPath.create(namespace, ObjectKind.SCHEMA)))
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.setText("USE $namespace;\nSELECT s.id FROM sync_table s")
            }
            PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
            assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.setText("USE $namespace;\nSELECT s. FROM sync_table s")
            }
            PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("s. FROM") + 2)
            val columns = myFixture.completeBasic().orEmpty().filter { it.lookupString in setOf("id", "label") }
            assertSameElements(listOf("id", "label"), columns.map { it.lookupString })
        } finally {
            DatabaseSessionViewService.getInstance().destroyView(console)
            Disposer.dispose(console)
            Disposer.dispose(consoleSession)
        }
        val dbTable = requireNotNull(DbPsiFacade.getInstance(project).findDataSource(source.uniqueId)?.findElement(table)) as DbTable
        val tableFile = requireNotNull(DatabaseElementVirtualFileImpl.findFile(dbTable, false))
        val editorProvider = DatabaseCompositeTableEditorProvider()
        assertTrue(editorProvider.accept(project, tableFile))
        val editor = editorProvider.createEditor(project, tableFile)
        try {
            editor.selectNotify()
            val grid = (editor as DataGridContainer).dataGrid
            val request = GridRequestSource(object : GridRequestSource.RequestPlace {})
            (editor as DataHookupContainer).dataHookup.loader.loadFirstPage(request)
            PlatformTestUtil.waitWithEventsDispatching("Native ClickHouse table editor reads rows", {
                grid.getDataModel(DataAccessType.DATABASE_DATA).rowCount == 1 || request.errorOccurred()
            }, 60)
            assertFalse(request.errorMessage, request.errorOccurred())
            assertEquals(listOf("id", "label"), grid.getDataModel(DataAccessType.DATABASE_DATA).columns.map { it.name })
            val data = grid.getDataModel(DataAccessType.DATABASE_DATA)
            val helper = DbExtractorsHelper.INSTANCE
            val csv = FormatExtractorFactory(CsvFormats.CSV_FORMAT.value)
                .createExtractor(helper.createExtractorConfig(grid, grid.objectFormatter))
            val output = NativeSmokeOut()
            val extraction = requireNotNull(csv).startExtraction(output, data.columns, "SELECT id, label", ExtractionConfig())
            extraction.addData(data.rows)
            extraction.complete()
            val exported = output.text()
            assertTrue(exported, exported.contains("7") && exported.contains("native path smoke"))
        } finally {
            val client = (editor as DataHookupContainer).dataHookup as DataGridSessionClient
            PlatformTestUtil.waitWithEventsDispatching("Native grid request finishes before session disposal", {
                client.session.isIdle
            }, 60)
            client.session.detach(client)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Disposer.dispose(editor)
        }
        val connected = runBlocking {
            DatabaseConnectionManager.getInstance().build(project, source).setRequestor(ConnectionRequestor.Anonymous())
                .setAskPassword(false).create()
        }
        requireNotNull(connected).use { handle ->
            val core = handle.get()
            assertSame(dbms, core.dbms)
            val dialect = requireNotNull(DatabaseDialects.findByDbms(dbms))
            JdbcNativeUtil.computeRemote { core.remoteConnection.setSchema(namespace) }
            assertEquals(namespace, dialect.tryToLoadSearchPath(core)?.current?.name)
            val definitions = mutableListOf<Any>()
            ClickHouseDefinitionProvider().fetchSources(listOf(table), core, PairConsumer { _, text -> definitions += text })
            assertTrue(definitions.toString(), definitions.single() is String && (definitions.single() as String).startsWith("CREATE TABLE"))
            val explain = requireNotNull(requireNotNull(ExplainPlanProvider.getDefaultProvider(source)).createRawExplainTask(source, false))
                .sqlExplainPlan(DdlBuilder().configureFor(dbTable), "SELECT id FROM $namespace.sync_table").statement
            assertTrue(explain, explain.startsWith("EXPLAIN", ignoreCase = true))
            JdbcNativeUtil.computeRemote {
                val statement = core.remoteConnection.createStatement()
                try {
                    val plan = statement.executeQuery(explain)
                    try { assertTrue(plan.next()) } finally { plan.close() }
                } finally { statement.close() }
            }
        }
    }

    private class NativeSmokeOut : Out {
        private val bytes = ByteArrayOutputStream()
        private val writer = OutputStreamWriter(bytes, Charsets.UTF_8)
        override fun toOutputStream() = bytes.also { writer.flush() }
        override fun toAppendable() = writer
        override fun append(value: ByteArray): Out = apply { writer.flush(); bytes.write(value) }
        override fun appendText(value: CharSequence): Out = apply { writer.append(value) }
        override fun sizeInBytes(): Long { writer.flush(); return bytes.size().toLong() }
        override fun length(): Long = text().length.toLong()
        fun text(): String { writer.flush(); return bytes.toString(Charsets.UTF_8) }
    }

    fun testCatalogHarvesterReadsOnlySmallSystemInventories() {
        val url = requireNotNull(System.getenv("BRIKK_CLICKHOUSE_URL")?.takeIf(String::isNotBlank))
        val jar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER") ?: System.getProperty("clickhouse.driver.fixture"))
        val jars = arrayOf(jar.toUri().toURL(), jar.resolveSibling("slf4j-api-2.0.7.jar").toUri().toURL())
        URLClassLoader(jars, javaClass.classLoader).use { loader ->
            val driver = loader.loadClass("com.clickhouse.jdbc.ClickHouseDriver").getConstructor().newInstance() as Driver
            val properties = Properties().apply {
                setProperty("user", "default")
                setProperty("password", "")
                setProperty("compress", "false")
                setProperty("decompress", "false")
            }
            requireNotNull(driver.connect(url, properties)).use { connection ->
                val jdbc = JdbcCatalogRows(connection, 5)
                val queried = linkedMapOf<String, MutableList<List<String?>>>()
                val started = System.nanoTime()
                val harvest = requireNotNull(ClickHouseCatalogHarvester.harvest(CatalogRowSource { sql, read ->
                    val values = queried.getOrPut(sql, ::ArrayList)
                    jdbc.forEachRow("$sql SETTINGS readonly = 1") { row ->
                        val columns = if (sql == ClickHouseCatalogHarvester.SQL_FUNCTIONS) 3 else 1
                        values += (1..columns).map(row::string)
                        read(row)
                    }
                }))
                assertSameElements(queried.keys, listOf(ClickHouseCatalogHarvester.SQL_VERSION, ClickHouseCatalogHarvester.SQL_FUNCTIONS, ClickHouseCatalogHarvester.SQL_TABLE_FUNCTIONS))
                val expected = queried.getValue(ClickHouseCatalogHarvester.SQL_FUNCTIONS).filter { !it[0].orEmpty().startsWith("__") || it[2] == "SQLUserDefined" }
                    .map { requireNotNull(it[0]) to if (it[1] == "1") FunctionKind.AGGREGATE else FunctionKind.SCALAR } +
                    queried.getValue(ClickHouseCatalogHarvester.SQL_TABLE_FUNCTIONS).mapNotNull { row -> row[0]?.takeUnless { it.startsWith("__") }?.let { it to FunctionKind.TABLE_VALUED } }
                assertSameElements(expected.distinct(), harvest.shared.functions.map { it.def.name to it.def.kind })
                assertFalse(harvest.shared.signatures)
                println("ClickHouse ${harvest.shared.serverVersion}: harvested ${harvest.shared.functions.size} name/kind pairs in ${(System.nanoTime() - started) / 1_000_000} ms using read-only system queries")
            }
        }
    }

    fun testStoredLoginVerifiesExportSyntaxWithoutWritingAnObject() {
        val url = requireNotNull(System.getenv("BRIKK_CLICKHOUSE_URL")?.takeIf(String::isNotBlank))
        val endpoint = URI.create("http:" + url.removePrefix("jdbc:clickhouse:"))
        val admin = URI.create("http://${endpoint.rawAuthority}/")
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val user = "ch1_user_$suffix"
        val marker = "ch1_export_$suffix"
        val liveFunction = "gate_live_$suffix"
        val password = "ch1-synthetic-password"
        fun adminQuery(sql: String): String = HttpClient.newHttpClient().use { client ->
            val response = client.send(HttpRequest.newBuilder(admin).POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { response.body() }
            response.body().trim()
        }
        adminQuery("CREATE USER $user IDENTIFIED WITH plaintext_password BY '$password'")
        Disposer.register(testRootDisposable) { adminQuery("DROP USER IF EXISTS $user") }
        adminQuery("CREATE FUNCTION $liveFunction AS (x) -> x + 1")
        Disposer.register(testRootDisposable) { adminQuery("DROP FUNCTION IF EXISTS $liveFunction") }
        adminQuery("GRANT SELECT, INSERT, FILE, S3, SHOW TABLES, SHOW DICTIONARIES ON *.* TO $user")
        val namespace = "ch2_namespace_$suffix"
        adminQuery("CREATE DATABASE $namespace")
        Disposer.register(testRootDisposable) { adminQuery("DROP DATABASE IF EXISTS $namespace") }
        adminQuery("CREATE TABLE $namespace.definition_table (id UInt64, value String) ENGINE=Memory")
        adminQuery("CREATE VIEW $namespace.definition_view AS SELECT id, value FROM $namespace.definition_table")
        adminQuery("CREATE MATERIALIZED VIEW $namespace.definition_mv ENGINE=Memory AS SELECT id, value FROM $namespace.definition_table")
        adminQuery("CREATE DICTIONARY $namespace.definition_dictionary (id UInt64, value String) PRIMARY KEY id SOURCE(CLICKHOUSE(HOST '127.0.0.1' PORT 9000 USER 'default' DB '$namespace' TABLE 'definition_table')) LAYOUT(HASHED()) LIFETIME(0)")

        val jar = Path.of(System.getenv("BRIKK_CLICKHOUSE_DRIVER") ?: System.getProperty("clickhouse.driver.fixture"))
        assertTrue("Missing driver fixture: $jar", Files.isRegularFile(jar))
        val manager = DatabaseDriverManager.getInstance()
        val driver = requireNotNull(manager.getDriver("clickhouse-sortdev")).copy("ch1-driver-$suffix", false) as DatabaseDriverImpl
        driver.artifacts = emptyList()
        driver.additionalClasspathElements = listOf(jar, jar.resolveSibling("slf4j-api-2.0.7.jar"))
            .flatMap { SimpleClasspathElementFactory.createElements("jar://$it!/") }
        manager.updateDriver(driver)
        Disposer.register(testRootDisposable) { manager.removeDriver(driver) }
        val source = LocalDataSource().apply {
            name = "CH.1 server test $suffix"
            databaseDriver = driver
            this.url = url + if ('?' in url) "&compress=true&decompress=true" else "?compress=true&decompress=true"
            username = user
            passwordStorage = LocalDataSource.Storage.MEMORY
            isAutoSynchronize = false
            isKeepAlive = false
            workingDir = Files.createDirectories(Path.of("build", "clickhouse-native-working-dir")).toAbsolutePath().toString()
        }
        DatabaseCredentials.getInstance().setPassword(source, OneTimeString(password))
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        Disposer.register(testRootDisposable) { LocalDataSourceManager.getInstance(project).removeDataSource(source) }
        val properties = mapOf("compress" to "false", "decompress" to "false", "ignore_unknown_config_key" to "true")
        val point = HelperConnectionPoint(source, properties)
        val session = runBlocking {
            DatabaseConnectionManager.getInstance().build(project, point).setRequestor(ConnectionRequestor.Anonymous()).setAskPassword(false).create()
        }
        requireNotNull(session).use {
            val live = ApplicationManager.getApplication().service<ClickHouseLiveCatalogService>()
            val refresh = ApplicationManager.getApplication().executeOnPooledThread(Callable { runBlocking { live.refresh(project, source) } })
            try {
                PlatformTestUtil.waitWithEventsDispatching("ClickHouse live function inventory", { refresh.isDone }, 30)
                assertTrue(refresh.get(1, TimeUnit.SECONDS).toString(), refresh.get() is RefreshOutcome.Refreshed)
            } finally {
                if (!refresh.isDone) refresh.cancel(true)
            }
            assertTrue(live.selection(source).source is CatalogSource.Live)
            val completionFile = myFixture.configureByText("live-functions-$suffix.sql", "SELECT ${liveFunction}<caret>")
            setSqlDialectMapping(project, completionFile.virtualFile, ClickHouseDialect)
            val completionSettings = CodeInsightSettings.getInstance()
            val previousAutocomplete = completionSettings.AUTOCOMPLETE_ON_CODE_COMPLETION
            completionSettings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
            val candidates = try {
                myFixture.completeBasic().orEmpty().filter { (it.`object` as? FunctionPresentation)?.name == liveFunction }
            } finally {
                completionSettings.AUTOCOMPLETE_ON_CODE_COMPLETION = previousAutocomplete
            }
            assertEquals(candidates.toString(), 1, candidates.size)
            myFixture.lookup.currentItem = candidates.single()
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals("SELECT $liveFunction()", myFixture.editor.document.text)
            val model = ModelFactory.BLACK_HOLE.createModel(ClickHouseDbms.CLICKHOUSE_BRIKK)
            val schema = (model.root as CHouseRoot).schemas.createOrGet(namespace)
            val definitions = listOf(
                schema to "CREATE DATABASE",
                schema.tables.createOrGet("definition_table") to "CREATE TABLE",
                schema.views.createOrGet("definition_view") to "CREATE VIEW",
                schema.matViews.createOrGet("definition_mv") to "CREATE MATERIALIZED VIEW",
                schema.tables.createOrGet("definition_dictionary").apply { engine = "Dictionary" } to "CREATE DICTIONARY",
            )
            val loaded = linkedMapOf<DasObject, Any>()
            ClickHouseDefinitionProvider().fetchSources(definitions.map { it.first }, it.get(), PairConsumer { obj, text -> loaded[obj] = text })
            for ((obj, prefix) in definitions) {
                val definition = loaded[obj]
                assertTrue("$obj returned $definition", definition is String && definition.startsWith(prefix))
            }
            val dialect = requireNotNull(DatabaseDialects.findByDbms(ClickHouseDbms.CLICKHOUSE_BRIKK))
            val path = SearchPath.of(ObjectPath.create(namespace, ObjectKind.SCHEMA))
            val use = requireNotNull(dialect.sqlSetSearchPath(path))
            val statement = requireNotNull(JdbcNativeUtil.computeRemote { it.get().remoteConnection.createStatement() })
            try {
                JdbcNativeUtil.computeRemote { statement.execute(use) }
                println("ClickHouse JDBC 0.9.8 read-back after raw USE: ${dialect.tryToLoadSearchPath(it.get())?.current?.name}")
                assertTrue(dialect.shouldSwitchThroughJdbc(ObjectKind.SCHEMA))
                JdbcNativeUtil.computeRemote { it.get().remoteConnection.setSchema(namespace) }
                assertEquals(namespace, dialect.tryToLoadSearchPath(it.get())?.current?.name)
            } finally {
                JdbcNativeUtil.closeRemoteStatementSafe(statement)
            }
            source.initScript = "INSERT INTO FUNCTION file('$marker-startup.tsv', 'TSV', 'id UInt64') SELECT 7"
            assertTrue(source.url.orEmpty().contains("compress=true"))
            val rejectedSql = "SELECT 'unicode 😀' FRM unknown_table"
            val rejectedFile = myFixture.configureByText("server-rejection-$suffix.sql", rejectedSql)
            setSqlDialectMapping(project, rejectedFile.virtualFile, ClickHouseDialect)
            val rejected = myFixture.doHighlighting(HighlightSeverity.ERROR)
                .filter { it.description.orEmpty().startsWith("ClickHouse:") }
            assertEquals(rejected.toString(), 1, rejected.size)
            val failedToken = rejectedSql.indexOf("unknown_table")
            assertEquals(failedToken, rejected.single().startOffset)
            assertEquals(failedToken + "unknown_table".length, rejected.single().endOffset)
            val statements = listOf(
                "INSERT INTO FUNCTION s3('https://objects.example.invalid/$marker/{_partition_id}/export.parquet', 'CH1_FAKE_ACCESS', 'CH1_FAKE_SECRET', 'Parquet')",
                "INSERT INTO FUNCTION file('$marker-{_partition_id}.parquet', 'Parquet', 'id UInt64, ts DateTime')",
            ).map { head -> "$head PARTITION BY concat('month=', toString(toMonth(ts))) SELECT number AS id, toDateTime('2026-02-03') AS ts FROM numbers(1) SETTINGS max_threads = 1 FORMAT Parquet;" }
            for (sql in statements) {
                val file = myFixture.configureByText("server-$suffix.sql", sql)
                setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
                assertContainsElements(ClickHouseCompletionProfile.functions(myFixture.file).map { it.name }, "toDateTime", "numbers", "s3")
                assertFalse(password in source.url.orEmpty())
                val validator = requireNotNull(ClickHouseNativeErrorProfile.validator(myFixture.file))
                val checkedSql = ClickHouseNativeErrorProfile.verificationText(myFixture.file, sql.removeSuffix(";"))
                val verdict = ApplicationManager.getApplication().executeOnPooledThread(Callable { validator.verify(checkedSql, null) })
                try {
                    PlatformTestUtil.waitWithEventsDispatching("ClickHouse parse-only verification", { verdict.isDone }, 30)
                    assertEquals(VerifyResult.Accepted, verdict.get(1, TimeUnit.SECONDS))
                } finally {
                    if (!verdict.isDone) verdict.cancel(true)
                }
                assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
                adminQuery("SYSTEM FLUSH LOGS")
                val hex = checkedSql.toByteArray(Charsets.UTF_8).toHexString(HexFormat.UpperCase)
                val logged = adminQuery("SELECT count() FROM system.query_log WHERE user = '$user' AND type = 'QueryFinish' AND startsWith(query, 'SELECT formatQuery(unhex(') AND position(query, '$hex') > 0")
                assertTrue("Verification did not use the stored login", logged.toLong() > 0)
            }
            adminQuery("SYSTEM FLUSH LOGS")
            assertEquals("0", adminQuery("SELECT count() FROM system.query_log WHERE user = '$user' AND position(query, '$marker') > 0"))
            System.getenv("BRIKK_CLICKHOUSE_CONTAINER")?.let { container ->
                val check = ProcessBuilder("docker", "exec", container, "find", "/var/lib/clickhouse/user_files", "-name", "$marker*", "-print")
                    .redirectErrorStream(true).start()
                val files = check.inputStream.bufferedReader().use { it.readText() }
                assertEquals(files, 0, check.waitFor())
                assertTrue("Verification wrote an object: $files", files.isBlank())
            }
        }
    }
}
