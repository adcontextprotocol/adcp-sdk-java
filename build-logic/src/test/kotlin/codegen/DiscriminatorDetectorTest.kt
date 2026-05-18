package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscriminatorDetectorTest {

    private val mapper = ObjectMapper()

    // JSON Schema keywords that contain '$' — must be defined as regular-string constants
    // because Kotlin triple-quoted strings don't support \$ escapes.
    private companion object {
        const val DEFS = "\$defs"
        const val REF  = "\$ref"
    }

    // ── findDiscriminatorProperty ────────────────────────────────────────────

    @Test
    fun findDiscriminatorProperty_returnsExplicit_whenPresentInSchema() {
        val schema = mapper.readTree("""{"discriminator":{"propertyName":"type"}}""")
        val oneOf = mapper.createArrayNode()
        val result = DiscriminatorDetector.findDiscriminatorProperty(schema, oneOf) { it }
        assertEquals("type", result)
    }

    @Test
    fun findDiscriminatorProperty_infersFromConst_whenNoExplicit() {
        val schema = mapper.createObjectNode()
        val oneOf = mapper.readTree("""[
            {"properties":{"type":{"const":"banner"}}},
            {"properties":{"type":{"const":"native"}}}
        ]""")
        val result = DiscriminatorDetector.findDiscriminatorProperty(schema, oneOf) { it }
        assertEquals("type", result)
    }

    @Test
    fun findDiscriminatorProperty_returnsNull_whenNoBranchConst() {
        val schema = mapper.createObjectNode()
        val oneOf = mapper.readTree("""[{"properties":{"id":{}}}, {"properties":{"name":{}}}]""")
        val result = DiscriminatorDetector.findDiscriminatorProperty(schema, oneOf) { it }
        assertNull(result)
    }

    // ── hasBranchDiscriminator ───────────────────────────────────────────────

    @Test
    fun hasBranchDiscriminator_returnsTrue_whenAnyBranchHasConst() {
        val oneOf = mapper.readTree("""[
            {"properties":{"kind":{"const":"video"}}},
            {"properties":{"other":{}}}
        ]""")
        assertTrue(DiscriminatorDetector.hasBranchDiscriminator(oneOf) { it })
    }

    @Test
    fun hasBranchDiscriminator_returnsFalse_whenNoBranchHasConst() {
        val oneOf = mapper.readTree("""[
            {"properties":{"a":{}}},
            {"properties":{"b":{}}}
        ]""")
        assertFalse(DiscriminatorDetector.hasBranchDiscriminator(oneOf) { it })
    }

    @Test
    fun hasBranchDiscriminator_returnsFalse_forNonArrayNode() {
        val notAnArray = mapper.createObjectNode()
        assertFalse(DiscriminatorDetector.hasBranchDiscriminator(notAnArray) { it })
    }

    // ── pickBestDiscriminator ─────────────────────────────────────────────────

    @Test
    fun pickBestDiscriminator_singleProperty_returnsIt() {
        val branches = mapper.readTree("""[
            {"properties":{"type":{"const":"a"}}},
            {"properties":{"type":{"const":"b"}}}
        ]""")
        assertEquals("type", DiscriminatorDetector.pickBestDiscriminator(branches) { it })
    }

    @Test
    fun pickBestDiscriminator_prefersFullCoverage() {
        // "kind" covers all 3 branches with unique values; "sub" only covers 2
        val branches = mapper.readTree("""[
            {"properties":{"kind":{"const":"x"}, "sub":{"const":"1"}}},
            {"properties":{"kind":{"const":"y"}, "sub":{"const":"2"}}},
            {"properties":{"kind":{"const":"z"}}}
        ]""")
        assertEquals("kind", DiscriminatorDetector.pickBestDiscriminator(branches) { it })
    }

    @Test
    fun pickBestDiscriminator_fallsBackToMostUniqueValues_whenNoCoversFull() {
        // Neither property covers all 3 branches; "kind" has 2 unique values vs "sub" with 1
        val branches = mapper.readTree("""[
            {"properties":{"kind":{"const":"x"}, "sub":{"const":"1"}}},
            {"properties":{"kind":{"const":"y"}}},
            {"properties":{"sub":{"const":"1"}}}
        ]""")
        assertEquals("kind", DiscriminatorDetector.pickBestDiscriminator(branches) { it })
    }

    @Test
    fun pickBestDiscriminator_returnsNull_whenNoBranchHasConst() {
        val branches = mapper.readTree("""[{"properties":{"a":{}}}, {"properties":{"b":{}}}]""")
        assertNull(DiscriminatorDetector.pickBestDiscriminator(branches) { it })
    }

    @Test
    fun pickBestDiscriminator_usesResolverCallback() {
        // Branches are $ref nodes; resolver dereferences them
        val bannerDef = mapper.readTree("""{"properties":{"type":{"const":"banner"}}}""")
        val nativeDef = mapper.readTree("""{"properties":{"type":{"const":"native"}}}""")
        val branches = mapper.readTree("""[
            {"$REF":"#/$DEFS/banner"},
            {"$REF":"#/$DEFS/native"}
        ]""")
        val refRegistry = mapOf("#/$DEFS/banner" to bannerDef, "#/$DEFS/native" to nativeDef)
        val resolver: (JsonNode) -> JsonNode = { node ->
            val ref = node.path(REF).asText(null)
            if (ref != null) refRegistry[ref] ?: node else node
        }
        assertEquals("type", DiscriminatorDetector.pickBestDiscriminator(branches, resolver))
    }
}
