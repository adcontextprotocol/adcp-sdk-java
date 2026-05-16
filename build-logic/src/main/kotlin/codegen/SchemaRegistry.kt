package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/**
 * Loads and indexes all JSON Schema files from the extracted protocol
 * tarball. Resolves `$ref` pointers (absolute, relative, fragment) to
 * their parsed [JsonNode] representation. Thread-safe after construction.
 *
 * Usage:
 * ```
 * val registry = SchemaRegistry(schemasDir)
 * val resolved = registry.resolve("/schemas/3.0.11/core/brand-ref.json")
 * ```
 */
class SchemaRegistry(private val schemaRoot: File) {

    private val mapper = ObjectMapper()

    /** Map from canonical path (e.g. "core/brand-ref.json") to parsed schema. */
    private val schemas = mutableMapOf<String, JsonNode>()

    /** Map from absolute $id (e.g. "/schemas/3.0.11/core/brand-ref.json") to canonical path. */
    private val idIndex = mutableMapOf<String, String>()

    /** The AdCP version detected from index.json, used to strip version prefix from $ref. */
    private var adcpVersion: String? = null

    init {
        loadAll()
    }

    /**
     * Recursively loads all .json files under [schemaRoot] and indexes
     * them by both relative path and `$id`.
     */
    private fun loadAll() {
        schemaRoot.walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .forEach { file ->
                val relativePath = file.relativeTo(schemaRoot).path
                val schema = mapper.readTree(file)
                schemas[relativePath] = schema

                // Index by $id if present
                val id = schema.path("\$id").asText(null)
                if (id != null) {
                    idIndex[id] = relativePath
                    // Extract version from first $id seen
                    if (adcpVersion == null) {
                        val match = Regex("^/schemas/([^/]+)/").find(id)
                        if (match != null) {
                            adcpVersion = match.groupValues[1]
                        }
                    }
                }
            }
    }

    /**
     * Returns the parsed schema for the given canonical path
     * (e.g. "core/brand-ref.json").
     */
    fun get(canonicalPath: String): JsonNode? = schemas[canonicalPath]

    /**
     * Resolves a `$ref` string to its parsed [JsonNode].
     *
     * Handles:
     * - Absolute refs: `/schemas/3.0.11/core/brand-ref.json`
     * - Fragment refs: `#/$defs/baseIndividualAsset`
     * - Relative refs resolved against [contextFile]
     */
    fun resolve(ref: String, contextFile: String? = null): JsonNode? {
        // Fragment-only ref (within same schema)
        if (ref.startsWith("#/")) {
            val contextSchema = if (contextFile != null) schemas[contextFile] else null
            return contextSchema?.let { resolveFragment(it, ref.removePrefix("#/")) }
        }

        // Absolute ref: strip version prefix to get canonical path
        val canonical = toCanonicalPath(ref)
        if (canonical != null) {
            val schema = schemas[canonical]
            if (schema != null) return schema
        }

        // Try direct $id lookup
        val fromId = idIndex[ref]
        if (fromId != null) return schemas[fromId]

        // Relative ref
        if (contextFile != null) {
            val contextDir = File(contextFile).parent ?: ""
            val resolved = File(contextDir, ref).normalize().path
            return schemas[resolved]
        }

        return null
    }

    /**
     * Returns all loaded schemas as (canonicalPath, schema) pairs.
     * Excludes index.json, manifest.json, and other non-type schemas.
     */
    fun allTypeSchemas(): Map<String, JsonNode> {
        return schemas.filter { (path, _) ->
            !path.endsWith("index.json") &&
            !path.endsWith("manifest.json") &&
            !path.endsWith("manifest.schema.json") &&
            !path.startsWith("bundled/")
        }
    }

    /**
     * Returns all schemas that represent generatable types — objects with
     * properties, or string enums. Filters out pure type aliases (bare
     * `"type": "string"` with no enum) and non-structural schemas.
     */
    fun allGeneratableSchemas(): Map<String, JsonNode> {
        return allTypeSchemas().filter { (_, schema) ->
            isGeneratable(schema)
        }
    }

    /**
     * Converts an absolute `$ref` or `$id` to the canonical relative path.
     * E.g. `/schemas/3.0.11/core/brand-ref.json` → `core/brand-ref.json`
     */
    fun toCanonicalPath(ref: String): String? {
        // Strip the /schemas/{version}/ prefix
        val version = adcpVersion ?: return null
        val prefix = "/schemas/$version/"
        if (ref.startsWith(prefix)) {
            return ref.removePrefix(prefix)
        }
        // Also handle refs without leading slash
        val altPrefix = "schemas/$version/"
        if (ref.startsWith(altPrefix)) {
            return ref.removePrefix(altPrefix)
        }
        return null
    }

    /**
     * Returns the detected AdCP version string (e.g. "3.0.11").
     */
    fun adcpVersion(): String? = adcpVersion

    /**
     * Checks whether a schema represents a generatable type:
     * - Object with properties → record
     * - String with enum values → Java enum
     * - Has $defs that define structured sub-types
     */
    companion object {
        fun isGeneratable(schema: JsonNode): Boolean {
            val type = schema.path("type").asText("")
            val hasProperties = schema.has("properties") && schema.path("properties").size() > 0
            val hasEnum = schema.has("enum") && schema.path("enum").size() > 0
            val hasOneOf = schema.has("oneOf")
            val hasAllOf = schema.has("allOf")

            return when {
                type == "object" && hasProperties -> true
                type == "string" && hasEnum -> true
                hasOneOf -> true
                hasAllOf -> true
                else -> false
            }
        }

        /**
         * Resolves a JSON Pointer fragment path within a schema.
         * E.g. `$defs/baseIndividualAsset` navigates the tree.
         */
        fun resolveFragment(schema: JsonNode, fragment: String): JsonNode? {
            var current: JsonNode = schema
            for (part in fragment.split("/")) {
                current = current.path(part)
                if (current.isMissingNode) return null
            }
            return if (current.isMissingNode) null else current
        }
    }
}
