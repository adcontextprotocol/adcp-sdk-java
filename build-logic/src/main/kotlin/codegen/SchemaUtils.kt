package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Schema-walking utilities for extracting properties, required fields,
 * and merging allOf branches. Pure functions — no code generation side effects.
 */
object SchemaUtils {

    private val mapper = ObjectMapper()

    /** Collects properties from a schema, merging allOf branches with `$ref` resolution. */
    fun collectProperties(
        schema: JsonNode,
        contextPath: String,
        registry: SchemaRegistry?
    ): Map<String, JsonNode> {
        val properties = LinkedHashMap<String, JsonNode>()
        schema.path("properties").fields().forEach { (name, prop) ->
            properties[name] = prop
        }
        val allOf = schema.path("allOf")
        if (allOf.isArray) {
            for (branch in allOf) {
                val resolved = if (branch.has("\$ref")) {
                    registry?.resolve(branch.path("\$ref").asText(), contextPath)
                } else branch
                resolved?.path("properties")?.fields()?.forEach { (name, prop) ->
                    if (name !in properties) properties[name] = prop
                }
            }
        }
        return properties
    }

    /** Collects required field names, resolving `$ref` in allOf branches. */
    fun collectRequired(schema: JsonNode, registry: SchemaRegistry?): Set<String> {
        val required = mutableSetOf<String>()
        schema.path("required").forEach { required.add(it.asText()) }
        schema.path("allOf").forEach { branch ->
            val resolved = if (branch.has("\$ref") && registry != null) {
                registry.resolve(branch.path("\$ref").asText()) ?: branch
            } else branch
            resolved.path("required").forEach { required.add(it.asText()) }
        }
        return required
    }

    /**
     * Merges allOf branches within a oneOf branch into a single schema.
     * Used for inline variant records that compose via allOf + `$defs`.
     */
    fun mergeAllOfInBranch(
        resolvedBranch: JsonNode,
        contextPath: String,
        registry: SchemaRegistry?
    ): JsonNode {
        val allOf = resolvedBranch.path("allOf")
        if (!allOf.isArray || allOf.isEmpty) return resolvedBranch

        val merged = mapper.createObjectNode()
        val mergedProps = mapper.createObjectNode()
        resolvedBranch.path("properties").fields().forEach { (name, prop) ->
            mergedProps.set<JsonNode>(name, prop)
        }

        val mergedRequired = mapper.createArrayNode()
        resolvedBranch.path("required").forEach { mergedRequired.add(it) }

        for (allOfBranch in allOf) {
            val resolved = if (allOfBranch.has("\$ref")) {
                registry?.resolve(allOfBranch.get("\$ref").asText(), contextPath)
            } else allOfBranch
            resolved?.path("properties")?.fields()?.forEach { (name, prop) ->
                if (!mergedProps.has(name)) mergedProps.set<JsonNode>(name, prop)
            }
            resolved?.path("required")?.forEach { req ->
                if (mergedRequired.none { it.asText() == req.asText() }) mergedRequired.add(req)
            }
        }

        merged.set<JsonNode>("properties", mergedProps)
        if (mergedRequired.size() > 0) merged.set<JsonNode>("required", mergedRequired)

        resolvedBranch.path("title").asText(null)?.let { merged.put("title", it) }
        resolvedBranch.path("description").asText(null)?.let { merged.put("description", it) }
        if (resolvedBranch.has("additionalProperties")) {
            merged.set<JsonNode>("additionalProperties", resolvedBranch.path("additionalProperties"))
        }
        return merged
    }

    /** Returns true if branches represent distinct types rather than just validation constraints. */
    fun hasStructuralBranches(branches: JsonNode): Boolean {
        val validationOnly = setOf("required", "not", "if", "then", "else")
        for (branch in branches) {
            val keys = branch.fieldNames().asSequence().toSet()
            if (keys.any { it !in validationOnly }) return true
        }
        return false
    }
}
