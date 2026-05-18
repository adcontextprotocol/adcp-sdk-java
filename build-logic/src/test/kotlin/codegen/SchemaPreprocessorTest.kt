package codegen

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaPreprocessorTest {

    private val preprocessor = SchemaPreprocessor()
    private val mapper = ObjectMapper()

    // ── allOf: conditional stripping ─────────────────────────────────────────

    @Test
    fun preprocess_strips_allOfMember_containingOnlyNot() {
        // Three-member allOf: one "not" validator + two real property schemas.
        // After stripping the "not" member, two members remain → allOf kept with size 2.
        val schema = mapper.readTree("""
            {"allOf":[
              {"not":{"required":["x"]}},
              {"properties":{"id":{"type":"string"}}},
              {"properties":{"name":{"type":"string"}}}
            ]}
        """)
        val result = preprocessor.preprocess(schema)
        val allOf = result.path("allOf")
        assertEquals(2, allOf.size(), "allOf member with only 'not' should be stripped, leaving 2")
        assertTrue(allOf.none { it.has("not") }, "no remaining member should contain 'not'")
    }

    @Test
    fun preprocess_strips_allOfMember_containingIfThenElse() {
        // Three-member allOf: one conditional-only member + two real property schemas.
        val schema = mapper.readTree("""
            {"allOf":[
              {"if":{"properties":{}},"then":{},"else":{}},
              {"properties":{"id":{}}},
              {"properties":{"status":{}}}
            ]}
        """)
        val result = preprocessor.preprocess(schema)
        val allOf = result.path("allOf")
        assertEquals(2, allOf.size(), "allOf member with only if/then/else should be stripped, leaving 2")
        assertTrue(allOf.none { it.has("if") }, "no remaining member should contain 'if'")
    }

    @Test
    fun preprocess_preserves_allOfMember_withRealProperties() {
        val schema = mapper.readTree("""
            {"allOf":[{"properties":{"id":{"type":"string"}}},{"properties":{"name":{"type":"string"}}}]}
        """)
        val result = preprocessor.preprocess(schema)
        assertEquals(2, result.path("allOf").size(), "allOf members with properties should be kept")
    }

    @Test
    fun preprocess_unwraps_singleRemainingAllOfMember() {
        // One conditional member stripped → one real member left → unwrap and merge into parent
        val schema = mapper.readTree("""
            {
              "type": "object",
              "allOf":[
                {"not":{"required":["x"]}},
                {"properties":{"id":{"type":"string"}},"required":["id"]}
              ]
            }
        """)
        val result = preprocessor.preprocess(schema)
        assertFalse(result.has("allOf"), "allOf should be unwrapped when one member remains")
        assertTrue(result.has("properties"), "properties should be merged into parent")
        assertTrue(result.has("required"), "required should be merged into parent")
    }

    @Test
    fun preprocess_mergesProperties_whenUnwrappingAllOf() {
        val schema = mapper.readTree("""
            {
              "properties": {"existing": {"type": "string"}},
              "allOf":[
                {"not":{}},
                {"properties":{"merged": {"type":"integer"}}}
              ]
            }
        """)
        val result = preprocessor.preprocess(schema)
        assertFalse(result.has("allOf"))
        assertTrue(result.path("properties").has("existing"), "existing parent property retained")
        assertTrue(result.path("properties").has("merged"), "new property merged in")
    }

    // ── array length constraints ──────────────────────────────────────────────

    @Test
    fun preprocess_removes_minItemsAndMaxItems_fromArraySchema() {
        val schema = mapper.readTree("""{"type":"array","items":{},"minItems":1,"maxItems":10}""")
        val result = preprocessor.preprocess(schema)
        assertFalse(result.has("minItems"), "minItems should be removed from arrays")
        assertFalse(result.has("maxItems"), "maxItems should be removed from arrays")
    }

    @Test
    fun preprocess_removes_arrayConstraints_recursively_inProperties() {
        val schema = mapper.readTree("""
            {"type":"object","properties":{"tags":{"type":"array","minItems":1,"maxItems":5}}}
        """)
        val result = preprocessor.preprocess(schema)
        val tags = result.path("properties").path("tags")
        assertFalse(tags.has("minItems"))
        assertFalse(tags.has("maxItems"))
    }

    @Test
    fun preprocess_doesNotRemove_minItemsMaxItems_fromNonArray() {
        // minItems on an object (unusual but valid) should be left alone
        val schema = mapper.readTree("""{"type":"object","minItems":1,"properties":{"a":{}}}""")
        val result = preprocessor.preprocess(schema)
        assertTrue(result.has("minItems"), "minItems on non-array schema should not be touched")
    }

    // ── top-level conditionals ────────────────────────────────────────────────

    @Test
    fun preprocess_strips_topLevel_ifThenElse() {
        val schema = mapper.readTree("""
            {"type":"object","properties":{"a":{}},"if":{},"then":{},"else":{}}
        """)
        val result = preprocessor.preprocess(schema)
        assertFalse(result.has("if"), "'if' should be stripped")
        assertFalse(result.has("then"), "'then' should be stripped")
        assertFalse(result.has("else"), "'else' should be stripped")
        assertTrue(result.has("properties"), "properties should be preserved")
    }

    // ── immutability ──────────────────────────────────────────────────────────

    @Test
    fun preprocess_doesNotMutate_originalSchema() {
        val schema = mapper.readTree("""{"type":"array","minItems":1,"maxItems":10}""")
        preprocessor.preprocess(schema)
        assertTrue(schema.has("minItems"), "original schema must not be mutated")
        assertTrue(schema.has("maxItems"), "original schema must not be mutated")
    }

    @Test
    fun preprocess_returnsNewInstance() {
        val schema = mapper.readTree("""{"type":"object","properties":{"a":{}}}""")
        val result = preprocessor.preprocess(schema)
        assertNotSame(schema, result, "preprocess should return a deep copy")
    }
}
