package io.github.moddpbridge.javastatic

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AssignExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.CastExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Performs syntax-only, read-only inventory of Java mod sources.
 *
 * No class loader, compiler, reflection API, initializer, or mod method is invoked.
 */
class JavaContentInventoryScanner {
    fun scan(sourceRoot: Path): JavaContentInventory {
        val root = sourceRoot.toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Java source root is not a directory: $root"
        }

        val files = Files.walk(root).use { paths ->
            paths
                .filter { path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        path.extension.equals("java", ignoreCase = true)
                }
                .sorted(compareBy<Path> { normalizePath(root.relativize(it)) })
                .toList()
        }

        val parser = JavaParser(
            ParserConfiguration()
                .setCharacterEncoding(StandardCharsets.UTF_8)
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE),
        )
        val declarations = mutableListOf<JavaContentDeclaration>()
        val problems = mutableListOf<JavaInventoryProblem>()

        files.forEach { file ->
            val relativePath = normalizePath(root.relativize(file))
            try {
                val result = parser.parse(file)
                result.problems.forEach { problem ->
                    val position = problem.location
                        .flatMap { it.toRange() }
                        .map { it.begin }
                        .orElse(null)
                    problems += JavaInventoryProblem(
                        kind = JavaInventoryProblemKind.PARSE,
                        sourcePath = relativePath,
                        message = problem.message,
                        line = position?.line,
                        column = position?.column,
                    )
                }
                result.result.ifPresent { unit ->
                    declarations += inventory(unit, relativePath)
                }
            } catch (exception: Exception) {
                problems += JavaInventoryProblem(
                    kind = JavaInventoryProblemKind.IO,
                    sourcePath = relativePath,
                    message = exception.message ?: exception::class.java.name,
                )
            }
        }

        return JavaContentInventory(
            sourceRoot = normalizePath(root),
            scannedFiles = files.size,
            declarations = declarations.sortedWith(
                compareBy<JavaContentDeclaration>({ it.sourcePath }, { it.line ?: Int.MAX_VALUE }, { it.symbol }),
            ),
            problems = problems,
        )
    }

    private fun inventory(unit: CompilationUnit, sourcePath: String): List<JavaContentDeclaration> = buildList {
        unit.findAll(TypeDeclaration::class.java).forEach { owner ->
            val ownerName = owner.fullyQualifiedName.orElse(owner.nameAsString)
            val contentFields = owner.fields
                .asSequence()
                .filter(FieldDeclaration::isStatic)
                .flatMap { field ->
                    field.variables.asSequence().mapNotNull { variable ->
                        classify(variable.type.asString())?.let { kind ->
                            variable.nameAsString to FieldInfo(kind, variable.type.asString())
                        }
                    }
                }
                .toMap()

            if (contentFields.isEmpty()) return@forEach

            owner.fields.forEach { field ->
                if (!field.isStatic) return@forEach
                field.variables.forEach { variable ->
                    val fieldInfo = contentFields[variable.nameAsString] ?: return@forEach
                    val creation = variable.initializer.orElse(null)?.unwrapCreation() ?: return@forEach
                    add(declaration(fieldInfo, variable.nameAsString, ownerName, creation, sourcePath))
                }
            }

            owner.findAll(AssignExpr::class.java)
                .asSequence()
                .filter { it.operator == AssignExpr.Operator.ASSIGN }
                // An assignment inside an anonymous object initializes that nested object, not top-level Content.
                .filter { assignment -> assignment.findAncestor(ObjectCreationExpr::class.java).isEmpty }
                .forEach { assignment ->
                    val symbol = assignment.target.fieldSymbol(owner.nameAsString) ?: return@forEach
                    val fieldInfo = contentFields[symbol] ?: return@forEach
                    val creation = assignment.value.unwrapCreation() ?: return@forEach
                    add(declaration(fieldInfo, symbol, ownerName, creation, sourcePath))
                }
        }
    }

    private fun declaration(
        field: FieldInfo,
        symbol: String,
        ownerType: String,
        creation: ObjectCreationExpr,
        sourcePath: String,
    ): JavaContentDeclaration {
        val firstArgument = creation.arguments.firstOrNull()
        val position = creation.begin.orElse(null)
        return JavaContentDeclaration(
            kind = field.kind,
            symbol = symbol,
            ownerType = ownerType,
            declaredType = field.declaredType,
            constructedType = creation.type.nameWithScope,
            contentName = firstArgument?.constantString(),
            nameExpression = firstArgument?.toString(),
            sourcePath = sourcePath,
            line = position?.line,
            column = position?.column,
            anonymousClassBody = creation.anonymousClassBody.isPresent,
        )
    }

    private fun classify(typeName: String): JavaContentKind? = when (typeName.substringAfterLast('.')) {
        "Item" -> JavaContentKind.ITEM
        "Liquid" -> JavaContentKind.LIQUID
        "StatusEffect" -> JavaContentKind.STATUS_EFFECT
        "UnitType", "TankUnitType", "MissileUnitType" -> JavaContentKind.UNIT
        "Block" -> JavaContentKind.BLOCK
        else -> null
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

    private fun Expression.constantString(): String? = when (this) {
        is StringLiteralExpr -> value
        is EnclosedExpr -> inner.constantString()
        is BinaryExpr -> if (operator == BinaryExpr.Operator.PLUS) {
            val leftValue = left.constantString()
            val rightValue = right.constantString()
            if (leftValue != null && rightValue != null) leftValue + rightValue else null
        } else {
            null
        }
        else -> null
    }

    private data class FieldInfo(
        val kind: JavaContentKind,
        val declaredType: String,
    )

    private companion object {
        fun normalizePath(path: Path): String = path.toString().replace('\\', '/')
    }
}
