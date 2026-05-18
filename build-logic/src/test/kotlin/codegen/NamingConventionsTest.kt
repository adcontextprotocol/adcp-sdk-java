package codegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NamingConventionsTest {

    // ── toClassName ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "toClassName({0}) = {1}")
    @CsvSource(
        "ad request,             AdRequest",
        "Ad-Request,             AdRequest",
        "ad_request,             AdRequest",
        "AdRequest,              AdRequest",
        "Creative Manifest,      CreativeManifest",
        "Brand Ref (Core),       BrandRef",
        "  spaced  ,             Spaced",
        "a,                      A",
    )
    fun toClassName_various(input: String, expected: String) {
        assertEquals(expected, NamingConventions.toClassName(input))
    }

    @Test
    fun toClassName_stripsParentheticalWithLeadingSpace() {
        // "(Core)" including its preceding space must be stripped
        assertEquals("BrandRef", NamingConventions.toClassName("Brand Ref (Core)"))
    }

    // ── toCamelCase ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "toCamelCase({0}) = {1}")
    @CsvSource(
        "foo_bar,         fooBar",
        "kebab-case,      kebabCase",
        "already,         already",
        "URL,             URL",
        "\$ref,            ref",
        "class,           class_",
        "record,          record_",
        "sealed,          sealed_",
        "int,             int_",
        "foo_bar_baz,     fooBarBaz",
    )
    fun toCamelCase_various(input: String, expected: String) {
        assertEquals(expected, NamingConventions.toCamelCase(input))
    }

    // ── toEnumConstant ───────────────────────────────────────────────────────

    @ParameterizedTest(name = "toEnumConstant({0}) = {1}")
    @CsvSource(
        "BANNER,             BANNER",
        "banner,             BANNER",
        "native-display,     NATIVE_DISPLAY",
        "native display,     NATIVE_DISPLAY",
        "v1.0,               V1_0",
        "123abc,             _123ABC",
        "\$special,          SPECIAL",
    )
    fun toEnumConstant_various(input: String, expected: String) {
        assertEquals(expected, NamingConventions.toEnumConstant(input))
    }

    @Test
    fun toEnumConstant_emptyString_returnsEmpty() {
        assertEquals("_EMPTY", NamingConventions.toEnumConstant(""))
    }

    @Test
    fun toEnumConstant_blankString_returnsEmpty() {
        assertEquals("_EMPTY", NamingConventions.toEnumConstant("   "))
    }

    @Test
    fun toEnumConstant_collapsesMultipleUnderscores() {
        val result = NamingConventions.toEnumConstant("a--b")
        assertEquals("A_B", result)
    }

    // ── sanitizeJavaName ─────────────────────────────────────────────────────

    @Test
    fun sanitizeJavaName_passesThrough_nonReserved() {
        assertEquals("myField", NamingConventions.sanitizeJavaName("myField"))
    }

    @Test
    fun sanitizeJavaName_appendsUnderscore_forReservedWord() {
        assertEquals("class_", NamingConventions.sanitizeJavaName("class"))
        assertEquals("new_", NamingConventions.sanitizeJavaName("new"))
        assertEquals("yield_", NamingConventions.sanitizeJavaName("yield"))
    }

    // ── derivePackageName ────────────────────────────────────────────────────

    @Test
    fun derivePackageName_topLevelFile_returnsGeneratedPackage() {
        assertEquals(
            "org.example.generated",
            NamingConventions.derivePackageName("org.example", "brand-ref.json")
        )
    }

    @Test
    fun derivePackageName_subdirectory_appendsSubpackage() {
        assertEquals(
            "org.example.generated.core",
            NamingConventions.derivePackageName("org.example", "core/brand-ref.json")
        )
    }

    @Test
    fun derivePackageName_kebabDirName_convertedToUnderscore() {
        assertEquals(
            "org.example.generated.ad_formats",
            NamingConventions.derivePackageName("org.example", "ad-formats/banner.json")
        )
    }

    @Test
    fun derivePackageName_withVersionNamespace_injectsSegment() {
        assertEquals(
            "org.example.generated.v2_5.core",
            NamingConventions.derivePackageName("org.example", "core/brand-ref.json", "v2_5")
        )
    }

    @Test
    fun derivePackageName_blankVersionNamespace_isIgnored() {
        assertEquals(
            "org.example.generated.core",
            NamingConventions.derivePackageName("org.example", "core/brand-ref.json", "")
        )
    }

    // ── deriveClassName ──────────────────────────────────────────────────────

    @Test
    fun deriveClassName_usesTitle_whenPresent() {
        assertEquals("BrandRef", NamingConventions.deriveClassName("anything/ignored.json", "Brand Ref"))
    }

    @Test
    fun deriveClassName_fallsBackToFileName_whenTitleNull() {
        assertEquals("BrandRef", NamingConventions.deriveClassName("core/brand-ref.json", null))
    }
}
