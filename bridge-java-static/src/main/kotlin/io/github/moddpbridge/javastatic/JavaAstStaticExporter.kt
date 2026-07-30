package io.github.moddpbridge.javastatic

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.InitializerDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.expr.ArrayCreationExpr
import com.github.javaparser.ast.expr.ArrayInitializerExpr
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.CharLiteralExpr
import com.github.javaparser.ast.expr.ConditionalExpr
import com.github.javaparser.ast.expr.DoubleLiteralExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.IntegerLiteralExpr
import com.github.javaparser.ast.expr.LambdaExpr
import com.github.javaparser.ast.expr.LongLiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NullLiteralExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.UnaryExpr
import com.github.javaparser.ast.expr.VariableDeclarationExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.EmptyStmt
import com.github.javaparser.ast.stmt.ExpressionStmt
import com.github.javaparser.ast.stmt.ForStmt
import com.github.javaparser.ast.stmt.Statement
import io.github.moddpbridge.converter.ConvertedFileStatus
import io.github.moddpbridge.converter.DetectedSourceKind
import io.github.moddpbridge.converter.StaticExportContext
import io.github.moddpbridge.converter.StaticExportResult
import io.github.moddpbridge.converter.StaticGeneratedFile
import io.github.moddpbridge.converter.StaticOutputNamespace
import io.github.moddpbridge.converter.StaticSourceExporter
import io.github.moddpbridge.converter.StaticSourceOutcome
import io.github.moddpbridge.model.ContentDisposition
import io.github.moddpbridge.model.ContentKind
import io.github.moddpbridge.model.ContentResult
import io.github.moddpbridge.model.Diagnostic
import io.github.moddpbridge.model.DiagnosticSeverity
import io.github.moddpbridge.model.SourceLocation
import io.github.moddpbridge.model.ValidationStage
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.math.pow

/**
 * Deterministic Java source -> v159 data-content exporter.
 *
 * This implementation deliberately evaluates a small, auditable language rather than Java as a
 * whole. It never compiles, loads, reflects over, or executes input classes. The first production
 * slice emits Item, Liquid/CellLiquid and StatusEffect declarations; Block and Unit declarations
 * are inventoried and reported individually until their larger object graphs are supported.
 */
class JavaAstStaticExporter : StaticSourceExporter {
    override val id: String = "java-ast-v1597"

    override fun export(context: StaticExportContext): StaticExportResult {
        if (context.detectedKind != DetectedSourceKind.MOD) return StaticExportResult()
        val javaFiles = context.files.filter { source ->
            source.path.endsWith(".java", ignoreCase = true) &&
                // Files below assets/ are resources, not Java compilation units. Repositories
                // often keep historical snippets there; parsing them creates false failures and
                // can export code that the original Mod never executed.
                !source.path.startsWith("assets/", ignoreCase = true)
        }
        if (javaFiles.isEmpty()) return StaticExportResult()

        val parser = JavaParser(
            ParserConfiguration()
                .setCharacterEncoding(StandardCharsets.UTF_8)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE),
        )
        val parsed = javaFiles.sortedBy { it.path }.map { source ->
            val result = parser.parse(source.text())
            val problems = result.problems.map { problem ->
                val position = problem.location.flatMap { it.toRange() }.map { it.begin }.orElse(null)
                JavaStaticProblem(
                    code = "JAVA_PARSE_PROBLEM",
                    message = problem.message,
                    sourcePath = source.path,
                    line = position?.line,
                    column = position?.column,
                    severity = JavaStaticSeverity.ERROR,
                )
            }
            ParsedJavaSource(source.path, result.result.orElse(null), problems)
        }

        val engine = ExportEngine(context.modNamespace ?: context.slug, parsed)
        return engine.export()
    }
}

private class ExportEngine(
    private val sourceNamespace: String,
    private val sources: List<ParsedJavaSource>,
) {
    private val problems = mutableListOf<JavaStaticProblem>()
    private val candidates = mutableListOf<JavaRootCandidate>()
    private val constants = linkedMapOf<String, ConstantExpression>()
    private val symbols = linkedMapOf<String, SymbolTarget>()
    private val contentFields = linkedMapOf<String, MutableMap<String, StaticJsonValue>>()
    private val promotedUnits = linkedMapOf<String, JavaGeneratedDeclaration>()
    private val promotingUnitNames = mutableSetOf<String>()

    fun export(): StaticExportResult {
        sources.forEach { problems += it.parseProblems }
        collectDeclarations()
        val generated = mutableListOf<JavaGeneratedDeclaration>()
        val contentResults = mutableListOf<ContentResult>()

        candidates.sortedWith(compareBy<JavaRootCandidate>({ kindOrder(it.kind) }, { it.sourcePath }, { it.line ?: Int.MAX_VALUE }))
            .forEach { candidate ->
                val declaration = buildDeclaration(candidate)
                if (declaration == null) {
                    contentResults += unsupportedResult(
                        candidate,
                        "The content name or root initializer could not be statically evaluated.",
                        "JAVA_CONTENT_EXPORT_FAILED",
                    )
                } else {
                    generated += declaration
                    contentResults += generatedResult(declaration)
                }
            }

        promotedUnits.values
            .sortedWith(compareBy({ it.candidate.sourcePath }, { it.candidate.line ?: Int.MAX_VALUE }, { it.candidate.contentName }))
            .forEach { declaration ->
                generated += declaration
                contentResults += generatedResult(declaration, promoted = true)
            }

        val generatedFiles = generated.map { declaration ->
            val candidate = declaration.candidate
            StaticGeneratedFile(
                outputPath = outputPath(candidate.kind, candidate.contentName!!),
                bytes = renderRootJson(declaration.body),
                sourcePaths = listOf(candidate.sourcePath),
                // Keep source-mod naming semantics here. The shared field-aware rewriter knows
                // which values need final dp-* qualification and which (e.g. Weapon.name) are
                // automatically prefixed later by ContentParser.
                namespace = StaticOutputNamespace.SOURCE,
                reason = "Generated by the deterministic Java AST v159.7 exporter.",
            )
        }
        val allProblems = (problems + generated.flatMap { it.problems }).distinct()
        val outputsBySource = generatedFiles.flatMap { file -> file.sourcePaths.map { it to file.outputPath } }
            .groupBy({ it.first }, { it.second })
        val candidateCounts = candidates.groupingBy { it.sourcePath }.eachCount()
        val sourceOutcomes = sources.map { source ->
            val sourceProblems = allProblems.filter { it.sourcePath == source.path }
            val outputPaths = outputsBySource[source.path].orEmpty().distinct().sorted()
            val hasParseError = source.parseProblems.any { it.severity == JavaStaticSeverity.ERROR }
            val excluded = isPolicyExcludedSource(source.path)
            val status = when {
                hasParseError -> ConvertedFileStatus.FAILED
                outputPaths.isNotEmpty() -> ConvertedFileStatus.NORMALIZED
                excluded -> ConvertedFileStatus.EXCLUDED
                else -> ConvertedFileStatus.UNSUPPORTED
            }
            val reason = when {
                hasParseError -> "Java source parsing failed; no code was executed."
                outputPaths.isNotEmpty() -> "Statically exported ${outputPaths.size} v159 data content file(s) without executing Java."
                excluded -> "Java source only implements product-scope-excluded campaign, planet, sector, tech-tree or map behavior."
                candidateCounts[source.path].orZero() > 0 ->
                    "Content declarations were inventoried, but this exporter slice has not emitted their object graphs yet."
                else -> "Java helper/runtime code has no directly exportable declaration in the supported static subset."
            }
            StaticSourceOutcome(
                sourcePath = source.path,
                status = status,
                reason = reason,
                outputPaths = outputPaths,
                diagnosticCodes = sourceProblems.map { it.code }.distinct(),
            )
        }

        val pending = contentResults.count { it.disposition == ContentDisposition.UNSUPPORTED }
        val diagnostics = allProblems.map(::toDiagnostic).toMutableList()
        if (pending > 0) {
            diagnostics += Diagnostic(
                code = "JAVA_STATIC_EXPORT_INCOMPLETE",
                severity = DiagnosticSeverity.ERROR,
                message = "The Java AST exporter inventoried declarations that are not emitted by the current implementation slice.",
                stage = ValidationStage.STRUCTURE,
                details = "$pending of ${contentResults.size} declaration(s) remain unsupported; generated files are retained for testing.",
                suggestion = "Review contentResults and continue implementing Block/Unit/Weapon/Bullet builder mappings.",
            )
        }
        if (generatedFiles.isNotEmpty()) {
            diagnostics += Diagnostic(
                code = "JAVA_STATIC_EXPORT_APPLIED",
                severity = DiagnosticSeverity.INFO,
                message = "Java content declarations were converted without compiling or executing the input Mod.",
                stage = ValidationStage.STRUCTURE,
                details = "${generatedFiles.size} generated content file(s).",
            )
        }

        return StaticExportResult(
            generatedFiles = generatedFiles,
            sourceOutcomes = sourceOutcomes,
            contentResults = contentResults,
            diagnostics = diagnostics,
            logs = listOf(
                "Parsed ${sources.size} Java source file(s); found ${candidates.size} top-level content declaration(s).",
                "Promoted ${promotedUnits.size} nested UnitType declaration(s) into independent data content files.",
                "Generated ${generatedFiles.size} Item/Liquid/Status/Unit/Block file(s); $pending declaration(s) remain pending.",
                "No Java class was compiled, loaded or executed.",
            ),
            metadata = mapOf(
                "javaFiles" to sources.size.toString(),
                "declarations" to candidates.size.toString(),
                "promotedUnits" to promotedUnits.size.toString(),
                "generatedContents" to generatedFiles.size.toString(),
                "pendingContents" to pending.toString(),
            ),
        )
    }

    private fun collectDeclarations() {
        sources.forEach { source ->
            val unit = source.unit ?: return@forEach
            unit.findAll(TypeDeclaration::class.java).forEach { owner ->
                val declaredContent = owner.fields.asSequence()
                    .filter(FieldDeclaration::isStatic)
                    .flatMap { field ->
                        field.variables.asSequence().mapNotNull { variable ->
                            MindustryJavaMappings.classifyDeclaredType(variable.type.asString())?.let { kind ->
                                variable.nameAsString to (kind to variable.type.asString())
                            }
                        }
                    }.toMap()

                collectConstants(owner, source.path, declaredContent.keys)
                owner.fields.forEach { field ->
                    if (!field.isStatic) return@forEach
                    field.variables.forEach { variable ->
                        val info = declaredContent[variable.nameAsString] ?: return@forEach
                        val creation = variable.initializer.orElse(null)?.unwrapCreation() ?: return@forEach
                        addCandidate(owner, source.path, variable.nameAsString, info.first, info.second, creation)
                    }
                }
                owner.findAll(AssignExpr::class.java).asSequence()
                    .filter { it.operator == AssignExpr.Operator.ASSIGN }
                    .filter { it.findAncestor(ObjectCreationExpr::class.java).isEmpty }
                    .forEach { assignment ->
                        val symbol = assignment.target.fieldSymbol(owner.nameAsString) ?: return@forEach
                        val info = declaredContent[symbol] ?: return@forEach
                        val creation = assignment.value.unwrapCreation() ?: return@forEach
                        addCandidate(owner, source.path, symbol, info.first, info.second, creation)
                    }
            }
        }
        candidates.distinctBy { Triple(it.owner.nameAsString, it.symbol, it.sourcePath) }.forEach { candidate ->
            val localName = candidate.contentName ?: return@forEach
            val target = SymbolTarget(candidate.kind, localName, "dp-$localName")
            symbols["${candidate.owner.nameAsString}.${candidate.symbol}"] = target
            symbols.putIfAbsent(candidate.symbol, target)
        }
    }

    private fun collectConstants(
        owner: TypeDeclaration<*>,
        sourcePath: String,
        contentSymbols: Set<String>,
    ) {
        owner.fields.filter(FieldDeclaration::isStatic).forEach { field ->
            field.variables.forEach { variable ->
                if (variable.nameAsString in contentSymbols) return@forEach
                val expression = variable.initializer.orElse(null) ?: return@forEach
                val constant = ConstantExpression(owner.nameAsString, variable.nameAsString, expression, sourcePath)
                constants["${owner.nameAsString}.${variable.nameAsString}"] = constant
                constants.putIfAbsent(variable.nameAsString, constant)
            }
        }
    }

    private fun addCandidate(
        owner: TypeDeclaration<*>,
        sourcePath: String,
        symbol: String,
        kind: ContentKind,
        declaredType: String,
        creation: ObjectCreationExpr,
    ) {
        val name = contentName(kind, creation)
        candidates += JavaRootCandidate(
            kind = kind,
            symbol = symbol,
            owner = owner,
            declaredType = declaredType,
            creation = creation,
            sourcePath = sourcePath,
            contentName = name,
            localConstants = visibleLocalConstants(owner, sourcePath, creation),
        )
        if (name == null) {
            val position = creation.begin.orElse(null)
            problems += JavaStaticProblem(
                code = "JAVA_CONTENT_NAME_UNRESOLVED",
                message = "Content name expression could not be statically resolved: ${creation.arguments.firstOrNull()}",
                sourcePath = sourcePath,
                line = position?.line,
                column = position?.column,
                severity = JavaStaticSeverity.ERROR,
            )
        }
    }

    /**
     * Collects method-local immutable expressions that are lexically visible at a content
     * declaration. Java content loaders often reuse a local ItemStack[] across several UnitPlan
     * constructors. These values are data, not executable code, and can be evaluated by the same
     * deterministic expression subset as static fields.
     *
     * Restricting the scan to the creation's nearest block avoids leaking variables from nested
     * lambdas/anonymous initializers or unrelated methods. Only declarations before the content
     * creation are visible in Java, so later variables are deliberately ignored.
     */
    private fun visibleLocalConstants(
        owner: TypeDeclaration<*>,
        sourcePath: String,
        creation: ObjectCreationExpr,
    ): Map<String, ConstantExpression> {
        val block = creation.findAncestor(BlockStmt::class.java).orElse(null) ?: return emptyMap()
        val creationPosition = creation.begin.orElse(null) ?: return emptyMap()
        return block.findAll(VariableDeclarator::class.java).asSequence()
            .filter { variable -> variable.findAncestor(BlockStmt::class.java).orElse(null) === block }
            .filter { variable ->
                val position = variable.begin.orElse(null) ?: return@filter false
                position.line < creationPosition.line ||
                    (position.line == creationPosition.line && position.column < creationPosition.column)
            }
            .mapNotNull { variable ->
                val expression = variable.initializer.orElse(null) ?: return@mapNotNull null
                val constant = ConstantExpression(owner.nameAsString, variable.nameAsString, expression, sourcePath)
                variable.nameAsString to constant
            }
            .toMap(LinkedHashMap())
    }

    private fun contentName(kind: ContentKind, creation: ObjectCreationExpr): String? {
        val first = creation.arguments.firstOrNull() ?: return null
        return when {
            creation.type.nameAsString == "OreBlock" && first is FieldAccessExpr -> {
                // Candidate symbols are bound only after declaration collection. OreBlock(Item)
                // follows a fixed vanilla naming convention, so derive it without symbol lookup.
                "ore-${MindustryJavaMappings.camelToKebab(first.nameAsString)}"
            }
            else -> constantString(first)
        }
    }

    private fun constantString(expression: Expression): String? = when (expression) {
        is StringLiteralExpr -> expression.value
        is EnclosedExpr -> constantString(expression.inner)
        is BinaryExpr -> if (expression.operator == BinaryExpr.Operator.PLUS) {
            val left = constantString(expression.left)
            val right = constantString(expression.right)
            if (left != null && right != null) left + right else null
        } else null
        is MethodCallExpr -> if (expression.nameAsString == "name" && expression.arguments.size == 1) {
            constantString(expression.arguments[0])
        } else null
        else -> null
    }

    private fun buildDeclaration(
        candidate: JavaRootCandidate,
        inheritedLocalValues: Map<String, StaticJsonValue> = emptyMap(),
    ): JavaGeneratedDeclaration? {
        if (candidate.contentName == null) return null
        val (targetType, rootDegraded) = MindustryJavaMappings.targetRootType(candidate)
        val body = StaticJsonValue.ObjectValue()
        val declaration = JavaGeneratedDeclaration(candidate, targetType, body, degraded = rootDegraded)
        declaration.localValues.putAll(inheritedLocalValues)
        if (candidate.kind == ContentKind.LIQUID && targetType != "Liquid") {
            body.fields["type"] = StaticJsonValue.StringValue(targetType)
        }
        if (candidate.kind == ContentKind.BLOCK) {
            body.fields["type"] = StaticJsonValue.StringValue(targetType)
        }
        if (candidate.kind == ContentKind.UNIT && targetType != "UnitType") {
            body.fields["template"] = StaticJsonValue.StringValue(targetType)
        }
        applyConstructorArguments(declaration)
        candidate.creation.anonymousClassBody.orElse(null)?.forEach { member ->
            when (member) {
                is InitializerDeclaration -> processBlock(member.body, declaration)
                is MethodDeclaration -> {
                    declaration.degraded = true
                    declaration.problems += problem(
                        "JAVA_METHOD_OVERRIDE_OMITTED",
                        "Java method '${member.nameAsString}' cannot be represented by v159 data content and was omitted.",
                        candidate,
                        member,
                    )
                }
                else -> Unit
            }
        }
        finalizeDataCompatibility(declaration)
        if (candidate.kind == ContentKind.UNIT && "type" !in body.fields) {
            when (targetType) {
                "TankUnitType" -> body.fields["type"] = StaticJsonValue.StringValue("tank")
                "MissileUnitType" -> body.fields["type"] = StaticJsonValue.StringValue("missile")
                else -> if ((body.fields["flying"] as? StaticJsonValue.Bool)?.value == true) {
                    body.fields["type"] = StaticJsonValue.StringValue("flying")
                }
            }
        }
        contentFields["${candidate.owner.nameAsString}.${candidate.symbol}"] = body.fields
        contentFields.putIfAbsent(candidate.symbol, body.fields)
        return declaration
    }

    /**
     * DataPatcher rejects a newly instantiated UnlockableContent nested inside another content
     * graph. Java frequently embeds MissileUnitType in BulletType.spawnUnit, so materialize that
     * constructor as an independent unit file and replace the field value with a content id.
     */
    private fun promoteNestedUnit(
        expression: ObjectCreationExpr,
        parent: JavaGeneratedDeclaration,
        visibleLocalValues: Map<String, StaticJsonValue>,
    ): String? {
        val sourceType = expression.type.nameWithScope.substringAfterLast('.')
        val name = contentName(ContentKind.UNIT, expression)
        if (name == null) {
            parent.degraded = true
            parent.problems += problem(
                "JAVA_NESTED_UNIT_NAME_UNRESOLVED",
                "Nested $sourceType could not be promoted because its content name is not a deterministic string.",
                parent.candidate,
                expression,
                expression.arguments.firstOrNull()?.toString(),
            )
            return null
        }

        val topLevel = candidates.firstOrNull { it.kind == ContentKind.UNIT && it.contentName == name }
        if (topLevel != null) {
            parent.degraded = true
            parent.problems += problem(
                "JAVA_NESTED_UNIT_NAME_COLLISION",
                "Nested $sourceType '$name' collides with an existing top-level unit; the existing unit reference was retained and inline fields were omitted.",
                parent.candidate,
                expression,
            )
            return "dp-$name"
        }

        promotedUnits[name]?.let { existing ->
            if (existing.candidate.creation.toString() != expression.toString()) {
                parent.degraded = true
                parent.problems += problem(
                    "JAVA_NESTED_UNIT_NAME_COLLISION",
                    "Multiple different nested unit declarations use '$name'; the first promoted declaration was retained.",
                    parent.candidate,
                    expression,
                )
            }
            return "dp-$name"
        }

        if (!promotingUnitNames.add(name)) {
            parent.degraded = true
            parent.problems += problem(
                "JAVA_NESTED_UNIT_PROMOTION_CYCLE",
                "Nested unit promotion for '$name' is recursive and was stopped.",
                parent.candidate,
                expression,
            )
            return null
        }

        return try {
            val synthetic = JavaRootCandidate(
                kind = ContentKind.UNIT,
                symbol = "${parent.candidate.symbol}\$nested\$$name",
                owner = parent.candidate.owner,
                declaredType = sourceType,
                creation = expression,
                sourcePath = parent.candidate.sourcePath,
                contentName = name,
                localConstants = parent.candidate.localConstants,
            )
            val declaration = buildDeclaration(synthetic, visibleLocalValues)
            if (declaration == null) {
                parent.degraded = true
                parent.problems += problem(
                    "JAVA_NESTED_UNIT_PROMOTION_FAILED",
                    "Nested $sourceType '$name' could not be materialized as a top-level unit.",
                    parent.candidate,
                    expression,
                )
                null
            } else {
                promotedUnits[name] = declaration
                val position = expression.begin.orElse(null)
                parent.problems += JavaStaticProblem(
                    code = "JAVA_NESTED_UNIT_PROMOTED",
                    message = "Nested $sourceType '$name' was promoted to content/units/$name.hjson.",
                    sourcePath = parent.candidate.sourcePath,
                    line = position?.line,
                    column = position?.column,
                    severity = JavaStaticSeverity.INFO,
                )
                "dp-$name"
            }
        } finally {
            promotingUnitNames.remove(name)
        }
    }

    /**
     * Applies target-only compatibility rules that need the complete root object graph. Java's
     * no-argument DrawLiquidTile intentionally selects the building's current liquid at runtime,
     * while DataPatcher rejects a null drawLiquid. For deterministic crafters, the declared output
     * liquid is the closest faithful target value; otherwise replace only that draw layer with a
     * DrawDefault and report the loss explicitly rather than emitting an unloadable object.
     */
    private fun finalizeDataCompatibility(declaration: JavaGeneratedDeclaration) {
        val inferredLiquid = firstLiquidName(declaration.body.fields["outputLiquid"])
            ?: firstLiquidName(declaration.body.fields["outputLiquids"])

        fun rewrite(value: StaticJsonValue) {
            when (value) {
                is StaticJsonValue.ArrayValue -> value.values.forEach(::rewrite)
                is StaticJsonValue.ObjectValue -> {
                    val type = (value.fields["type"] as? StaticJsonValue.StringValue)?.value
                    if (type == "DrawLiquidTile" && "drawLiquid" !in value.fields) {
                        if (inferredLiquid != null) {
                            value.fields["drawLiquid"] = StaticJsonValue.StringValue(inferredLiquid)
                            declaration.problems += problem(
                                "JAVA_DRAW_LIQUID_INFERRED",
                                "DrawLiquidTile() was bound to the block output liquid '$inferredLiquid' for v159.7 DataPatcher compatibility.",
                                declaration.candidate,
                                declaration.candidate.creation,
                            )
                        } else {
                            value.fields.clear()
                            value.fields["type"] = StaticJsonValue.StringValue("DrawDefault")
                            declaration.degraded = true
                            declaration.problems += problem(
                                "JAVA_DRAW_LIQUID_TILE_DEGRADED",
                                "DrawLiquidTile() had no statically identifiable liquid and was replaced with DrawDefault to keep the content loadable.",
                                declaration.candidate,
                                declaration.candidate.creation,
                            )
                        }
                    }
                    value.fields.values.toList().forEach(::rewrite)
                }
                else -> Unit
            }
        }

        rewrite(declaration.body)
    }

    private fun firstLiquidName(value: StaticJsonValue?): String? = when (value) {
        is StaticJsonValue.StringValue -> value.value.substringBefore('/').takeIf(String::isNotBlank)
        is StaticJsonValue.ObjectValue ->
            (value.fields["liquid"] as? StaticJsonValue.StringValue)?.value
                ?: value.fields.values.firstNotNullOfOrNull(::firstLiquidName)
        is StaticJsonValue.ArrayValue -> value.values.firstNotNullOfOrNull(::firstLiquidName)
        else -> null
    }

    private fun applyConstructorArguments(declaration: JavaGeneratedDeclaration) {
        val candidate = declaration.candidate
        val args = candidate.creation.arguments
        val evaluator = evaluator(declaration)
        when (candidate.kind) {
            ContentKind.ITEM, ContentKind.LIQUID -> {
                if (args.size >= 2) {
                    evaluator.eval(args[1])?.json?.let { declaration.body.fields["color"] = it }
                        ?: evaluator.omit("color", args[1], "Constructor color could not be evaluated.")
                }
            }
            ContentKind.BLOCK -> when (candidate.constructedType) {
                "Floor" -> if (args.size >= 2) {
                    evaluator.eval(args[1])?.json?.let { declaration.body.fields["variants"] = it }
                        ?: evaluator.omit("variants", args[1], "Floor constructor variant count could not be evaluated.")
                }
                "OreBlock" -> (if (args.firstOrNull() is StringLiteralExpr) args.getOrNull(1) else args.firstOrNull())?.let { item ->
                    evaluator.eval(item)?.json?.let { declaration.body.fields["itemDrop"] = it }
                        ?: evaluator.omit("itemDrop", item, "OreBlock item constructor argument could not be evaluated.")
                }
            }
            else -> Unit
        }
    }

    private fun processBlock(block: BlockStmt, declaration: JavaGeneratedDeclaration) {
        block.statements.forEach { statement -> processStatement(statement, declaration) }
    }

    private fun processStatement(statement: Statement, declaration: JavaGeneratedDeclaration) {
        when (statement) {
            is ExpressionStmt -> processExpressionStatement(statement.expression, declaration)
            is ForStmt -> processForStatement(statement, declaration)
            is EmptyStmt -> Unit
            else -> {
                declaration.degraded = true
                declaration.problems += problem(
                    "JAVA_STATEMENT_UNSUPPORTED",
                    "Statement type '${statement.javaClass.simpleName}' is outside the supported static initializer subset.",
                    declaration.candidate,
                    statement,
                    details = statement.toString().take(500),
                )
            }
        }
    }

    private fun processForStatement(statement: ForStmt, declaration: JavaGeneratedDeclaration) {
        val result = expandStaticForLoop(
            statement = statement,
            locals = declaration.localValues,
            state = declaration.forExpansionState,
            evaluate = { expression -> evaluator(declaration).eval(expression) },
            process = { nested -> processStatement(nested, declaration) },
        )
        if (result.failure != null) {
            declaration.degraded = true
            declaration.problems += problem(
                result.failureCode ?: "JAVA_FOR_LOOP_UNSUPPORTED",
                result.failure,
                declaration.candidate,
                statement,
                statement.toString().take(500),
            )
        } else {
            val position = statement.begin.orElse(null)
            declaration.problems += JavaStaticProblem(
                code = "JAVA_FOR_LOOP_EXPANDED",
                message = "A deterministic for loop was expanded into ${result.iterations} static data operation(s).",
                sourcePath = declaration.candidate.sourcePath,
                line = position?.line,
                column = position?.column,
                severity = JavaStaticSeverity.INFO,
            )
        }
    }

    private fun processExpressionStatement(expression: Expression, declaration: JavaGeneratedDeclaration) {
        when (expression) {
            is AssignExpr -> applyAssignment(expression, declaration)
            is MethodCallExpr -> applyBuilderCall(expression, declaration)
            is VariableDeclarationExpr -> rememberLocalValues(expression, declaration)
            else -> {
                declaration.degraded = true
                declaration.problems += problem(
                    "JAVA_EXPRESSION_STATEMENT_UNSUPPORTED",
                    "Initializer expression is not a supported assignment or builder call.",
                    declaration.candidate,
                    expression,
                    details = expression.toString().take(500),
                )
            }
        }
    }

    private fun rememberLocalValues(expression: VariableDeclarationExpr, declaration: JavaGeneratedDeclaration) {
        expression.variables.forEach { variable ->
            val initializer = variable.initializer.orElse(null) ?: return@forEach
            val value = evaluator(declaration).eval(initializer)?.json
            if (value != null) {
                declaration.localValues[variable.nameAsString] = value
            } else {
                declaration.degraded = true
                declaration.problems += problem(
                    "JAVA_LOCAL_VALUE_OMITTED",
                    "Local initializer '${variable.nameAsString}' could not be evaluated; later references may be omitted.",
                    declaration.candidate,
                    variable,
                    initializer.toString().take(500),
                )
            }
        }
    }

    private fun applyAssignment(assignment: AssignExpr, declaration: JavaGeneratedDeclaration) {
        if (assignment.operator != AssignExpr.Operator.ASSIGN) {
            declaration.degraded = true
            declaration.problems += problem(
                "JAVA_ASSIGNMENT_OPERATOR_UNSUPPORTED",
                "Compound assignment '${assignment.operator}' was not evaluated.",
                declaration.candidate,
                assignment,
            )
            return
        }
        val targets = assignmentPaths(assignment.target)
        if (targets.isEmpty()) {
            declaration.degraded = true
            declaration.problems += problem(
                "JAVA_FIELD_TARGET_UNRESOLVED",
                "Assignment target could not be mapped to a data field.",
                declaration.candidate,
                assignment,
                details = assignment.target.toString(),
            )
            return
        }
        val valueExpression = unwrapChainedAssignmentValue(assignment.value, targets)
        val remainingTargets = targets.toMutableList()
        consumptionCall(valueExpression)?.let { (consume, boosted) ->
            applyConsumptionCall(consume, declaration, boosted)
            // Java often stores the returned Consume handle (e.g. coolant = consumeCoolant(...))
            // for later runtime mutation. DP represents it under `consumes`; emitting the handle
            // again as an ordinary block field would be invalid and adds no gameplay data.
            remainingTargets.clear()
        }
        remainingTargets.filter { it.size == 1 && it.single() == "constructor" }.forEach { target ->
            val unitType = MindustryJavaMappings.unitEntityType(valueExpression.toString())
            if (unitType != null) {
                declaration.body.fields["type"] = StaticJsonValue.StringValue(unitType)
            } else {
                evaluator(declaration).omit("type", valueExpression, "Unit constructor could not be mapped to a built-in data-pack entity type.")
            }
            remainingTargets.remove(target)
        }
        remainingTargets.filter { it.size == 1 && it.single() in setOf("controller", "aiController", "defaultController") }
            .forEach { target ->
                val controller = controllerType(valueExpression)
                if (controller != null) {
                    // DataPatcher exposes UnitType.aiController's Prov field through the special
                    // `controller` data field. Emitting `aiController` makes ContentParser try to
                    // deserialize the class name as a Prov and fail during apply.
                    val outputField = if (target.single() == "aiController") "controller" else target.single()
                    declaration.body.fields[outputField] = StaticJsonValue.StringValue(controller)
                } else {
                    evaluator(declaration).omit(target.single(), valueExpression, "Unit AI controller callback could not be reduced to a built-in controller class.")
                }
                remainingTargets.remove(target)
            }
        if (remainingTargets.isEmpty()) return
        val crossTargets = remainingTargets.associateWith(::crossContentTarget)
        val evaluated = if (valueExpression is ThisExpr && crossTargets.values.any { it != null }) {
            EvalValue.json(StaticJsonValue.StringValue("dp-${declaration.candidate.contentName}"))
        } else {
            evaluator(declaration).eval(valueExpression)
        }
        if (evaluated?.json == null) {
            remainingTargets.forEach { evaluator(declaration).omit(it.joinToString("."), valueExpression, "Field expression could not be evaluated.") }
            return
        }
        remainingTargets.forEach { path ->
            val field = path.last()
            val crossTarget = crossTargets[path]
            val targetSourceType = crossTarget?.sourceType ?: declaration.candidate.constructedType
            if (field in MindustryJavaMappings.unsupportedFields(targetSourceType)) {
                val rejectedByTarget = field in MindustryJavaMappings.unsupportedDataFields[targetSourceType].orEmpty()
                declaration.degraded = true
                declaration.problems += problem(
                    if (rejectedByTarget) "JAVA_TARGET_FIELD_OMITTED" else "JAVA_CUSTOM_FIELD_OMITTED",
                    if (rejectedByTarget) {
                        "Field '$field' is not accepted by the v159.7 DataPatcher and was omitted."
                    } else {
                        "Custom field '$field' has no v159 data-content equivalent and was omitted."
                    },
                    declaration.candidate,
                    assignment,
                )
            } else {
                val value = if (field == "effect" && evaluated.json == StaticJsonValue.Null) {
                    // Effect fields use Fx.none in data content; JSON null is rejected by the
                    // target parser even though Java content commonly assigns null directly.
                    StaticJsonValue.StringValue("none")
                } else {
                    evaluated.json
                }
                if (crossTarget == null) {
                    setJsonPath(declaration.body, path, value)
                } else {
                    setJsonPath(crossTarget.fields, crossTarget.remainingPath, value)
                    val position = assignment.begin.orElse(null)
                    declaration.problems += JavaStaticProblem(
                        code = "JAVA_CROSS_CONTENT_ASSIGNMENT_APPLIED",
                        message = "A deterministic assignment to already generated content '${crossTarget.symbolKey}' was applied.",
                        sourcePath = declaration.candidate.sourcePath,
                        line = position?.line,
                        column = position?.column,
                        severity = JavaStaticSeverity.INFO,
                        details = assignment.toString().take(500),
                    )
                }
            }
        }
    }

    private data class CrossContentTarget(
        val symbolKey: String,
        val fields: MutableMap<String, StaticJsonValue>,
        val remainingPath: List<String>,
        val sourceType: String,
    )

    private fun crossContentTarget(path: List<String>): CrossContentTarget? {
        if (path.size < 2) return null
        for (prefixLength in path.size - 1 downTo 1) {
            val key = path.take(prefixLength).joinToString(".")
            if (key !in symbols) continue
            val fields = contentFields[key] ?: continue
            val symbol = symbols.getValue(key)
            val sourceType = candidates.firstOrNull {
                it.kind == symbol.kind && it.contentName == symbol.localName
            }?.constructedType ?: continue
            return CrossContentTarget(key, fields, path.drop(prefixLength), sourceType)
        }
        return null
    }

    private fun unwrapChainedAssignmentValue(expression: Expression, targets: MutableList<List<String>>): Expression {
        var current = expression
        while (current is AssignExpr && current.operator == AssignExpr.Operator.ASSIGN) {
            targets += assignmentPaths(current.target)
            current = current.value
        }
        return current
    }

    private fun assignmentPaths(expression: Expression): MutableList<List<String>> =
        expression.assignmentPath()?.let { mutableListOf(it) } ?: mutableListOf()

    private fun controllerType(expression: Expression): String? = when (expression) {
        is LambdaExpr -> expression.body.findFirst(ObjectCreationExpr::class.java).map { it.type.nameAsString }.orElse(null)
        is ObjectCreationExpr -> expression.type.nameAsString
        is MethodReferenceExpr -> expression.scope.toString().substringAfterLast('.')
        is EnclosedExpr -> controllerType(expression.inner)
        else -> null
    }

    private fun consumptionCall(expression: Expression): Pair<MethodCallExpr, Boolean>? {
        val call = expression as? MethodCallExpr ?: return null
        if (call.nameAsString.startsWith("consume")) return call to false
        if (call.nameAsString in setOf("boost", "update")) {
            val nested = call.scope.orElse(null) as? MethodCallExpr ?: return null
            if (nested.nameAsString.startsWith("consume")) return nested to (call.nameAsString == "boost")
        }
        return null
    }

    private fun applyBuilderCall(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        when (call.nameAsString) {
            "opposite", "affinity" -> addStatusRelation(call, declaration)
            "init" -> processInitCallback(call, declaration)
            "add", "addAll" -> addToCollection(call, declaration)
            "set", "put" -> addToMap(call, declaration)
            "requirements" -> applyRequirements(call, declaration)
            "ammo" -> applyAmmo(call, declaration)
            "consumePower", "consumePowerBuffered", "consumeItem", "consumeItems",
            "consumeLiquid", "consumeLiquids", "consumeCoolant", "consume" ->
                applyConsumptionCall(call, declaration)
            "boost", "update" -> {
                val nested = call.scope.orElse(null) as? MethodCallExpr
                if (nested != null && nested.nameAsString.startsWith("consume")) {
                    applyConsumptionCall(nested, declaration, boosted = call.nameAsString == "boost")
                } else {
                    unsupportedBuilder(call, declaration)
                }
            }
            "setEnginesMirror" -> applyMirroredEngines(call, declaration)
            else -> {
                unsupportedBuilder(call, declaration)
            }
        }
    }

    private fun unsupportedBuilder(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        declaration.degraded = true
        declaration.problems += problem(
            "JAVA_BUILDER_CALL_UNSUPPORTED",
            "Builder call '${call.nameAsString}' is not supported by the deterministic static subset.",
            declaration.candidate,
            call,
            details = call.toString().take(500),
        )
    }

    private fun applyRequirements(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        if (call.arguments.size !in 2..3) {
            evaluator(declaration).omit("requirements", call, "requirements(...) must have category, optional visibility, and item stacks.")
            return
        }
        val evaluator = evaluator(declaration)
        evaluator.eval(call.arguments[0])?.json?.let { declaration.body.fields["category"] = it }
            ?: evaluator.omit("category", call.arguments[0], "Block category could not be evaluated.")
        if (call.arguments.size == 3) {
            evaluator.eval(call.arguments[1])?.json?.let { declaration.body.fields["buildVisibility"] = it }
                ?: evaluator.omit("buildVisibility", call.arguments[1], "Build visibility could not be evaluated.")
        }
        val stacks = call.arguments.last()
        evaluator.eval(stacks)?.json?.let { declaration.body.fields["requirements"] = it }
            ?: evaluator.omit("requirements", stacks, "ItemStack requirements could not be evaluated.")
    }

    private fun applyAmmo(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        if (call.arguments.size < 2 || call.arguments.size % 2 != 0) {
            evaluator(declaration).omit("ammo", call, "ammo(...) must contain item/liquid and bullet pairs.")
            return
        }
        val ammo = declaration.body.fields["ammoTypes"] as? StaticJsonValue.ObjectValue
            ?: StaticJsonValue.ObjectValue().also { declaration.body.fields["ammoTypes"] = it }
        call.arguments.toList().chunked(2).forEach { (keyExpression, bulletExpression) ->
            val key = (evaluator(declaration).eval(keyExpression)?.json as? StaticJsonValue.StringValue)?.value
            val bullet = evaluator(declaration).eval(bulletExpression)?.json
            if (key == null || bullet == null) {
                evaluator(declaration).omit("ammo", bulletExpression, "Ammo key or bullet object could not be evaluated.")
            } else {
                ammo.fields[key] = bullet
            }
        }
    }

    private fun applyConsumptionCall(
        call: MethodCallExpr,
        declaration: JavaGeneratedDeclaration,
        boosted: Boolean = false,
    ) {
        val consumes = declaration.body.fields["consumes"] as? StaticJsonValue.ObjectValue
            ?: StaticJsonValue.ObjectValue().also { declaration.body.fields["consumes"] = it }
        val evaluator = evaluator(declaration)
        fun argument(index: Int): StaticJsonValue? = call.arguments.getOrNull(index)?.let { evaluator.eval(it)?.json }
        fun combinedStack(content: StaticJsonValue?, amount: StaticJsonValue?): StaticJsonValue? {
            val name = (content as? StaticJsonValue.StringValue)?.value ?: return null
            val number = (amount as? StaticJsonValue.Number)?.value ?: BigDecimal.ONE
            return StaticJsonValue.StringValue("$name/${number.stripTrailingZeros().toPlainString()}")
        }
        when (call.nameAsString) {
            "consumePower" -> argument(0)?.let { consumes.fields["power"] = it }
            "consumePowerBuffered" -> argument(0)?.let { consumes.fields["powerBuffered"] = it }
            "consumeItem" -> {
                val stack = combinedStack(argument(0), argument(1))
                if (stack != null) {
                    val key = if (boosted) "itemsBoost" else "items"
                    consumes.fields[key] = StaticJsonValue.ArrayValue(mutableListOf(stack))
                } else evaluator.omit("consumes.items", call, "Item consumption could not be evaluated.")
            }
            "consumeItems" -> argument(0)?.let { consumes.fields[if (boosted) "itemsBoost" else "items"] = it }
                ?: evaluator.omit("consumes.items", call, "Item stack consumption could not be evaluated.")
            "consumeLiquid" -> {
                val stack = combinedStack(argument(0), argument(1))
                if (stack != null) {
                    consumes.fields[if (boosted) "liquidsBoost" else "liquid"] = if (boosted) {
                        // v159 DataPatcher models a boosted liquid consumer as ConsumeLiquids,
                        // even when Java created it through consumeLiquid(...).boost().
                        StaticJsonValue.ArrayValue(mutableListOf(stack))
                    } else {
                        stack
                    }
                }
                else evaluator.omit("consumes.liquid", call, "Liquid consumption could not be evaluated.")
            }
            "consumeLiquids" -> argument(0)?.let { consumes.fields[if (boosted) "liquidsBoost" else "liquids"] = it }
                ?: evaluator.omit("consumes.liquids", call, "Liquid stack consumption could not be evaluated.")
            "consumeCoolant" -> {
                val value = argument(0)
                if (value != null) {
                    consumes.fields["coolant"] = if (value is StaticJsonValue.Number) {
                        StaticJsonValue.ObjectValue(linkedMapOf("amount" to value))
                    } else value
                } else evaluator.omit("consumes.coolant", call, "Coolant consumption could not be evaluated.")
            }
            "consume" -> {
                val value = argument(0) as? StaticJsonValue.ObjectValue
                val type = (value?.fields?.get("type") as? StaticJsonValue.StringValue)?.value
                val key = MindustryJavaMappings.consumeKey(type)
                if (value != null && key != null) {
                    value.fields.remove("type")
                    consumes.fields[key] = value
                } else evaluator.omit("consumes", call, "Custom Consume object could not be mapped to a data-pack consume key.")
            }
        }
    }

    private fun applyMirroredEngines(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        val engines = declaration.body.fields["engines"] as? StaticJsonValue.ArrayValue
            ?: StaticJsonValue.ArrayValue().also { declaration.body.fields["engines"] = it }
        call.arguments.forEach { argument ->
            val base = evaluator(declaration).eval(argument)?.json as? StaticJsonValue.ObjectValue
            if (base == null) {
                evaluator(declaration).omit("engines", argument, "Unit engine constructor could not be evaluated.")
                return@forEach
            }
            engines.values += base
            val mirrored = StaticJsonValue.ObjectValue(LinkedHashMap(base.fields))
            val x = (mirrored.fields["x"] as? StaticJsonValue.Number)?.value
            val rotation = (mirrored.fields["rotation"] as? StaticJsonValue.Number)?.value
            if (x != null) mirrored.fields["x"] = StaticJsonValue.Number(x.negate())
            if (rotation != null) {
                var angle = BigDecimal(180).subtract(rotation)
                while (angle < BigDecimal.ZERO) angle += BigDecimal(360)
                mirrored.fields["rotation"] = StaticJsonValue.Number(angle)
            }
            engines.values += mirrored
        }
    }

    private fun processInitCallback(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        val lambda = call.arguments.singleOrNull() as? LambdaExpr
        if (lambda == null) {
            evaluator(declaration).omit("init", call, "Only a direct lambda init block can be inspected.")
            return
        }
        lambda.body.findAll(MethodCallExpr::class.java).forEach { nested ->
            if (nested.nameAsString in setOf("opposite", "affinity")) addStatusRelation(nested, declaration)
        }
    }

    private fun addStatusRelation(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        val field = if (call.nameAsString == "opposite") "opposites" else "affinities"
        val array = declaration.body.fields[field] as? StaticJsonValue.ArrayValue
            ?: StaticJsonValue.ArrayValue().also { declaration.body.fields[field] = it }
        val relationArguments = if (call.nameAsString == "affinity") call.arguments.take(1) else call.arguments
        relationArguments.forEach { argument ->
            evaluator(declaration).eval(argument)?.json?.let(array.values::add)
                ?: evaluator(declaration).omit(field, argument, "Status relation target could not be evaluated.")
        }
        if (call.nameAsString == "opposite") {
            declaration.degraded = true
            declaration.problems += problem(
                "JAVA_STATUS_OPPOSITE_DISPLAY_ONLY",
                "Status opposite relation was retained for display, but Java's duration-cancellation callback cannot be installed by a data patch.",
                declaration.candidate,
                call,
            )
        }
        if (call.nameAsString == "affinity" && call.arguments.any { it is LambdaExpr }) {
            declaration.degraded = true
            declaration.problems += problem(
                "JAVA_STATUS_CALLBACK_OMITTED",
                "Status affinity relation was retained for display, but its custom transition callback was omitted; data patches cannot install a TransitionHandler.",
                declaration.candidate,
                call,
            )
        }
    }

    private fun addToCollection(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        val scope = call.scope.orElse(null) ?: run {
            evaluator(declaration).omit(call.nameAsString, call, "Collection builder call has no field scope.")
            return
        }
        val field = when (scope) {
            is NameExpr -> scope.nameAsString
            is FieldAccessExpr -> scope.nameAsString
            else -> null
        } ?: run {
            evaluator(declaration).omit(call.nameAsString, call, "Collection field could not be identified.")
            return
        }
        val array = declaration.body.fields[field] as? StaticJsonValue.ArrayValue
            ?: StaticJsonValue.ArrayValue().also { declaration.body.fields[field] = it }
        call.arguments.forEach { argument ->
            val value = evaluator(declaration).eval(argument)?.json
            if (call.nameAsString == "addAll" && value is StaticJsonValue.ArrayValue && field != "upgrades") {
                array.values += value.values
            } else if (value != null) {
                array.values += value
            } else {
                evaluator(declaration).omit(field, argument, "Collection element could not be evaluated.")
            }
        }
    }

    private fun addToMap(call: MethodCallExpr, declaration: JavaGeneratedDeclaration) {
        val scope = call.scope.orElse(null) ?: run {
            evaluator(declaration).omit(call.nameAsString, call, "Map builder call has no field scope.")
            return
        }
        val field = scope.assignmentPath()?.lastOrNull() ?: run {
            evaluator(declaration).omit(call.nameAsString, call, "Map field could not be identified.")
            return
        }
        if (call.arguments.size < 2) {
            evaluator(declaration).omit(field, call, "Map builder call needs a key and value.")
            return
        }
        val keyExpression = call.arguments[0]
        // Resolve content symbols before falling back to their Java field spelling. This keeps
        // custom map keys in the generated dp-* namespace (e.g. SFItems.discFabric ->
        // dp-disc-fabric) while vanilla Items.* still resolve to their built-in names.
        val evaluatedKey = evaluator(declaration).eval(keyExpression)?.json as? StaticJsonValue.StringValue
        val key = evaluatedKey?.value ?: when (keyExpression) {
            is FieldAccessExpr -> MindustryJavaMappings.camelToKebab(keyExpression.nameAsString)
            is NameExpr -> MindustryJavaMappings.camelToKebab(keyExpression.nameAsString)
            else -> null
        }
        val value = evaluator(declaration).eval(call.arguments[1])?.json
        if (key == null || value == null) {
            evaluator(declaration).omit(field, call, "Map key or value could not be evaluated.")
            return
        }
        val map = declaration.body.fields[field] as? StaticJsonValue.ObjectValue
            ?: StaticJsonValue.ObjectValue().also { declaration.body.fields[field] = it }
        map.fields[key] = value
    }

    private fun evaluator(declaration: JavaGeneratedDeclaration): ExpressionEvaluator = ExpressionEvaluator(
        declaration = declaration,
        // Java locals shadow fields. The candidate-specific map is intentionally applied last,
        // while qualified Owner.FIELD constants remain available from the global map.
        constants = constants + declaration.candidate.localConstants,
        symbols = symbols,
        contentFields = contentFields,
        resolveContentReference = ::resolveContentReference,
        promoteNestedUnit = { expression, visibleLocals ->
            promoteNestedUnit(expression, declaration, visibleLocals)
        },
    )

    private fun resolveContentReference(expression: Expression): String? {
        val key = expression.toString()
        symbols[key]?.let { return it.targetName }
        if (expression is FieldAccessExpr) {
            val cleanOwner = when (val scope = expression.scope) {
                is NameExpr -> scope.nameAsString
                is FieldAccessExpr -> scope.nameAsString
                else -> scope.toString().substringAfterLast('.').trim()
            }
            symbols["$cleanOwner.${expression.nameAsString}"]?.let { return it.targetName }
            val owner = expression.scope.toString().substringAfterLast('.')
            if (owner in setOf("Items", "Liquids", "StatusEffects", "Blocks", "UnitTypes")) {
                return MindustryJavaMappings.vanillaContentName(owner, expression.nameAsString)
            }
        }
        if (expression is NameExpr) symbols[expression.nameAsString]?.let { return it.targetName }
        return null
    }

    private fun generatedResult(
        declaration: JavaGeneratedDeclaration,
        promoted: Boolean = false,
    ): ContentResult {
        val candidate = declaration.candidate
        return ContentResult(
            sourceSymbol = "${candidate.owner.nameAsString}.${candidate.symbol}",
            kind = candidate.kind,
            disposition = if (declaration.degraded) ContentDisposition.DEGRADED else ContentDisposition.CONVERTED,
            sourceType = candidate.constructedType,
            targetType = declaration.targetType,
            outputName = "dp-${candidate.contentName}",
            outputPath = outputPath(candidate.kind, candidate.contentName!!),
            reason = when {
                declaration.degraded ->
                    "A loadable HJSON object was emitted, but one or more Java-only fields or callbacks were omitted."
                promoted ->
                    "A nested Java UnitType was promoted to independent v159 data content without executing input code."
                else -> "Statically exported from Java declarations without executing input code."
            },
            diagnosticCodes = declaration.problems.map { it.code }.distinct(),
            location = SourceLocation(candidate.sourcePath, line = candidate.line, column = candidate.column),
        )
    }

    private fun unsupportedResult(candidate: JavaRootCandidate, reason: String, code: String): ContentResult = ContentResult(
        sourceSymbol = "${candidate.owner.nameAsString}.${candidate.symbol}",
        kind = candidate.kind,
        disposition = ContentDisposition.UNSUPPORTED,
        sourceType = candidate.constructedType,
        targetType = MindustryJavaMappings.targetRootType(candidate).first,
        outputName = candidate.contentName?.let { "dp-$it" },
        reason = reason,
        diagnosticCodes = listOf(code),
        location = SourceLocation(candidate.sourcePath, line = candidate.line, column = candidate.column),
    )

    private fun problem(
        code: String,
        message: String,
        candidate: JavaRootCandidate,
        node: Node,
        details: String? = null,
    ): JavaStaticProblem {
        val position = node.begin.orElse(null)
        return JavaStaticProblem(code, message, candidate.sourcePath, position?.line, position?.column, details = details)
    }

    private fun toDiagnostic(problem: JavaStaticProblem): Diagnostic = Diagnostic(
        code = problem.code,
        severity = when (problem.severity) {
            JavaStaticSeverity.INFO -> DiagnosticSeverity.INFO
            JavaStaticSeverity.WARNING -> DiagnosticSeverity.WARNING
            JavaStaticSeverity.ERROR -> DiagnosticSeverity.ERROR
        },
        message = problem.message,
        stage = ValidationStage.STRUCTURE,
        location = SourceLocation(problem.sourcePath, line = problem.line, column = problem.column),
        details = problem.details,
        suggestion = if (problem.severity == JavaStaticSeverity.ERROR) {
            "Review this declaration and add a deterministic mapping or provide a manual HJSON replacement."
        } else null,
    )

    private fun outputPath(kind: ContentKind, name: String): String = "content/${kind.folderName}/$name.hjson"

    private fun isPolicyExcludedSource(path: String): Boolean {
        val name = path.substringAfterLast('/').substringBeforeLast('.').lowercase(Locale.ROOT)
        return name.contains("planet") || name.contains("sector") || name.contains("techtree") ||
            name == "techfix" || path.contains("/maps/", ignoreCase = true)
    }

    private fun kindOrder(kind: ContentKind): Int = when (kind) {
        ContentKind.ITEM -> 0
        ContentKind.STATUS -> 1
        ContentKind.LIQUID -> 2
        ContentKind.UNIT -> 3
        ContentKind.BLOCK -> 4
        ContentKind.WEATHER -> 5
    }
}

private class ExpressionEvaluator(
    private val declaration: JavaGeneratedDeclaration,
    private val constants: Map<String, ConstantExpression>,
    private val symbols: Map<String, SymbolTarget>,
    private val contentFields: Map<String, Map<String, StaticJsonValue>>,
    private val resolveContentReference: (Expression) -> String?,
    private val promoteNestedUnit: (ObjectCreationExpr, Map<String, StaticJsonValue>) -> String?,
) {
    private val evaluatingConstants = mutableSetOf<String>()
    private val objectScopes = java.util.ArrayDeque<StaticJsonValue.ObjectValue>()
    private val objectLocalScopes = java.util.ArrayDeque<MutableMap<String, StaticJsonValue>>()
    private val partProgressConstants = setOf(
        "reload", "smoothReload", "warmup", "charge", "recoil", "heat", "life", "time",
    )
    private val partProgressOperations = setOf(
        "inv", "slope", "clamp", "delay", "sustain", "shorten", "compress", "add",
        "blend", "mul", "min", "sin", "absin", "mod", "loop", "curve",
    )

    fun eval(expression: Expression): EvalValue? = when (expression) {
        is StringLiteralExpr -> EvalValue.json(StaticJsonValue.StringValue(expression.value))
        is CharLiteralExpr -> EvalValue.json(StaticJsonValue.StringValue(expression.value))
        is BooleanLiteralExpr -> EvalValue.json(StaticJsonValue.Bool(expression.value))
        is NullLiteralExpr -> EvalValue.json(StaticJsonValue.Null)
        is IntegerLiteralExpr -> parseNumber(expression.value)
        is LongLiteralExpr -> parseNumber(expression.value.removeSuffix("L").removeSuffix("l"))
        is DoubleLiteralExpr -> parseNumber(expression.value.removeSuffix("f").removeSuffix("F").removeSuffix("d").removeSuffix("D"))
        is EnclosedExpr -> eval(expression.inner)
        is CastExpr -> eval(expression.expression)
        is UnaryExpr -> evalUnary(expression)
        is BinaryExpr -> evalBinary(expression)
        is ConditionalExpr -> evalConditional(expression)
        is FieldAccessExpr -> evalFieldAccess(expression)
        is NameExpr -> evalName(expression)
        is MethodCallExpr -> evalMethodCall(expression)
        is MethodReferenceExpr -> EvalValue.json(
            StaticJsonValue.StringValue(expression.scope.toString().substringAfterLast('.')),
        )
        is ObjectCreationExpr -> evalObjectCreation(expression)
        is ArrayCreationExpr -> expression.initializer.orElse(null)?.let(::evalArray)
        is ArrayInitializerExpr -> evalArray(expression)
        else -> null
    }

    fun omit(field: String, expression: Node, reason: String) {
        declaration.degraded = true
        val position = expression.begin.orElse(null)
        declaration.problems += JavaStaticProblem(
            code = "JAVA_FIELD_EXPRESSION_OMITTED",
            message = "$reason Field '$field' was omitted.",
            sourcePath = declaration.candidate.sourcePath,
            line = position?.line,
            column = position?.column,
            details = expression.toString().take(500),
        )
    }

    private fun degrade(code: String, message: String, node: Node, details: String? = null) {
        declaration.degraded = true
        val position = node.begin.orElse(null)
        declaration.problems += JavaStaticProblem(
            code = code,
            message = message,
            sourcePath = declaration.candidate.sourcePath,
            line = position?.line,
            column = position?.column,
            details = details,
        )
    }

    private fun info(code: String, message: String, node: Node, details: String? = null) {
        val position = node.begin.orElse(null)
        declaration.problems += JavaStaticProblem(
            code = code,
            message = message,
            sourcePath = declaration.candidate.sourcePath,
            line = position?.line,
            column = position?.column,
            severity = JavaStaticSeverity.INFO,
            details = details,
        )
    }

    private fun evalName(expression: NameExpr): EvalValue? {
        lookupLocalValue(expression.nameAsString)?.let { return evalStoredValue(it) }
        lookupAssignedField(expression.nameAsString)?.let { return evalStoredValue(it) }
        symbols[expression.nameAsString]?.let { return EvalValue.json(StaticJsonValue.StringValue(it.targetName)) }
        MindustryJavaMappings.staticImportedContent[expression.nameAsString]?.let {
            return EvalValue.json(StaticJsonValue.StringValue(it))
        }
        return evalConstant(expression.nameAsString)
    }

    private fun evalFieldAccess(expression: FieldAccessExpr): EvalValue? {
        val full = expression.toString()
        if (expression.scope.toString() == "this") {
            lookupAssignedField(expression.nameAsString)?.let { return evalStoredValue(it) }
        }
        MindustryJavaMappings.colorConstants[full]?.let { return EvalValue.color(it) }
        MindustryJavaMappings.numericConstants[full]?.let { return parseNumber(it) }
        resolveContentReference(expression)?.let { return EvalValue.json(StaticJsonValue.StringValue(it)) }
        val scopeKey = expression.scope.toString()
        contentFields[scopeKey]?.get(expression.nameAsString)?.let { value ->
            return evalStoredValue(value)
        }
        if (expression.nameAsString == "color") {
            MindustryJavaMappings.colorConstants[full]?.let { return EvalValue.color(it) }
            val fields = contentFields[scopeKey]
            val color = fields?.get("color") as? StaticJsonValue.StringValue
            if (color != null) return EvalValue.color(normalizeColor(color.value))
        }
        val owner = expression.scope.toString().substringAfterLast('.')
        if (owner in setOf("interp", "sizeInterp") && expression.nameAsString in setOf(
                "linear", "smooth", "smooth2", "smoother", "fade", "pow2", "pow2In", "pow2Out",
                "pow3", "pow3In", "pow3Out", "pow4", "pow4In", "pow4Out", "pow5", "pow5In",
                "pow5Out", "pow10", "pow10In", "pow10Out", "sine", "sineIn", "sineOut", "exp5",
                "exp5In", "exp5Out", "circle", "circleIn", "circleOut", "elastic", "elasticIn",
                "elasticOut", "swing", "swingIn", "swingOut", "bounce", "bounceIn", "bounceOut",
                "fastSlow", "slowFast", "slope",
            )
        ) {
            // Java permits referencing static members through an instance (`interp.fastSlow`).
            return EvalValue.json(StaticJsonValue.StringValue(expression.nameAsString))
        }
        if (owner == "Env") {
            return EvalValue.json(
                StaticJsonValue.ArrayValue(mutableListOf(StaticJsonValue.StringValue(expression.nameAsString))),
            )
        }
        if (owner == "SFSounds") {
            return EvalValue.json(StaticJsonValue.StringValue(expression.nameAsString))
        }
        if (owner == "SFFx") {
            degrade(
                "JAVA_CUSTOM_EFFECT_REFERENCE_OMITTED",
                "Custom Java Effect reference '$full' was replaced with Fx.none.",
                expression,
            )
            return EvalValue.json(StaticJsonValue.StringValue("none"))
        }
        if (owner == "SFAttribute" || owner == "Attribute") {
            return EvalValue.json(StaticJsonValue.StringValue(MindustryJavaMappings.camelToKebab(expression.nameAsString)))
        }
        if (owner == "UnitCommand") {
            // Java exposes static fields such as rebuildCommand, while DataPatcher resolves the
            // registered MappableContent name ("rebuild").
            return EvalValue.json(
                StaticJsonValue.StringValue(expression.nameAsString.removeSuffix("Command")),
            )
        }
        if (owner in MindustryJavaMappings.classStaticStringPrefixes) {
            return EvalValue.json(StaticJsonValue.StringValue(expression.nameAsString))
        }
        return evalConstant(full)
    }

    private fun evalConstant(key: String): EvalValue? {
        val constant = constants[key] ?: return null
        val fullKey = "${constant.ownerSimpleName}.${constant.symbol}"
        if (!evaluatingConstants.add(fullKey)) return null
        return try {
            eval(constant.expression)
        } finally {
            evaluatingConstants.remove(fullKey)
        }
    }

    private fun evalUnary(expression: UnaryExpr): EvalValue? {
        val value = eval(expression.expression) ?: return null
        return when (expression.operator) {
            UnaryExpr.Operator.MINUS -> value.number?.negate()?.let(EvalValue::number)
            UnaryExpr.Operator.PLUS -> value.number?.let(EvalValue::number)
            UnaryExpr.Operator.LOGICAL_COMPLEMENT -> (value.json as? StaticJsonValue.Bool)?.value
                ?.let { EvalValue.json(StaticJsonValue.Bool(!it)) }
            else -> null
        }
    }

    private fun evalBinary(expression: BinaryExpr): EvalValue? {
        val left = eval(expression.left) ?: return null
        val right = eval(expression.right) ?: return null
        if (expression.operator in setOf(BinaryExpr.Operator.AND, BinaryExpr.Operator.OR)) {
            val l = (left.json as? StaticJsonValue.Bool)?.value ?: return null
            val r = (right.json as? StaticJsonValue.Bool)?.value ?: return null
            return EvalValue.json(
                StaticJsonValue.Bool(if (expression.operator == BinaryExpr.Operator.AND) l && r else l || r),
            )
        }
        if (expression.operator in setOf(
                BinaryExpr.Operator.EQUALS,
                BinaryExpr.Operator.NOT_EQUALS,
                BinaryExpr.Operator.LESS,
                BinaryExpr.Operator.LESS_EQUALS,
                BinaryExpr.Operator.GREATER,
                BinaryExpr.Operator.GREATER_EQUALS,
            )
        ) {
            val comparison = if (left.number != null && right.number != null) {
                left.number.compareTo(right.number)
            } else {
                null
            }
            val matches = when (expression.operator) {
                BinaryExpr.Operator.EQUALS -> comparison?.let { it == 0 } ?: (left.json == right.json)
                BinaryExpr.Operator.NOT_EQUALS -> comparison?.let { it != 0 } ?: (left.json != right.json)
                BinaryExpr.Operator.LESS -> comparison?.let { it < 0 } ?: return null
                BinaryExpr.Operator.LESS_EQUALS -> comparison?.let { it <= 0 } ?: return null
                BinaryExpr.Operator.GREATER -> comparison?.let { it > 0 } ?: return null
                BinaryExpr.Operator.GREATER_EQUALS -> comparison?.let { it >= 0 } ?: return null
                else -> return null
            }
            return EvalValue.json(StaticJsonValue.Bool(matches))
        }
        if (expression.operator == BinaryExpr.Operator.BINARY_OR) {
            fun elements(value: StaticJsonValue?): List<StaticJsonValue>? = when (value) {
                is StaticJsonValue.ArrayValue -> value.values
                is StaticJsonValue.StringValue -> listOf(value)
                else -> null
            }
            val merged = elements(left.json)?.plus(elements(right.json).orEmpty())
            if (merged != null) return EvalValue.json(StaticJsonValue.ArrayValue(merged.distinct().toMutableList()))
        }
        if (expression.operator == BinaryExpr.Operator.PLUS &&
            (left.json is StaticJsonValue.StringValue || right.json is StaticJsonValue.StringValue)
        ) {
            val ls = (left.json as? StaticJsonValue.StringValue)?.value ?: left.number?.toPlainString() ?: return null
            val rs = (right.json as? StaticJsonValue.StringValue)?.value ?: right.number?.toPlainString() ?: return null
            return EvalValue.json(StaticJsonValue.StringValue(ls + rs))
        }
        val l = left.number ?: return null
        val r = right.number ?: return null
        val result = when (expression.operator) {
            BinaryExpr.Operator.PLUS -> l + r
            BinaryExpr.Operator.MINUS -> l - r
            BinaryExpr.Operator.MULTIPLY -> l * r
            BinaryExpr.Operator.DIVIDE -> if (r.compareTo(BigDecimal.ZERO) == 0) return null else l.divide(r, 12, RoundingMode.HALF_UP)
            BinaryExpr.Operator.REMAINDER -> if (r.compareTo(BigDecimal.ZERO) == 0) return null else l.remainder(r)
            else -> return null
        }
        return EvalValue.number(result)
    }

    private fun evalConditional(expression: ConditionalExpr): EvalValue? {
        val condition = eval(expression.condition)?.json as? StaticJsonValue.Bool ?: return null
        return eval(if (condition.value) expression.thenExpr else expression.elseExpr)
    }

    private fun evalMethodCall(expression: MethodCallExpr): EvalValue? {
        val name = expression.nameAsString
        evalKnownVanillaObject(expression)?.let { value ->
            info(
                "JAVA_VANILLA_OBJECT_SNAPSHOT_APPLIED",
                "A v159.7 built-in nested object lookup was replaced with the matching fixed-target data snapshot.",
                expression,
                expression.toString().take(500),
            )
            return EvalValue.json(value)
        }
        if (name == "random" && expression.arguments.size in 1..2) {
            val arguments = expression.arguments.map { eval(it)?.number ?: return null }
            val minimum = if (arguments.size == 1) BigDecimal.ZERO else arguments[0]
            val maximum = arguments.last()
            val midpoint = minimum.add(maximum).divide(BigDecimal.valueOf(2L))
            degrade(
                "JAVA_RANDOM_EXPRESSION_APPROXIMATED",
                "A load-time random numeric expression was replaced with its deterministic midpoint ${midpoint.stripTrailingZeros().toPlainString()}.",
                expression,
                expression.toString().take(500),
            )
            return EvalValue.number(midpoint)
        }
        if (name == "constant" && expression.scope.map { it.toString().endsWith("PartProgress") }.orElse(false) &&
            expression.arguments.size == 1
        ) {
            return eval(expression.arguments[0])?.number?.let(EvalValue::number)
        }
        if (name in partProgressOperations && expression.scope.isPresent &&
            isPartProgressExpression(expression.scope.get())
        ) {
            return evalPartProgressOperation(name, expression.scope.get(), expression.arguments.toList())
        }
        val weaponCopyArity = mapOf("copy" to 3, "copyRotate" to 4, "copyRotRel" to 5)
        if (weaponCopyArity[name] == expression.arguments.size) {
            val base = eval(expression.arguments[0])?.json as? StaticJsonValue.ObjectValue ?: return null
            val copied = base.deepCopy() as StaticJsonValue.ObjectValue
            val x = eval(expression.arguments[1])?.json ?: return null
            val y = eval(expression.arguments[2])?.json ?: return null
            copied.fields["x"] = x
            copied.fields["y"] = y
            if (name in setOf("copyRotate", "copyRotRel")) {
                copied.fields["baseRotation"] = eval(expression.arguments.getOrNull(3) ?: return null)?.json ?: return null
            }
            if (name == "copyRotRel") {
                val relative = eval(expression.arguments.getOrNull(4) ?: return null)?.number ?: return null
                val reload = (copied.fields["reload"] as? StaticJsonValue.Number)?.value ?: BigDecimal.ONE
                copied.fields["reload"] = StaticJsonValue.Number(reload + relative)
            }
            return EvalValue.json(copied)
        }
        if (name == "wrap" && expression.scope.isPresent && expression.arguments.size in 1..2) {
            val effect = eval(expression.scope.get())?.json as? StaticJsonValue.StringValue
            val color = eval(expression.arguments[0])?.json as? StaticJsonValue.StringValue
            if (effect != null && color != null) {
                val wrapped = StaticJsonValue.ObjectValue(
                    linkedMapOf(
                        "type" to StaticJsonValue.StringValue("WrapEffect"),
                        "effect" to effect,
                        "color" to color,
                    ),
                )
                expression.arguments.getOrNull(1)?.let { rotation ->
                    eval(rotation)?.json?.let { wrapped.fields["rotation"] = it }
                }
                return EvalValue.json(wrapped)
            }
        }
        if (expression.scope.map { it.toString().substringAfterLast('.') == "SFFx" }.orElse(false)) {
            degrade(
                "JAVA_CUSTOM_EFFECT_REFERENCE_OMITTED",
                "Custom Java Effect factory '${expression.scope.get()}.$name(...)' was replaced with Fx.none.",
                expression,
                expression.toString().take(500),
            )
            return EvalValue.json(StaticJsonValue.StringValue("none"))
        }
        if (name in setOf("followParent", "rotWithParent", "layer") && expression.scope.isPresent) {
            // Preserve the safe approximation of a fluent Java Effect instead of causing its
            // enclosing MultiEffect/field to be dropped altogether.
            return eval(expression.scope.get())
        }
        if (name == "valueOf" && expression.scope.map { it.toString().endsWith("Color") }.orElse(false)) {
            val raw = (eval(expression.arguments.firstOrNull() ?: return null)?.json as? StaticJsonValue.StringValue)?.value
                ?: return null
            return EvalValue.color(normalizeColor(raw))
        }
        if (name == "cpy" && expression.scope.isPresent) return eval(expression.scope.get())
        if (name == "a" && expression.scope.isPresent && expression.arguments.size == 1) {
            val base = eval(expression.scope.get())?.color ?: return null
            val alpha = eval(expression.arguments[0])?.number?.toDouble() ?: return null
            return EvalValue.color(withAlpha(base, alpha))
        }
        if (name == "lerp" && expression.scope.isPresent && expression.arguments.size == 2) {
            val from = eval(expression.scope.get())?.color ?: return null
            val to = eval(expression.arguments[0])?.color ?: return null
            val amount = eval(expression.arguments[1])?.number?.toDouble() ?: return null
            return EvalValue.color(lerpColor(from, to, amount))
        }
        if (name == "mul" && expression.scope.isPresent && expression.arguments.size == 1) {
            val base = eval(expression.scope.get())?.color ?: return null
            val multiplier = eval(expression.arguments[0])?.number?.toDouble() ?: return null
            return EvalValue.color(multiplyColor(base, multiplier))
        }
        if (name in setOf("min", "max") && expression.arguments.size == 2) {
            val a = eval(expression.arguments[0])?.number ?: return null
            val b = eval(expression.arguments[1])?.number ?: return null
            return EvalValue.number(if (name == "min") a.min(b) else a.max(b))
        }
        if (name == "mult" && expression.scope.map { it.toString().substringAfterLast('.') == "ItemStack" }.orElse(false) &&
            expression.arguments.size == 2
        ) {
            val stacks = eval(expression.arguments[0])?.json as? StaticJsonValue.ArrayValue ?: return null
            val multiplier = eval(expression.arguments[1])?.number ?: return null
            val multiplied = stacks.values.map { value ->
                when (value) {
                    is StaticJsonValue.StringValue -> {
                        val separator = value.value.lastIndexOf('/')
                        if (separator <= 0) return null
                        val namePart = value.value.substring(0, separator)
                        val amount = value.value.substring(separator + 1).toBigDecimalOrNull() ?: return null
                        val scaled = amount.multiply(multiplier).setScale(0, RoundingMode.HALF_UP)
                        StaticJsonValue.StringValue("$namePart/${scaled.toPlainString()}")
                    }
                    is StaticJsonValue.ObjectValue -> {
                        val amount = (value.fields["amount"] as? StaticJsonValue.Number)?.value ?: return null
                        StaticJsonValue.ObjectValue(LinkedHashMap(value.fields)).also { copy ->
                            copy.fields["amount"] = StaticJsonValue.Number(
                                amount.multiply(multiplier).setScale(0, RoundingMode.HALF_UP),
                            )
                        }
                    }
                    else -> return null
                }
            }
            return EvalValue.json(StaticJsonValue.ArrayValue(multiplied.toMutableList()))
        }
        if (name in setOf("with", "list") && expression.arguments.isNotEmpty()) {
            val scope = expression.scope.map { it.toString().substringAfterLast('.') }.orElse("")
            val values = expression.arguments.map { eval(it)?.json ?: return null }
            val looksLikeStacks = scope in setOf("ItemStack", "LiquidStack", "PayloadStack") ||
                (scope.isBlank() && values.size % 2 == 0 && values.indices.filter { it % 2 == 1 }
                    .all { values[it] is StaticJsonValue.Number })
            if (looksLikeStacks && values.size % 2 == 0) {
                val stacks = values.chunked(2).map { pair ->
                    val content = (pair[0] as? StaticJsonValue.StringValue)?.value ?: return null
                    val amount = (pair[1] as? StaticJsonValue.Number)?.value ?: return null
                    StaticJsonValue.StringValue("$content/${amount.stripTrailingZeros().toPlainString()}")
                }
                return EvalValue.json(StaticJsonValue.ArrayValue(stacks.toMutableList()))
            }
            return EvalValue.json(StaticJsonValue.ArrayValue(values.toMutableList()))
        }
        if (name == "of" && expression.arguments.isNotEmpty()) {
            val scope = expression.scope.map { it.toString().substringAfterLast('.') }.orElse("")
            val values = expression.arguments.map { eval(it)?.json ?: return null }
            if (scope == "ObjectMap" && values.size % 2 == 0) {
                val result = StaticJsonValue.ObjectValue()
                values.chunked(2).forEach { pair ->
                    val key = (pair[0] as? StaticJsonValue.StringValue)?.value ?: return null
                    result.fields[key] = pair[1]
                }
                return EvalValue.json(result)
            }
            return EvalValue.json(StaticJsonValue.ArrayValue(values.toMutableList()))
        }
        if (name == "name" && expression.arguments.size == 1) return eval(expression.arguments[0])
        if (name in setOf("asFloor", "asLiquid") && expression.scope.isPresent) return eval(expression.scope.get())
        if (name in setOf("abs", "sqrt", "floor", "ceil", "round") && expression.arguments.size == 1) {
            val value = eval(expression.arguments[0])?.number?.toDouble() ?: return null
            val result = when (name) {
                "abs" -> kotlin.math.abs(value)
                "sqrt" -> kotlin.math.sqrt(value)
                "floor" -> kotlin.math.floor(value)
                "ceil" -> kotlin.math.ceil(value)
                else -> kotlin.math.round(value)
            }
            return EvalValue.number(BigDecimal.valueOf(result))
        }
        if (name == "pow" && expression.arguments.size == 2) {
            val base = eval(expression.arguments[0])?.number?.toDouble() ?: return null
            val exponent = eval(expression.arguments[1])?.number?.toDouble() ?: return null
            return EvalValue.number(BigDecimal.valueOf(base.pow(exponent)))
        }
        return null
    }

    /**
     * Fixed-target snapshots for public vanilla object graphs that DataPatcher cannot reference by
     * identity. These are sourced from the pinned v159.7/B480 Mindustry content declarations, not
     * from input Mod execution. Keep this list deliberately small and exact-expression matched.
     */
    private fun evalKnownVanillaObject(expression: MethodCallExpr): StaticJsonValue.ObjectValue? {
        val canonical = expression.toString().replace(Regex("\\s+"), "")
        if (canonical != "((LiquidTurret)Blocks.tsunami).ammoTypes.get(Liquids.slag)") return null
        return StaticJsonValue.ObjectValue(
            linkedMapOf(
                "type" to StaticJsonValue.StringValue("LiquidBulletType"),
                "liquid" to StaticJsonValue.StringValue("slag"),
                "lifetime" to StaticJsonValue.Number(BigDecimal("49")),
                "speed" to StaticJsonValue.Number(BigDecimal("4")),
                "knockback" to StaticJsonValue.Number(BigDecimal("1.3")),
                "puddleSize" to StaticJsonValue.Number(BigDecimal("8")),
                "orbSize" to StaticJsonValue.Number(BigDecimal("4")),
                "damage" to StaticJsonValue.Number(BigDecimal("4.75")),
                "drag" to StaticJsonValue.Number(BigDecimal("0.001")),
                "ammoMultiplier" to StaticJsonValue.Number(BigDecimal("0.4")),
                "statusDuration" to StaticJsonValue.Number(BigDecimal("240")),
                // LiquidBulletType(Liquids.slag) constructor side effects; DataPatcher creates the
                // no-arg type and assigns liquid afterward, so preserve these explicitly.
                "status" to StaticJsonValue.StringValue("melting"),
                "hitColor" to StaticJsonValue.StringValue("ffa166ff"),
                "lightColor" to StaticJsonValue.StringValue("f0511d66"),
                "lightOpacity" to StaticJsonValue.Number(BigDecimal("0.4")),
            ),
        )
    }

    private fun evalObjectCreation(expression: ObjectCreationExpr): EvalValue? {
        val sourceType = expression.type.nameWithScope.substringAfterLast('.')
        if (sourceType == "Color" && expression.arguments.isEmpty()) {
            // Arc's zero-argument Color is opaque white. A generic {type:"Color"} object is not
            // a valid Color value for DataPatcher fields.
            return EvalValue.color("ffffffff")
        }
        if (sourceType == "Color" && expression.arguments.size == 1) return eval(expression.arguments[0])
        if (sourceType == "Color" && expression.arguments.size in 3..4) {
            val channels = expression.arguments.map { eval(it)?.number?.toDouble() ?: return null }
                .let { if (it.size == 3) it + 1.0 else it }
            val encoded = channels.joinToString("") { channel ->
                (channel.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
                    .toString(16).padStart(2, '0')
            }
            return EvalValue.color(encoded)
        }
        if (sourceType in setOf("MultiEffect", "DrawMulti")) {
            val effects = expression.arguments.map { eval(it)?.json ?: return null }
            return EvalValue.json(StaticJsonValue.ArrayValue(effects.toMutableList()))
        }
        if (sourceType == "Effect") {
            degrade(
                "JAVA_LAMBDA_EFFECT_APPROXIMATED",
                "A Java callback Effect has no data-pack equivalent and was replaced with Fx.none.",
                expression,
                expression.toString().take(500),
            )
            return EvalValue.json(StaticJsonValue.StringValue("none"))
        }
        if (sourceType in setOf("UnitType", "TankUnitType", "MissileUnitType")) {
            val reference = promoteNestedUnit(expression, visibleLocalValues()) ?: return null
            return EvalValue.json(StaticJsonValue.StringValue(reference))
        }
        val targetType = MindustryJavaMappings.customBulletFallbacks[sourceType] ?: sourceType
        val objectValue = StaticJsonValue.ObjectValue()
        if (sourceType !in MindustryJavaMappings.constructorTypeOmitted) {
            objectValue.fields["type"] = StaticJsonValue.StringValue(targetType)
        }
        val defaultBulletColors = when (sourceType) {
            "MissileBulletType" -> "e58956ff" to "ffd2aeff"
            in setOf(
                "BasicBulletType", "ArtilleryBulletType", "FlakBulletType",
                "PowerupBullet", "ShieldBreakBullet", "SizeDamageBullet",
            ) -> "f9c27aff" to "fff8e8ff"
            else -> null
        }
        if (defaultBulletColors != null) {
            // These are Java class defaults. Emitting them is semantically neutral for the target,
            // and lets later assignments such as lightningColor = backColor resolve statically.
            objectValue.fields["backColor"] = StaticJsonValue.StringValue(defaultBulletColors.first)
            objectValue.fields["frontColor"] = StaticJsonValue.StringValue(defaultBulletColors.second)
        }
        applyNestedConstructorArguments(sourceType, expression, objectValue)
        objectScopes.addLast(objectValue)
        objectLocalScopes.addLast(linkedMapOf())
        try {
            expression.anonymousClassBody.orElse(null)?.forEach { member ->
                when (member) {
                    is InitializerDeclaration -> member.body.statements.forEach { statement ->
                        processNestedStatement(statement, sourceType, targetType, objectValue)
                    }
                    is MethodDeclaration -> {
                        degrade(
                            "JAVA_METHOD_OVERRIDE_OMITTED",
                            "Nested method override '${member.nameAsString}' was omitted.",
                            member,
                        )
                    }
                    else -> Unit
                }
            }
        } finally {
            objectLocalScopes.removeLast()
            objectScopes.removeLast()
        }
        if (sourceType in MindustryJavaMappings.customBulletFallbacks) {
            degrade(
                "JAVA_CUSTOM_BULLET_DEGRADED",
                "Custom bullet '$sourceType' was degraded to built-in '$targetType'; Java-only behavior was omitted.",
                expression,
            )
        }
        return EvalValue.json(objectValue)
    }

    private fun processNestedStatement(
        statement: Statement,
        sourceType: String,
        targetType: String,
        output: StaticJsonValue.ObjectValue,
    ) {
        when (statement) {
            is ExpressionStmt -> when (val expression = statement.expression) {
                is AssignExpr -> applyNestedAssignment(expression, sourceType, targetType, output)
                is MethodCallExpr -> applyNestedBuilder(expression, output)
                is VariableDeclarationExpr -> rememberNestedLocalValues(expression)
                else -> degrade(
                    "JAVA_NESTED_EXPRESSION_UNSUPPORTED",
                    "Nested expression '${expression.javaClass.simpleName}' was omitted.",
                    expression,
                    expression.toString().take(500),
                )
            }
            is ForStmt -> processNestedForStatement(statement, sourceType, targetType, output)
            is EmptyStmt -> Unit
            else -> degrade(
                "JAVA_NESTED_STATEMENT_UNSUPPORTED",
                "Nested statement '${statement.javaClass.simpleName}' was omitted.",
                statement,
                statement.toString().take(500),
            )
        }
    }

    private fun processNestedForStatement(
        statement: ForStmt,
        sourceType: String,
        targetType: String,
        output: StaticJsonValue.ObjectValue,
    ) {
        val locals = objectLocalScopes.peekLast() ?: run {
            degrade("JAVA_FOR_LOOP_UNSUPPORTED", "Nested for loop has no active local scope.", statement)
            return
        }
        val result = expandStaticForLoop(
            statement = statement,
            locals = locals,
            state = declaration.forExpansionState,
            evaluate = ::eval,
            process = { nested -> processNestedStatement(nested, sourceType, targetType, output) },
        )
        if (result.failure != null) {
            degrade(
                result.failureCode ?: "JAVA_FOR_LOOP_UNSUPPORTED",
                result.failure,
                statement,
                statement.toString().take(500),
            )
        } else {
            val position = statement.begin.orElse(null)
            declaration.problems += JavaStaticProblem(
                code = "JAVA_FOR_LOOP_EXPANDED",
                message = "A deterministic nested for loop was expanded into ${result.iterations} static data operation(s).",
                sourcePath = declaration.candidate.sourcePath,
                line = position?.line,
                column = position?.column,
                severity = JavaStaticSeverity.INFO,
            )
        }
    }

    private fun rememberNestedLocalValues(expression: VariableDeclarationExpr) {
        val scope = objectLocalScopes.peekLast() ?: return
        expression.variables.forEach { variable ->
            val initializer = variable.initializer.orElse(null) ?: return@forEach
            val value = eval(initializer)?.json
            if (value != null) {
                scope[variable.nameAsString] = value
            } else {
                omit(
                    variable.nameAsString,
                    initializer,
                    "Nested local initializer could not be evaluated; later references may be omitted.",
                )
            }
        }
    }

    private fun lookupLocalValue(name: String): StaticJsonValue? {
        val iterator = objectLocalScopes.descendingIterator()
        while (iterator.hasNext()) {
            iterator.next()[name]?.let { return it }
        }
        return declaration.localValues[name]
    }

    private fun visibleLocalValues(): Map<String, StaticJsonValue> = linkedMapOf<String, StaticJsonValue>().also { visible ->
        visible.putAll(declaration.localValues)
        objectLocalScopes.forEach(visible::putAll)
    }

    private fun lookupAssignedField(name: String): StaticJsonValue? {
        val iterator = objectScopes.descendingIterator()
        while (iterator.hasNext()) {
            iterator.next().fields[name]?.let { return it }
        }
        return declaration.body.fields[name]
    }

    private fun evalStoredValue(value: StaticJsonValue): EvalValue = when (value) {
        is StaticJsonValue.Number -> EvalValue.number(value.value)
        is StaticJsonValue.StringValue -> {
            if (value.value.matches(Regex("(?i)[0-9a-f]{3,8}")) && value.value.length in setOf(3, 4, 6, 8)) {
                EvalValue.color(normalizeColor(value.value))
            } else {
                EvalValue.json(value)
            }
        }
        else -> EvalValue.json(value)
    }

    private fun isPartProgressExpression(expression: Expression): Boolean = when (expression) {
        is FieldAccessExpr -> expression.scope.toString().substringAfterLast('.') == "PartProgress" ||
            lookupAssignedField(expression.nameAsString)?.let(::isPartProgressValue) == true
        is NameExpr -> lookupAssignedField(expression.nameAsString)?.let(::isPartProgressValue) == true
        is MethodCallExpr -> expression.nameAsString in partProgressOperations &&
            expression.scope.map(::isPartProgressExpression).orElse(false)
        is EnclosedExpr -> isPartProgressExpression(expression.inner)
        else -> false
    }

    private fun isPartProgressValue(value: StaticJsonValue): Boolean = when (value) {
        is StaticJsonValue.StringValue -> value.value in partProgressConstants
        is StaticJsonValue.Number -> true
        is StaticJsonValue.ObjectValue -> "type" in value.fields &&
            ("operation" in value.fields || "operations" in value.fields)
        else -> false
    }

    private fun evalPartProgressOperation(
        operation: String,
        baseExpression: Expression,
        arguments: List<Expression>,
    ): EvalValue? {
        val base = eval(baseExpression)?.json ?: return null
        val op = StaticJsonValue.ObjectValue(linkedMapOf("operation" to StaticJsonValue.StringValue(operation)))
        fun argument(index: Int): EvalValue? = arguments.getOrNull(index)?.let(::eval)
        fun putNumber(name: String, index: Int): Boolean {
            val number = argument(index)?.number ?: return false
            op.fields[name] = StaticJsonValue.Number(number)
            return true
        }
        when (operation) {
            "inv", "slope", "clamp" -> if (arguments.isNotEmpty()) return null
            "delay", "shorten", "mod" -> if (!putNumber("amount", 0)) return null
            "loop" -> if (!putNumber("time", 0)) return null
            "sustain" -> if (!putNumber("offset", 0) || !putNumber("grow", 1) || !putNumber("sustain", 2)) return null
            "compress" -> if (!putNumber("start", 0) || !putNumber("end", 1)) return null
            "curve" -> when (arguments.size) {
                1 -> {
                    val interp = argument(0)?.json ?: return null
                    op.fields["interp"] = interp
                }
                2 -> if (!putNumber("offset", 0) || !putNumber("duration", 1)) return null
                else -> return null
            }
            "blend" -> {
                op.fields["other"] = argument(0)?.json ?: return null
                if (!putNumber("amount", 1)) return null
            }
            "add", "mul" -> {
                val value = argument(0) ?: return null
                if (value.number != null) op.fields["amount"] = StaticJsonValue.Number(value.number)
                else op.fields["other"] = value.json ?: return null
            }
            "min" -> op.fields["other"] = argument(0)?.json ?: return null
            "sin" -> when (arguments.size) {
                2 -> if (!putNumber("scl", 0) || !putNumber("mag", 1)) return null
                3 -> if (!putNumber("offset", 0) || !putNumber("scl", 1) || !putNumber("mag", 2)) return null
                else -> return null
            }
            "absin" -> if (!putNumber("scl", 0) || !putNumber("mag", 1)) return null
            else -> return null
        }

        val result = StaticJsonValue.ObjectValue()
        val operations = mutableListOf<StaticJsonValue>()
        when (base) {
            is StaticJsonValue.StringValue -> result.fields["type"] = base
            is StaticJsonValue.ObjectValue -> {
                val type = base.fields["type"] as? StaticJsonValue.StringValue ?: return null
                result.fields["type"] = type
                (base.fields["operations"] as? StaticJsonValue.ArrayValue)?.values?.let(operations::addAll)
                val previous = base.fields["operation"] as? StaticJsonValue.StringValue
                if (previous != null) {
                    operations += StaticJsonValue.ObjectValue(
                        LinkedHashMap(base.fields.filterKeys { it != "type" }),
                    )
                }
            }
            else -> return null
        }
        operations += op
        result.fields["operations"] = StaticJsonValue.ArrayValue(operations)
        return EvalValue.json(result)
    }

    private fun applyNestedAssignment(
        assignment: AssignExpr,
        sourceType: String,
        targetType: String,
        output: StaticJsonValue.ObjectValue,
    ) {
        if (assignment.operator != AssignExpr.Operator.ASSIGN) {
            degrade(
                "JAVA_ASSIGNMENT_OPERATOR_UNSUPPORTED",
                "Nested compound assignment '${assignment.operator}' was omitted.",
                assignment,
            )
            return
        }
        val paths = mutableListOf<List<String>>()
        assignment.target.assignmentPath()?.let(paths::add)
        var valueExpression = assignment.value
        while (valueExpression is AssignExpr && valueExpression.operator == AssignExpr.Operator.ASSIGN) {
            valueExpression.target.assignmentPath()?.let(paths::add)
            valueExpression = valueExpression.value
        }
        if (paths.isEmpty()) {
            omit("<nested>", assignment, "Nested assignment target could not be identified.")
            return
        }
        val value = eval(valueExpression)?.json
        if (value == null) {
            paths.forEach { omit(it.joinToString("."), valueExpression, "Nested object field expression could not be evaluated.") }
            return
        }
        paths.forEach { path ->
            val field = path.last()
            if (field in MindustryJavaMappings.unsupportedFields(sourceType)) {
                degrade(
                    if (field in MindustryJavaMappings.unsupportedDataFields[sourceType].orEmpty()) {
                        "JAVA_TARGET_FIELD_OMITTED"
                    } else {
                        "JAVA_CUSTOM_FIELD_OMITTED"
                    },
                    if (field in MindustryJavaMappings.unsupportedDataFields[sourceType].orEmpty()) {
                        "Field '$field' on $sourceType is not accepted by the v159.7 DataPatcher and was omitted."
                    } else {
                        "Custom field '$field' on $sourceType was omitted after degrading to $targetType."
                    },
                    assignment,
                )
            } else {
                setJsonPath(output, path, value)
            }
        }
    }

    private fun applyNestedBuilder(call: MethodCallExpr, output: StaticJsonValue.ObjectValue) {
        val field = call.scope.orElse(null)?.assignmentPath()?.lastOrNull()
        when (call.nameAsString) {
            "add", "addAll" -> {
                if (field == null) {
                    omit(call.nameAsString, call, "Nested collection field could not be identified.")
                    return
                }
                val array = output.fields[field] as? StaticJsonValue.ArrayValue
                    ?: StaticJsonValue.ArrayValue().also { output.fields[field] = it }
                call.arguments.forEach { argument ->
                    val value = eval(argument)?.json
                    if (call.nameAsString == "addAll" && value is StaticJsonValue.ArrayValue && field != "upgrades") {
                        array.values += value.values
                    }
                    else if (value != null) array.values += value
                    else omit(field, argument, "Nested collection element could not be evaluated.")
                }
            }
            "set", "put" -> {
                if (field == null || call.arguments.size < 2) {
                    omit(call.nameAsString, call, "Nested map field or arguments could not be identified.")
                    return
                }
                val keyExpression = call.arguments[0]
                val evaluatedKey = eval(keyExpression)?.json as? StaticJsonValue.StringValue
                val key = evaluatedKey?.value ?: when (keyExpression) {
                    is FieldAccessExpr -> MindustryJavaMappings.camelToKebab(keyExpression.nameAsString)
                    is NameExpr -> MindustryJavaMappings.camelToKebab(keyExpression.nameAsString)
                    else -> null
                }
                val value = eval(call.arguments[1])?.json
                if (key == null || value == null) {
                    omit(field, call, "Nested map key or value could not be evaluated.")
                } else {
                    val map = output.fields[field] as? StaticJsonValue.ObjectValue
                        ?: StaticJsonValue.ObjectValue().also { output.fields[field] = it }
                    map.fields[key] = value
                }
            }
            else -> degrade(
                "JAVA_NESTED_BUILDER_UNSUPPORTED",
                "Nested builder call '${call.nameAsString}' was omitted.",
                call,
                call.toString().take(500),
            )
        }
    }

    private fun applyNestedConstructorArguments(
        sourceType: String,
        expression: ObjectCreationExpr,
        output: StaticJsonValue.ObjectValue,
    ) {
        val args = expression.arguments
        if (sourceType == "ShootMulti") {
            args.firstOrNull()?.let { argument ->
                eval(argument)?.json?.let { output.fields["source"] = it }
                    ?: omit("source", argument, "ShootMulti source pattern could not be evaluated.")
            }
            if (args.size > 1) {
                val destinations = args.drop(1).map { argument ->
                    eval(argument)?.json ?: run {
                        omit("dest", argument, "ShootMulti destination pattern could not be evaluated.")
                        return@map null
                    }
                }.filterNotNull()
                output.fields["dest"] = StaticJsonValue.ArrayValue(destinations.toMutableList())
            }
            return
        }
        if (sourceType == "PartMove" && args.size in setOf(4, 6)) {
            val names = if (args.size == 4) {
                listOf("progress", "x", "y", "rot")
            } else {
                listOf("progress", "x", "y", "gx", "gy", "rot")
            }
            names.forEachIndexed { index, name ->
                eval(args[index])?.json?.let { output.fields[name] = it }
                    ?: omit(name, args[index], "PartMove constructor argument could not be evaluated.")
            }
            return
        }
        val fields = MindustryJavaMappings.constructorFields(sourceType)
        fields.forEachIndexed { index, name ->
            args.getOrNull(index)?.let { argument ->
                eval(argument)?.json?.let { output.fields[name] = it }
                    ?: omit(name, argument, "$sourceType constructor argument could not be evaluated.")
            }
        }
        if (args.isNotEmpty() && fields.isEmpty()) {
            degrade(
                "JAVA_CONSTRUCTOR_ARGUMENTS_OMITTED",
                "Constructor arguments for '$sourceType' have no deterministic positional mapping and were omitted.",
                expression,
                args.joinToString().take(500),
            )
        } else if (args.size > fields.size) {
            degrade(
                "JAVA_CONSTRUCTOR_ARGUMENTS_PARTIAL",
                "${args.size - fields.size} trailing constructor argument(s) for '$sourceType' were omitted.",
                expression,
                args.drop(fields.size).joinToString().take(500),
            )
        }
    }

    private fun evalArray(expression: ArrayInitializerExpr): EvalValue? {
        val values = expression.values.map { eval(it)?.json ?: return null }
        return EvalValue.json(StaticJsonValue.ArrayValue(values.toMutableList()))
    }

    private fun parseNumber(raw: String): EvalValue? = runCatching {
        val normalized = raw.replace("_", "")
        val value = when {
            normalized.startsWith("0x", true) -> BigDecimal(normalized.substring(2).toLong(16))
            normalized.startsWith("0b", true) -> BigDecimal(normalized.substring(2).toLong(2))
            else -> BigDecimal(normalized)
        }
        EvalValue.number(value)
    }.getOrNull()

    private fun normalizeColor(raw: String): String {
        val clean = raw.trim().removePrefix("#").lowercase(Locale.ROOT)
        return when (clean.length) {
            3 -> clean.map { "$it$it" }.joinToString("") + "ff"
            4 -> clean.map { "$it$it" }.joinToString("")
            6 -> clean + "ff"
            8 -> clean
            else -> clean
        }
    }

    private fun withAlpha(raw: String, alpha: Double): String {
        val color = normalizeColor(raw).padEnd(8, 'f').take(8)
        val a = (alpha.coerceIn(0.0, 1.0) * 255.0).toInt().coerceIn(0, 255)
        return color.take(6) + a.toString(16).padStart(2, '0')
    }

    private fun multiplyColor(raw: String, multiplier: Double): String {
        val color = normalizeColor(raw).padEnd(8, 'f').take(8)
        return (0 until 3).joinToString("") { index ->
            val channel = color.substring(index * 2, index * 2 + 2).toInt(16)
            (channel * multiplier).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
        } + color.takeLast(2)
    }

    private fun lerpColor(fromRaw: String, toRaw: String, amountRaw: Double): String {
        val from = normalizeColor(fromRaw).padEnd(8, 'f').take(8)
        val to = normalizeColor(toRaw).padEnd(8, 'f').take(8)
        val amount = amountRaw.coerceIn(0.0, 1.0)
        return (0 until 4).joinToString("") { index ->
            val a = from.substring(index * 2, index * 2 + 2).toInt(16)
            val b = to.substring(index * 2, index * 2 + 2).toInt(16)
            (a + ((b - a) * amount)).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
        }
    }
}

private fun StaticJsonValue.deepCopy(): StaticJsonValue = when (this) {
    StaticJsonValue.Null -> StaticJsonValue.Null
    is StaticJsonValue.Bool -> copy()
    is StaticJsonValue.Number -> copy()
    is StaticJsonValue.StringValue -> copy()
    is StaticJsonValue.ArrayValue -> StaticJsonValue.ArrayValue(values.map { it.deepCopy() }.toMutableList())
    is StaticJsonValue.ObjectValue -> StaticJsonValue.ObjectValue(
        LinkedHashMap(fields.mapValues { (_, value) -> value.deepCopy() }),
    )
}

private fun Expression.unwrapCreation(): ObjectCreationExpr? = when (this) {
    is ObjectCreationExpr -> this
    is EnclosedExpr -> inner.unwrapCreation()
    is CastExpr -> expression.unwrapCreation()
    else -> null
}

private fun Expression.fieldSymbol(ownerSimpleName: String): String? = when (this) {
    is NameExpr -> nameAsString
    is FieldAccessExpr -> when (scope.toString()) {
        "this", ownerSimpleName -> nameAsString
        else -> null
    }
    is EnclosedExpr -> inner.fieldSymbol(ownerSimpleName)
    else -> null
}

private fun Expression.assignmentPath(): List<String>? = when (this) {
    is NameExpr -> listOf(nameAsString)
    is FieldAccessExpr -> when {
        scope.toString() == "this" -> listOf(nameAsString)
        else -> scope.assignmentPath()?.plus(nameAsString)
    }
    is EnclosedExpr -> inner.assignmentPath()
    is CastExpr -> expression.assignmentPath()
    else -> null
}

private fun setJsonPath(
    root: StaticJsonValue.ObjectValue,
    path: List<String>,
    value: StaticJsonValue,
) {
    if (path.isEmpty()) return
    setJsonPath(root.fields, path, value)
}

private fun setJsonPath(
    root: MutableMap<String, StaticJsonValue>,
    path: List<String>,
    value: StaticJsonValue,
) {
    if (path.isEmpty()) return
    var current = root
    path.dropLast(1).forEach { segment ->
        val child = current[segment] as? StaticJsonValue.ObjectValue
            ?: StaticJsonValue.ObjectValue().also { current[segment] = it }
        current = child.fields
    }
    current[path.last()] = value
}

private const val STATIC_FOR_LOOP_LIMIT = 64
private const val STATIC_FOR_LOOP_TOTAL_BUDGET = 4096
private const val STATIC_FOR_LOOP_MAX_DEPTH = 8

private data class StaticForExpansion(
    val iterations: Int = 0,
    val failure: String? = null,
    val failureCode: String? = null,
)

/**
 * Expands only a small, auditable classic-for subset. The header is dry-run first, so an invalid
 * condition/update never leaves a half-expanded object graph. The input Java is still never run.
 */
private fun expandStaticForLoop(
    statement: ForStmt,
    locals: MutableMap<String, StaticJsonValue>,
    state: JavaStaticForExpansionState,
    evaluate: (Expression) -> EvalValue?,
    process: (Statement) -> Unit,
): StaticForExpansion {
    if (state.activeDepth >= STATIC_FOR_LOOP_MAX_DEPTH) {
        return StaticForExpansion(
            failure = "For-loop nesting exceeded the hard limit of $STATIC_FOR_LOOP_MAX_DEPTH levels for one generated content declaration.",
            failureCode = "JAVA_FOR_LOOP_DEPTH_EXCEEDED",
        )
    }
    state.activeDepth += 1
    try {
        val initialization = statement.initialization.singleOrNull() as? VariableDeclarationExpr
            ?: return StaticForExpansion(failure = "Only one numeric loop-variable declaration is supported in a static for loop.")
        val variable = initialization.variables.singleOrNull()
            ?: return StaticForExpansion(failure = "Static for loops must declare exactly one loop variable.")
        val initializer = variable.initializer.orElse(null)
            ?: return StaticForExpansion(failure = "Static for-loop variables require a deterministic initializer.")
        val condition = statement.compare.orElse(null)
            ?: return StaticForExpansion(failure = "Infinite or condition-less for loops are not expanded.")
        val update = statement.update.singleOrNull()
            ?: return StaticForExpansion(failure = "Static for loops must have exactly one deterministic update expression.")
        if (!supportsStaticForBody(statement.body)) {
            return StaticForExpansion(
                failure = "For-loop body contains control flow outside assignments, local declarations, builder calls, empty statements, or nested static for loops.",
            )
        }

        val baseLocals = LinkedHashMap(locals)
        return try {
            locals.clear()
            locals.putAll(baseLocals)
            var current = evaluate(initializer)?.number
                ?: return StaticForExpansion(failure = "For-loop initializer is not a deterministic number.")
            val values = mutableListOf<BigDecimal>()
            while (true) {
                locals.clear()
                locals.putAll(baseLocals)
                locals[variable.nameAsString] = StaticJsonValue.Number(current)
                val matches = (evaluate(condition)?.json as? StaticJsonValue.Bool)?.value
                    ?: return StaticForExpansion(failure = "For-loop condition is not a deterministic boolean comparison.")
                if (!matches) break
                if (values.size >= STATIC_FOR_LOOP_LIMIT) {
                    return StaticForExpansion(
                        failure = "For-loop expansion exceeded the hard limit of $STATIC_FOR_LOOP_LIMIT iterations.",
                        failureCode = "JAVA_FOR_LOOP_LIMIT_EXCEEDED",
                    )
                }
                values += current
                current = advanceStaticForLoop(update, variable.nameAsString, current, evaluate)
                    ?: return StaticForExpansion(failure = "For-loop update is not a supported constant increment, decrement, or numeric assignment.")
            }

            val remainingBudget = STATIC_FOR_LOOP_TOTAL_BUDGET - state.expandedIterations
            if (values.size > remainingBudget) {
                return StaticForExpansion(
                    failure = "For-loop expansion would exceed the per-content total budget of " +
                        "$STATIC_FOR_LOOP_TOTAL_BUDGET iterations (already expanded ${state.expandedIterations}, requested ${values.size}).",
                    failureCode = "JAVA_FOR_LOOP_BUDGET_EXCEEDED",
                )
            }
            state.expandedIterations += values.size

            val bodyStatements = (statement.body as? BlockStmt)?.statements?.toList() ?: listOf(statement.body)
            values.forEach { loopValue ->
                locals.clear()
                locals.putAll(baseLocals)
                locals[variable.nameAsString] = StaticJsonValue.Number(loopValue)
                bodyStatements.forEach(process)
            }
            StaticForExpansion(iterations = values.size)
        } finally {
            locals.clear()
            locals.putAll(baseLocals)
        }
    } finally {
        state.activeDepth -= 1
    }
}

private fun supportsStaticForBody(statement: Statement): Boolean = when (statement) {
    is BlockStmt -> statement.statements.all(::supportsStaticForBody)
    is EmptyStmt -> true
    is ForStmt -> true
    is ExpressionStmt -> statement.expression is AssignExpr ||
        statement.expression is MethodCallExpr || statement.expression is VariableDeclarationExpr
    else -> false
}

private fun advanceStaticForLoop(
    update: Expression,
    variable: String,
    current: BigDecimal,
    evaluate: (Expression) -> EvalValue?,
): BigDecimal? = when (update) {
    is UnaryExpr -> {
        val target = (update.expression as? NameExpr)?.nameAsString ?: return null
        if (target != variable) return null
        when (update.operator) {
            UnaryExpr.Operator.PREFIX_INCREMENT, UnaryExpr.Operator.POSTFIX_INCREMENT -> current + BigDecimal.ONE
            UnaryExpr.Operator.PREFIX_DECREMENT, UnaryExpr.Operator.POSTFIX_DECREMENT -> current - BigDecimal.ONE
            else -> null
        }
    }
    is AssignExpr -> {
        val target = (update.target as? NameExpr)?.nameAsString ?: return null
        if (target != variable) return null
        when (update.operator) {
            AssignExpr.Operator.PLUS -> evaluate(update.value)?.number?.let(current::add)
            AssignExpr.Operator.MINUS -> evaluate(update.value)?.number?.let(current::subtract)
            AssignExpr.Operator.ASSIGN -> evaluate(update.value)?.number
            else -> null
        }
    }
    else -> null
}

private fun Int?.orZero(): Int = this ?: 0
