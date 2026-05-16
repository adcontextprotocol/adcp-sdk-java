package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.palantir.javapoet.ClassName

/**
 * Maps schema file paths to Java type names. Built from a [SchemaRegistry],
 * this registry determines what Java class name each schema generates and
 * how `$ref` pointers resolve to Java types at codegen time.
 *
 * Type categories:
 * - **Object schemas** (type=object with properties) → Java record
 * - **Enum schemas** (type=string with enum) → Java enum
 * - **Type aliases** (type=string without enum) → maps to `String`
 * - **Polymorphic schemas** (oneOf with discriminator) → sealed interface (Phase 2)
 */
class TypeRegistry(
    private val basePackage: String,
    private val registry: SchemaRegistry
) {

    /** Maps canonical schema path → Java ClassName. */
    private val typeMap = mutableMapOf<String, ClassName>()

    /** Maps canonical schema path → type category. */
    private val categoryMap = mutableMapOf<String, TypeCategory>()

    init {
        buildTypeMap()
    }

    enum class TypeCategory {
        RECORD,         // object with properties → Java record
        ENUM,           // string with enum → Java enum
        STRING_ALIAS,   // bare string type → String (no generated class)
        INTEGER_ALIAS,  // bare integer type → Integer
        NUMBER_ALIAS,   // bare number type → Double
        BOOLEAN_ALIAS,  // bare boolean type → Boolean
        POLYMORPHIC,    // oneOf/anyOf → sealed interface (Phase 2)
        COMPOSED,       // allOf → merged record
        UNSUPPORTED     // can't generate
    }

    private fun buildTypeMap() {
        for ((path, schema) in registry.allTypeSchemas()) {
            val title = schema.path("title").asText(null)
            val type = schema.path("type").asText("")
            val hasProperties = schema.has("properties") && schema.path("properties").size() > 0
            val hasEnum = schema.has("enum") && schema.path("enum").size() > 0
            val hasOneOf = schema.has("oneOf")
            val hasAllOf = schema.has("allOf")

            val category = when {
                type == "object" && hasProperties -> TypeCategory.RECORD
                type == "string" && hasEnum -> TypeCategory.ENUM
                hasOneOf -> TypeCategory.POLYMORPHIC
                hasAllOf && !hasProperties -> TypeCategory.COMPOSED
                type == "object" && hasAllOf -> TypeCategory.RECORD
                type == "string" -> TypeCategory.STRING_ALIAS
                type == "integer" -> TypeCategory.INTEGER_ALIAS
                type == "number" -> TypeCategory.NUMBER_ALIAS
                type == "boolean" -> TypeCategory.BOOLEAN_ALIAS
                else -> TypeCategory.UNSUPPORTED
            }

            categoryMap[path] = category

            // Only generate a Java type for records, enums, polymorphic, and composed
            if (category in setOf(TypeCategory.RECORD, TypeCategory.ENUM,
                    TypeCategory.POLYMORPHIC, TypeCategory.COMPOSED)) {
                val className = deriveClassName(path, title)
                val packageName = derivePackageName(path)
                typeMap[path] = ClassName.get(packageName, className)
            }
        }
    }

    /**
     * Returns the Java [ClassName] for a schema at the given canonical path,
     * or `null` if the schema doesn't generate a Java type (e.g. type aliases).
     */
    fun getClassName(canonicalPath: String): ClassName? = typeMap[canonicalPath]

    /**
     * Returns the Java [ClassName] for a `$ref` string, resolving it to a
     * canonical path first.
     */
    fun getClassNameForRef(ref: String): ClassName? {
        val canonical = registry.toCanonicalPath(ref) ?: return null
        return typeMap[canonical]
    }

    /** Returns the type category for a schema. */
    fun getCategory(canonicalPath: String): TypeCategory? = categoryMap[canonicalPath]

    /** Returns all schema paths that generate Java types. */
    fun allGeneratableEntries(): Map<String, ClassName> = typeMap.toMap()

    /**
     * Resolves a property's `$ref` to the appropriate Java type.
     * For type aliases, returns the primitive type (String, Integer, etc.).
     * For generatable types, returns the generated ClassName.
     */
    fun resolveRefType(ref: String): JavaTypeInfo {
        val canonical = registry.toCanonicalPath(ref) ?: return JavaTypeInfo.OBJECT
        val category = categoryMap[canonical]
        val className = typeMap[canonical]

        return when (category) {
            TypeCategory.STRING_ALIAS -> JavaTypeInfo.STRING
            TypeCategory.INTEGER_ALIAS -> JavaTypeInfo.INTEGER
            TypeCategory.NUMBER_ALIAS -> JavaTypeInfo.DOUBLE
            TypeCategory.BOOLEAN_ALIAS -> JavaTypeInfo.BOOLEAN
            TypeCategory.RECORD, TypeCategory.ENUM,
            TypeCategory.POLYMORPHIC, TypeCategory.COMPOSED ->
                if (className != null) JavaTypeInfo(className)
                else JavaTypeInfo.OBJECT
            else -> JavaTypeInfo.OBJECT
        }
    }

    private fun deriveClassName(path: String, title: String?): String {
        val raw = title ?: fileNameToTitle(path)
        // Strip parenthetical suffixes: "Format Reference (Structured Object)" → "Format Reference"
        val cleaned = raw.replace(Regex("\\s*\\([^)]*\\)\\s*"), " ").trim()
        return cleaned.split(" ", "-", "_")
            .filter { it.isNotBlank() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    private fun derivePackageName(path: String): String {
        val dir = java.io.File(path).parent ?: ""
        if (dir.isEmpty()) return "$basePackage.generated"
        val subPackage = dir.replace('-', '_').replace('/', '.').lowercase()
        return "$basePackage.generated.$subPackage"
    }

    private fun fileNameToTitle(path: String): String {
        return java.io.File(path).nameWithoutExtension
            .replace('-', ' ')
            .replace('_', ' ')
    }

    /** Encapsulates a resolved Java type — either a ClassName or a primitive. */
    data class JavaTypeInfo(
        val className: ClassName,
        val isPrimitive: Boolean = false
    ) {
        companion object {
            val STRING = JavaTypeInfo(ClassName.get("java.lang", "String"), true)
            val INTEGER = JavaTypeInfo(ClassName.get("java.lang", "Integer"), true)
            val DOUBLE = JavaTypeInfo(ClassName.get("java.lang", "Double"), true)
            val BOOLEAN = JavaTypeInfo(ClassName.get("java.lang", "Boolean"), true)
            val OBJECT = JavaTypeInfo(ClassName.get("java.lang", "Object"), true)
        }
    }
}
