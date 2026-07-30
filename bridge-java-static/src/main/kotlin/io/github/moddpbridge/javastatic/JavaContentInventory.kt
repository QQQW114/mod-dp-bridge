package io.github.moddpbridge.javastatic

/** Root Content families that can become Mindustry data assets. */
enum class JavaContentKind {
    ITEM,
    LIQUID,
    STATUS_EFFECT,
    UNIT,
    BLOCK,
}

/** A top-level Content constructor found without loading or executing mod code. */
data class JavaContentDeclaration(
    val kind: JavaContentKind,
    val symbol: String,
    val ownerType: String,
    val declaredType: String,
    val constructedType: String,
    val contentName: String?,
    val nameExpression: String?,
    val sourcePath: String,
    val line: Int?,
    val column: Int?,
    val anonymousClassBody: Boolean,
)

enum class JavaInventoryProblemKind {
    IO,
    PARSE,
}

/** A recoverable source discovery or parse problem. Other source files are still scanned. */
data class JavaInventoryProblem(
    val kind: JavaInventoryProblemKind,
    val sourcePath: String,
    val message: String,
    val line: Int? = null,
    val column: Int? = null,
)

data class JavaContentInventory(
    val sourceRoot: String,
    val scannedFiles: Int,
    val declarations: List<JavaContentDeclaration>,
    val problems: List<JavaInventoryProblem>,
) {
    val countsByKind: Map<JavaContentKind, Int>
        get() = JavaContentKind.entries.associateWith { kind -> declarations.count { it.kind == kind } }
}
