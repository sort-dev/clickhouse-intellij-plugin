package dev.sort.clickhouse.brikk.kotlin

import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkRelationFacts
import dev.brikk.house.intellij.BrikkRelationFactsResult
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.relation.ExternalRelationFacts
import dev.brikk.house.sql.intellij.relation.RelationColumnLabels
import dev.brikk.house.sql.intellij.relation.RelationFactsResult

private fun brikkFacts(): ExternalRelationFacts {
    val brikk = BrikkRelationFacts(BrikkDialect.CLICKHOUSE)
    val labels = BrikkRelationFacts.LABELS
    return ExternalRelationFacts.of(
        labels = RelationColumnLabels(
            declared = labels.declared, contract = labels.contract,
            declaredKind = SqlLookupKind.BRIKK_COLUMN, contractKind = SqlLookupKind.BRIKK_COLUMN,
        ),
        navigationTarget = brikk::navigationTarget,
    ) { file ->
        when (val result = brikk.facts(file)) {
            is BrikkRelationFactsResult.Available -> RelationFactsResult.Available(result.facts, result.revision)
            is BrikkRelationFactsResult.Pending -> RelationFactsResult.Pending(result.reason)
            null -> null
        }
    }
}

class ClickHouseBrikkRelationFacts : ExternalRelationFacts by brikkFacts()
