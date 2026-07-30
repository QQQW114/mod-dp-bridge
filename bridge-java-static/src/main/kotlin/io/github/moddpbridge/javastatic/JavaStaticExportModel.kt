package io.github.moddpbridge.javastatic

import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.ObjectCreationExpr
import io.github.moddpbridge.model.ContentKind
import java.math.BigDecimal

internal data class ParsedJavaSource(
    val path: String,
    val unit: CompilationUnit?,
    val parseProblems: List<JavaStaticProblem>,
)

internal data class JavaStaticProblem(
    val code: String,
    val message: String,
    val sourcePath: String,
    val line: Int? = null,
    val column: Int? = null,
    val severity: JavaStaticSeverity = JavaStaticSeverity.WARNING,
    val details: String? = null,
)

internal enum class JavaStaticSeverity {
    INFO,
    WARNING,
    ERROR,
}

internal data class JavaRootCandidate(
    val kind: ContentKind,
    val symbol: String,
    val owner: TypeDeclaration<*>,
    val declaredType: String,
    val creation: ObjectCreationExpr,
    val sourcePath: String,
    val contentName: String?,
    val localConstants: Map<String, ConstantExpression> = emptyMap(),
) {
    val constructedType: String = creation.type.nameWithScope.substringAfterLast('.')
    val line: Int? = creation.begin.orElse(null)?.line
    val column: Int? = creation.begin.orElse(null)?.column
}

internal data class ConstantExpression(
    val ownerSimpleName: String,
    val symbol: String,
    val expression: Expression,
    val sourcePath: String,
)

internal data class JavaGeneratedDeclaration(
    val candidate: JavaRootCandidate,
    val targetType: String,
    val body: StaticJsonValue.ObjectValue,
    val problems: MutableList<JavaStaticProblem> = mutableListOf(),
    val localValues: MutableMap<String, StaticJsonValue> = linkedMapOf(),
    val forExpansionState: JavaStaticForExpansionState = JavaStaticForExpansionState(),
    var degraded: Boolean = false,
)

/**
 * Per-content guard for deterministic classic-for expansion.
 *
 * The state deliberately belongs to the generated declaration instead of an individual evaluator:
 * root initializers and all nested anonymous data builders must share one untrusted-input budget.
 */
internal class JavaStaticForExpansionState {
    var expandedIterations: Int = 0
    var activeDepth: Int = 0
}

internal data class EvalValue(
    val json: StaticJsonValue? = null,
    val number: BigDecimal? = null,
    val color: String? = null,
) {
    companion object {
        fun json(value: StaticJsonValue): EvalValue = EvalValue(json = value)
        fun number(value: BigDecimal): EvalValue = EvalValue(StaticJsonValue.Number(value), value)
        fun color(value: String): EvalValue = EvalValue(StaticJsonValue.StringValue(value), color = value)
    }
}

internal data class SymbolTarget(
    val kind: ContentKind,
    val localName: String,
    val targetName: String,
)
