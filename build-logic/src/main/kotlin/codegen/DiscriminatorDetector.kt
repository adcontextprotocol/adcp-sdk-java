package codegen

import com.fasterxml.jackson.databind.JsonNode

/**
 * Pure-function utilities for detecting discriminator properties in
 * oneOf / anyOf branches. Stateless — all schema resolution is handled
 * by a caller-supplied [branchResolver] lambda.
 */
object DiscriminatorDetector {

    /**
     * Finds the discriminator property name for a schema with oneOf branches.
     * Checks `discriminator.propertyName` first, then infers from `const` values.
     */
    fun findDiscriminatorProperty(
        schema: JsonNode,
        oneOf: JsonNode,
        branchResolver: (JsonNode) -> JsonNode
    ): String? {
        val explicit = schema.path("discriminator").path("propertyName").asText(null)
        if (explicit != null) return explicit
        return pickBestDiscriminator(oneOf, branchResolver)
    }

    /**
     * Returns `true` if any oneOf branch has a `const` property that
     * could serve as a discriminator.
     */
    fun hasBranchDiscriminator(
        oneOf: JsonNode,
        branchResolver: (JsonNode) -> JsonNode
    ): Boolean {
        if (!oneOf.isArray) return false
        for (branch in oneOf) {
            val resolved = branchResolver(branch)
            val propsIter = resolved.path("properties").fields()
            while (propsIter.hasNext()) {
                if (propsIter.next().value.has("const")) return true
            }
        }
        return false
    }

    /**
     * Selects the best discriminator property from oneOf/anyOf branches.
     *
     * When multiple `const` properties exist across branches, scores each
     * by unique-value count and picks the one with highest discrimination
     * power. Full coverage (unique values == branch count) is preferred.
     *
     * Returns `null` if no `const` properties are found.
     */
    fun pickBestDiscriminator(
        branches: JsonNode,
        branchResolver: (JsonNode) -> JsonNode
    ): String? {
        val constValuesByProp = mutableMapOf<String, MutableList<String>>()
        for (branch in branches) {
            val resolved = branchResolver(branch)
            resolved.path("properties").fields().forEach { entry ->
                val constVal = entry.value.path("const").asText(null)
                if (constVal != null) {
                    constValuesByProp.getOrPut(entry.key) { mutableListOf() }.add(constVal)
                }
            }
        }

        if (constValuesByProp.isEmpty()) return null
        if (constValuesByProp.size == 1) return constValuesByProp.keys.first()

        val branchCount = branches.size()

        // Prefer a property that covers ALL branches with ALL-UNIQUE values
        val fullCoverage = constValuesByProp.entries
            .filter { (_, values) -> values.size == branchCount && values.toSet().size == branchCount }
            .maxByOrNull { (_, values) -> values.size }
            ?.key
        if (fullCoverage != null) return fullCoverage

        // Fallback: property with the most unique values (best partial discriminator)
        return constValuesByProp.entries
            .maxByOrNull { (_, values) -> values.toSet().size }
            ?.key
    }
}
