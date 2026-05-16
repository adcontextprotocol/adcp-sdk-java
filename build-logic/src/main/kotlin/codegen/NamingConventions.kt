package codegen

import java.io.File

/**
 * Pure naming-convention functions for mapping JSON Schema identifiers
 * to valid Java identifiers. Stateless — all methods are pure functions.
 */
object NamingConventions {

    private val JAVA_RESERVED = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "var", "yield", "record", "sealed", "permits"
    )

    /** Converts a schema title or file name to PascalCase Java class name. */
    fun toClassName(title: String): String {
        val cleaned = title.replace(Regex("\\s*\\([^)]*\\)\\s*"), " ").trim()
        return cleaned.split(" ", "-", "_")
            .filter { it.isNotBlank() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    /** Converts a JSON property name (snake_case / kebab-case) to camelCase. */
    fun toCamelCase(jsonName: String): String {
        val stripped = jsonName.removePrefix("${'$'}")
        val parts = stripped.split("_", "-")
        val raw = parts.first() + parts.drop(1).joinToString("") {
            it.replaceFirstChar(Char::uppercaseChar)
        }
        return sanitizeJavaName(raw)
    }

    /** Converts a JSON enum value to UPPER_SNAKE_CASE. */
    fun toEnumConstant(value: String): String {
        if (value.isBlank()) return "_EMPTY"
        val raw = value.removePrefix("${'$'}")
            .uppercase()
            .replace('.', '_')
            .replace('-', '_')
            .replace(' ', '_')
            .replace(Regex("[^A-Z0-9_]"), "_")
        val cleaned = if (raw.firstOrNull()?.isDigit() == true) "_$raw" else raw
        return cleaned.replace(Regex("_+"), "_").trimEnd('_')
    }

    /** Escapes dollar signs for JavaPoet string literals. */
    fun escape(text: String): String =
        text.replace("${'$'}", "${'$'}${'$'}")

    /** Appends underscore to Java reserved words; passes others through. */
    fun sanitizeJavaName(name: String): String =
        if (name in JAVA_RESERVED) "${name}_" else name

    /** Derives a Java class name from a schema file path, using title if available. */
    fun deriveClassName(path: String, title: String?): String {
        val raw = title ?: fileNameToTitle(path)
        return toClassName(raw)
    }

    /** Derives the Java package name for a schema at the given relative path. */
    fun derivePackageName(basePackage: String, path: String): String {
        val dir = File(path).parent ?: ""
        if (dir.isEmpty()) return "$basePackage.generated"
        val subPackage = dir.replace('-', '_').replace('/', '.').lowercase()
        return "$basePackage.generated.$subPackage"
    }

    /** Derives a sub-package from a schema file's parent directory. */
    fun jsonPackagePath(schemaFile: File): String =
        schemaFile.parentFile.name.replace('-', '_').lowercase()

    private fun fileNameToTitle(path: String): String =
        File(path).nameWithoutExtension.replace('-', ' ').replace('_', ' ')
}
