package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.TypeSpec
import java.io.File
import java.nio.file.Path

/**
 * Shared state for the codegen pipeline. Holds registries and
 * provides common operations (ref resolution, file writing) used
 * by all generators.
 */
class CodegenContext(
    val basePackage: String,
    val schemaRegistry: SchemaRegistry?,
    val typeRegistry: TypeRegistry?,
    val preprocessor: SchemaPreprocessor?
) {
    val sealedIndex = SealedInterfaceIndex()
    val inlineTypes = InlineTypeCollector()

    /** Resolves a branch's `$ref` if present; returns unchanged otherwise. */
    fun resolveBranch(branch: JsonNode): JsonNode {
        if (branch.has("\$ref") && schemaRegistry != null) {
            return schemaRegistry.resolve(branch.get("\$ref").asText()) ?: branch
        }
        return branch
    }

    fun writeJavaFile(packageName: String, typeSpec: TypeSpec, outputDir: File): Path {
        val javaFile = JavaFile.builder(packageName, typeSpec)
            .skipJavaLangImports(true)
            .indent("    ")
            .build()
        val typeName = typeSpec.name() ?: error("TypeSpec has no name")
        val targetDir = outputDir.toPath().resolve(packageName.replace('.', '/'))
        targetDir.toFile().mkdirs()
        val targetFile = targetDir.resolve("$typeName.java")
        targetFile.toFile().writeText(javaFile.toString())
        return targetFile
    }

    fun writePackageInfo(packageName: String, outputDir: File): Path {
        val targetDir = outputDir.toPath().resolve(packageName.replace('.', '/'))
        targetDir.toFile().mkdirs()
        val targetFile = targetDir.resolve("package-info.java")
        targetFile.toFile().writeText(
            "@org.jspecify.annotations.NullMarked\npackage $packageName;\n"
        )
        return targetFile
    }
}

/**
 * Tracks which generated types must implement sealed interfaces.
 * Indexes by both schema path (for `$ref` branches) and fully-qualified
 * class name (for inline branches that may collide with standalone types).
 */
class SealedInterfaceIndex {
    private val bySchemaPath = mutableMapOf<String, MutableList<ClassName>>()
    private val byFqn = mutableMapOf<String, MutableList<ClassName>>()

    fun register(schemaPath: String, parent: ClassName) {
        bySchemaPath.getOrPut(schemaPath) { mutableListOf() }.add(parent)
    }

    fun registerByName(fqn: String, parent: ClassName) {
        byFqn.getOrPut(fqn) { mutableListOf() }.add(parent)
    }

    /** Returns parent interfaces for a type, checking schema path first, then FQN. */
    fun parentsFor(schemaPath: String?, className: ClassName): List<ClassName> {
        if (schemaPath != null) {
            bySchemaPath[schemaPath]?.let { return it }
        }
        return byFqn["${className.packageName()}.${className.simpleName()}"] ?: emptyList()
    }

    fun clear() {
        bySchemaPath.clear()
        byFqn.clear()
    }
}

/**
 * Collects inline types generated as side effects of property resolution.
 * Drained after each top-level type is written to disk.
 */
class InlineTypeCollector {
    private val pending = mutableListOf<GeneratedType>()

    fun add(packageName: String, typeSpec: TypeSpec) {
        pending.add(GeneratedType(packageName, typeSpec))
    }

    fun drain(): List<GeneratedType> {
        val result = pending.toList()
        pending.clear()
        return result
    }

    fun clear() = pending.clear()
}

data class GeneratedType(val packageName: String, val typeSpec: TypeSpec)
