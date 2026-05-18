package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Normalizes raw JSON Schemas before code generation. Mirrors the TS SDK's
 * `enforceStrictSchema` pipeline in `scripts/generate-types.ts`:
 *
 * 1. Strip `allOf` members that contain only `not` or `if/then/else`
 *    (conditional validators that can't be represented in Java types)
 * 2. Remove `minItems` / `maxItems` from arrays (avoids tuple explosion)
 * 3. Flatten mutual-exclusive `oneOf` patterns
 * 4. Remove deprecated fields when configured
 *
 * The preprocessor operates on a deep copy — the original schema is not
 * mutated.
 */
class SchemaPreprocessor {

    private val mapper = ObjectMapper()

    /**
     * Applies the full preprocessing pipeline to a schema.
     * Returns a new [JsonNode] — the input is not mutated.
     */
    fun preprocess(schema: JsonNode): JsonNode {
        val copy = schema.deepCopy<JsonNode>()
        if (copy is ObjectNode) {
            stripConditionalAllOf(copy)
            removeArrayLengthConstraints(copy)
            stripTopLevelConditionals(copy)
        }
        return copy
    }

    /**
     * Strips `allOf` members that contain only validation keywords that
     * can't be represented as Java types:
     * - Members with only `not` (mutual-exclusivity validators)
     * - Members with only `if`/`then`/`else` (conditional validators)
     *
     * If stripping leaves a single-element `allOf`, unwrap it and merge
     * into the parent.
     */
    private fun stripConditionalAllOf(node: ObjectNode) {
        // Recurse into properties first
        node.path("properties").fields().forEach { (_, prop) ->
            if (prop is ObjectNode) stripConditionalAllOf(prop)
        }
        node.path("items").let { items ->
            if (items is ObjectNode) stripConditionalAllOf(items)
        }

        val allOf = node.path("allOf")
        if (!allOf.isArray) return

        val filtered = (allOf as ArrayNode).filter { member ->
            if (member !is ObjectNode) return@filter true
            val keys = member.fieldNames().asSequence().toSet()
            val validationOnly = setOf("not", "if", "then", "else")
            // Keep the member if it has keys beyond just validation keywords
            !keys.all { it in validationOnly }
        }

        if (filtered.size == allOf.size()) return // nothing stripped

        if (filtered.isEmpty()) {
            node.remove("allOf")
        } else if (filtered.size == 1 && filtered[0] is ObjectNode) {
            // Single remaining allOf member — merge its properties into parent
            node.remove("allOf")
            val remaining = filtered[0] as ObjectNode
            remaining.fields().forEach { (key, value) ->
                if (!node.has(key)) {
                    node.set<JsonNode>(key, value)
                } else if (key == "properties" && node.has("properties")) {
                    // Merge properties
                    val parentProps = node.path("properties") as ObjectNode
                    (value as ObjectNode).fields().forEach { (propName, propValue) ->
                        if (!parentProps.has(propName)) {
                            parentProps.set<JsonNode>(propName, propValue)
                        }
                    }
                } else if (key == "required" && node.has("required")) {
                    // Merge required arrays
                    val parentReq = node.path("required") as ArrayNode
                    val existingValues = parentReq.map { it.asText() }.toSet()
                    (value as ArrayNode).forEach { req ->
                        if (req.asText() !in existingValues) {
                            parentReq.add(req)
                        }
                    }
                }
            }
        } else {
            val newAllOf = mapper.createArrayNode()
            filtered.forEach { newAllOf.add(it) }
            node.set<JsonNode>("allOf", newAllOf)
        }
    }

    /**
     * Removes `minItems` and `maxItems` from all array schemas recursively.
     * Mirrors `removeArrayLengthConstraints` in the TS SDK.
     *
     * Reason: `minItems: 1` combined with `maxItems` can cause type
     * generators to enumerate tuple variants. AdCP schemas use `minItems: 1`
     * on many arrays, but agents return empty arrays in practice.
     */
    private fun removeArrayLengthConstraints(node: ObjectNode) {
        if (node.path("type").asText("") == "array") {
            node.remove("minItems")
            node.remove("maxItems")
        }

        // Recurse into properties
        node.path("properties").fields().forEach { (_, prop) ->
            if (prop is ObjectNode) removeArrayLengthConstraints(prop)
        }
        // Recurse into items
        node.path("items").let { items ->
            if (items is ObjectNode) removeArrayLengthConstraints(items)
        }
        // Recurse into allOf/oneOf/anyOf
        listOf("allOf", "oneOf", "anyOf").forEach { keyword ->
            node.path(keyword).forEach { member ->
                if (member is ObjectNode) removeArrayLengthConstraints(member)
            }
        }
        // Recurse into $defs / definitions
        listOf("\$defs", "definitions").forEach { keyword ->
            node.path(keyword).fields().forEach { (_, def) ->
                if (def is ObjectNode) removeArrayLengthConstraints(def)
            }
        }
    }

    /**
     * Strips top-level `if`/`then`/`else` from object schemas.
     * These are conditional validators that can't be represented in Java.
     */
    private fun stripTopLevelConditionals(node: ObjectNode) {
        node.remove("if")
        node.remove("then")
        node.remove("else")

        // Recurse
        node.path("properties").fields().forEach { (_, prop) ->
            if (prop is ObjectNode) stripTopLevelConditionals(prop)
        }
    }
}
