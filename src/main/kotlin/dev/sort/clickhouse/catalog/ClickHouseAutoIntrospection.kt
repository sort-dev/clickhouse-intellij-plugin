package dev.sort.clickhouse.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.components.service
import com.intellij.psi.PsiElement
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectService
import dev.brikk.house.sql.intellij.catalog.CatalogProfile
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.catalog.IntrospectBannerProvider
import dev.brikk.house.sql.intellij.catalog.QualifiedPathIntrospectContributor
import dev.brikk.house.sql.intellij.catalog.isHelperRequestor
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

/** Binds native table-reference PSI to the same shared queue used by qualified completion. */
class ClickHouseReferencedSchemaIntrospector : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val reference = element as? SqlReferenceExpression ?: return
        if (reference.referenceElementType.targetKind !in setOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MAT_VIEW)) return
        val qualifier = reference.qualifierExpression as? SqlReferenceExpression ?: return
        if (qualifier.qualifierExpression != null) return
        val name = qualifier.name ?: return
        val file = reference.containingFile
        val source = clickHouseDataSources.resolve(file).dataSource ?: return
        if (DatabaseConnectionManager.getInstance().activeConnections.none {
                it.connectionPoint.dataSource.uniqueId == source.uniqueId && !isHelperRequestor(it.requestor)
            }) return
        file.project.service<ClickHouseAutoIntrospectService>().requestPath(file, listOf(name))
    }
}
