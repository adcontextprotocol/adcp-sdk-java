package codegen

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SchemaRegistryTest {

    private val mapper = ObjectMapper()

    // JSON Schema keywords that contain '$' — must be defined as regular-string constants
    // because Kotlin triple-quoted strings don't support \$ escapes.
    private companion object {
        const val ID   = "\$id"
        const val DEFS = "\$defs"
    }

    // ── isGeneratable (static companion) ─────────────────────────────────────

    @Test
    fun isGeneratable_returnsTrue_forObjectWithProperties() {
        val schema = mapper.readTree("""{"type":"object","properties":{"id":{}}}""")
        assertTrue(SchemaRegistry.isGeneratable(schema))
    }

    @Test
    fun isGeneratable_returnsTrue_forStringEnum() {
        val schema = mapper.readTree("""{"type":"string","enum":["a","b"]}""")
        assertTrue(SchemaRegistry.isGeneratable(schema))
    }

    @Test
    fun isGeneratable_returnsTrue_forSchemaWithOneOf() {
        val schema = mapper.readTree("""{"oneOf":[{"type":"string"},{"type":"integer"}]}""")
        assertTrue(SchemaRegistry.isGeneratable(schema))
    }

    @Test
    fun isGeneratable_returnsTrue_forSchemaWithAllOf() {
        val schema = mapper.readTree("""{"allOf":[{"properties":{"a":{}}}]}""")
        assertTrue(SchemaRegistry.isGeneratable(schema))
    }

    @Test
    fun isGeneratable_returnsFalse_forBareStringType() {
        val schema = mapper.readTree("""{"type":"string"}""")
        assertFalse(SchemaRegistry.isGeneratable(schema))
    }

    @Test
    fun isGeneratable_returnsFalse_forObjectWithNoProperties() {
        val schema = mapper.readTree("""{"type":"object"}""")
        assertFalse(SchemaRegistry.isGeneratable(schema))
    }

    @Test
    fun isGeneratable_returnsFalse_forEmptyEnumArray() {
        val schema = mapper.readTree("""{"type":"string","enum":[]}""")
        assertFalse(SchemaRegistry.isGeneratable(schema))
    }

    // ── resolveFragment (static companion) ───────────────────────────────────

    @Test
    fun resolveFragment_navigatesNestedPath() {
        val schema = mapper.readTree("""{"$DEFS":{"BrandRef":{"type":"object","properties":{"id":{"type":"string"}}}}}""")
        val result = SchemaRegistry.resolveFragment(schema, "\$defs/BrandRef")
        assertNotNull(result)
        assertEquals("object", result!!.path("type").asText())
    }

    @Test
    fun resolveFragment_returnsNull_whenPathMissing() {
        val schema = mapper.readTree("""{"type":"object"}""")
        assertNull(SchemaRegistry.resolveFragment(schema, "\$defs/Missing"))
    }

    @Test
    fun resolveFragment_handlesDeepPath() {
        val schema = mapper.readTree("""{"$DEFS":{"Foo":{"properties":{"bar":{"type":"string"}}}}}""")
        val result = SchemaRegistry.resolveFragment(schema, "\$defs/Foo/properties/bar")
        assertNotNull(result)
        assertEquals("string", result!!.path("type").asText())
    }

    // ── SchemaRegistry with a real directory ─────────────────────────────────

    @Test
    fun registry_loadsSchemas_andResolvesAbsoluteRef(@TempDir tempDir: File) {
        val version = "3.0.11"
        val coreDir = File(tempDir, "core").also { it.mkdirs() }
        val brandRef = File(coreDir, "brand-ref.json")
        brandRef.writeText("""{"$ID":"/schemas/$version/core/brand-ref.json","type":"object","properties":{"id":{}}}""")

        val registry = SchemaRegistry(tempDir)

        // Absolute ref resolution
        val resolved = registry.resolve("/schemas/$version/core/brand-ref.json")
        assertNotNull(resolved, "absolute ref should resolve to schema")
        assertEquals("object", resolved!!.path("type").asText())
    }

    @Test
    fun registry_resolvesFragmentRef_withinContextFile(@TempDir tempDir: File) {
        val version = "3.0.11"
        val coreDir = File(tempDir, "core").also { it.mkdirs() }
        val schemaFile = File(coreDir, "signal.json")
        schemaFile.writeText("""{"$ID":"/schemas/$version/core/signal.json","$DEFS":{"Base":{"type":"object","properties":{"id":{}}}}}""")

        val registry = SchemaRegistry(tempDir)
        val result = registry.resolve("#/$DEFS/Base", "core/signal.json")
        assertNotNull(result, "fragment ref should resolve within context file")
        assertEquals("object", result!!.path("type").asText())
    }

    @Test
    fun registry_resolvesRelativeRef(@TempDir tempDir: File) {
        val version = "3.0.11"
        val coreDir = File(tempDir, "core").also { it.mkdirs() }
        File(coreDir, "brand-ref.json").writeText("""{"$ID":"/schemas/$version/core/brand-ref.json","type":"object","properties":{"brandId":{}}}""")
        File(coreDir, "campaign.json").writeText("""{"$ID":"/schemas/$version/core/campaign.json","type":"object","properties":{"brand":{}}}""")

        val registry = SchemaRegistry(tempDir)
        // Relative ref from campaign.json → brand-ref.json
        val result = registry.resolve("brand-ref.json", "core/campaign.json")
        assertNotNull(result, "relative ref should resolve against context file's directory")
        assertTrue(result!!.path("properties").has("brandId"))
    }

    @Test
    fun registry_detectsAdcpVersion_fromFirstSchemaId(@TempDir tempDir: File) {
        val version = "3.0.11"
        File(tempDir, "index.json").writeText("""{"$ID":"/schemas/$version/index.json","type":"object"}""")

        val registry = SchemaRegistry(tempDir)
        assertEquals(version, registry.adcpVersion())
    }

    @Test
    fun registry_toCanonicalPath_stripsVersionPrefix(@TempDir tempDir: File) {
        val version = "3.0.11"
        File(tempDir, "index.json").writeText("""{"$ID":"/schemas/$version/index.json","type":"object"}""")

        val registry = SchemaRegistry(tempDir)
        assertEquals("core/brand-ref.json", registry.toCanonicalPath("/schemas/$version/core/brand-ref.json"))
    }

    @Test
    fun registry_toCanonicalPath_worksWithoutSchemaLoaded(@TempDir tempDir: File) {
        // New implementation uses regex — works purely on the ref string
        val registry = SchemaRegistry(tempDir)
        assertEquals("core/foo.json", registry.toCanonicalPath("/schemas/3.0.11/core/foo.json"))
    }

    @Test
    fun registry_toCanonicalPath_handlesV2xUnversionedRefs(@TempDir tempDir: File) {
        // v2.x refs: /schemas/core/foo.json — no version segment
        val registry = SchemaRegistry(tempDir)
        assertEquals("core/foo.json", registry.toCanonicalPath("/schemas/core/foo.json"))
        assertEquals("enums/delivery-type.json", registry.toCanonicalPath("/schemas/enums/delivery-type.json"))
    }

    @Test
    fun registry_detectsAdcpVersion_fromIndexJsonAdcpVersionField(@TempDir tempDir: File) {
        // v2.x: $id has no version segment; adcp_version field carries the version
        File(tempDir, "index.json").writeText(
            """{"$ID":"/schemas/index.json","adcp_version":"2.5.1","type":"object"}"""
        )
        val registry = SchemaRegistry(tempDir)
        assertEquals("2.5.1", registry.adcpVersion())
    }

    @Test
    fun registry_resolvesV2xRefs_viaIdIndex(@TempDir tempDir: File) {
        // v2.x schemas: $id has no version segment
        val coreDir = File(tempDir, "core").also { it.mkdirs() }
        File(coreDir, "product.json").writeText(
            """{"$ID":"/schemas/core/product.json","type":"object","properties":{"product_id":{"type":"string"}}}"""
        )
        File(tempDir, "index.json").writeText("""{"$ID":"/schemas/index.json","adcp_version":"2.5.1"}""")

        val registry = SchemaRegistry(tempDir)
        val resolved = registry.resolve("/schemas/core/product.json")
        assertNotNull(resolved, "v2.x ref should resolve via idIndex")
        assertTrue(resolved!!.path("properties").has("product_id"))
    }

    @Test
    fun registry_allTypeSchemas_excludesIndexAndManifest(@TempDir tempDir: File) {
        val version = "3.0.11"
        val coreDir = File(tempDir, "core").also { it.mkdirs() }
        File(coreDir, "brand-ref.json").writeText("""{"type":"object","properties":{"id":{}}}""")
        File(tempDir, "index.json").writeText("""{"type":"object"}""")
        File(tempDir, "manifest.json").writeText("""{"type":"object"}""")

        val registry = SchemaRegistry(tempDir)
        val typeSchemas = registry.allTypeSchemas()
        assertTrue(typeSchemas.containsKey("core/brand-ref.json"), "real schemas should be included")
        assertFalse(typeSchemas.containsKey("index.json"), "index.json should be excluded")
        assertFalse(typeSchemas.containsKey("manifest.json"), "manifest.json should be excluded")
    }
}
