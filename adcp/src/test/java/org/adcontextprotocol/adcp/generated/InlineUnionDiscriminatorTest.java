package org.adcontextprotocol.adcp.generated;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.generated.core.FormatAssetsItem;
import org.adcontextprotocol.adcp.generated.core.IndividualImageAsset;
import org.adcontextprotocol.adcp.generated.core.IndividualVideoAsset;
import org.adcontextprotocol.adcp.generated.core.RepeatableGroupAsset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for multi-discriminator inline unions (Format.assets).
 * Verifies that asset_type discriminator correctly distinguishes
 * 15 asset variants, including defaultImpl fallback for RepeatableGroupAsset.
 */
class InlineUnionDiscriminatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void formatAssetsItem_discriminates_image_by_asset_type() throws Exception {
        String json = """
            {
                "item_type": "individual",
                "asset_type": "image",
                "asset_id": "hero_image",
                "required": true
            }
            """;
        FormatAssetsItem item = mapper.readValue(json, FormatAssetsItem.class);
        assertInstanceOf(IndividualImageAsset.class, item);
        assertEquals("image", ((IndividualImageAsset) item).assetType());
    }

    @Test
    void formatAssetsItem_discriminates_video_by_asset_type() throws Exception {
        String json = """
            {
                "item_type": "individual",
                "asset_type": "video",
                "asset_id": "hero_video",
                "required": true
            }
            """;
        FormatAssetsItem item = mapper.readValue(json, FormatAssetsItem.class);
        assertInstanceOf(IndividualVideoAsset.class, item);
    }

    @Test
    void formatAssetsItem_defaultImpl_handles_repeatable_group() throws Exception {
        String json = """
            {
                "item_type": "repeatable_group",
                "asset_group_id": "slides",
                "required": true,
                "min_count": 1,
                "max_count": 10,
                "assets": []
            }
            """;
        FormatAssetsItem item = mapper.readValue(json, FormatAssetsItem.class);
        assertInstanceOf(RepeatableGroupAsset.class, item);
        assertEquals("repeatable_group", ((RepeatableGroupAsset) item).itemType());
    }

    @Test
    void formatAssetsItem_round_trips_image_asset() throws Exception {
        String json = """
            {
                "item_type": "individual",
                "asset_type": "image",
                "asset_id": "logo",
                "asset_role": "brand_logo",
                "required": false
            }
            """;
        FormatAssetsItem item = mapper.readValue(json, FormatAssetsItem.class);
        String reserialized = mapper.writeValueAsString(item);
        assertTrue(reserialized.contains("\"asset_type\":\"image\""));
        assertTrue(reserialized.contains("\"asset_id\":\"logo\""));
    }

    /**
     * Simulates the multi-uncovered-variant fix: a polymorphic interface where two
     * variants have no discriminator const (so neither maps to a named @JsonSubTypes.Type).
     * The codegen fix picks the first uncovered variant as {@code defaultImpl}, so any
     * JSON whose discriminator value doesn't match a named variant must deserialize as
     * that first uncovered variant rather than throwing a mapping exception.
     */
    @Test
    void multi_uncovered_variants_uses_first_as_default() throws Exception {
        // Hand-crafted interface that mirrors what codegen emits when there are 2
        // uncovered variants (VariantA is first → defaultImpl).
        String json = """
            { "kind": "completely_unknown_value", "name": "test" }
            """;
        MultiUncoveredUnion result = mapper.readValue(json, MultiUncoveredUnion.class);
        // Must deserialize as VariantA (the defaultImpl / first uncovered), not throw.
        assertInstanceOf(VariantA.class, result,
                "Unknown discriminator value must fall back to the first uncovered variant");
        assertEquals("test", ((VariantA) result).name());
    }

    @Test
    void multi_uncovered_named_variant_still_discriminates() throws Exception {
        // The named variant 'b' must still resolve correctly alongside the defaultImpl.
        String json = """
            { "kind": "b", "value": "hello" }
            """;
        MultiUncoveredUnion result = mapper.readValue(json, MultiUncoveredUnion.class);
        assertInstanceOf(VariantB.class, result);
        assertEquals("hello", ((VariantB) result).value());
    }

    // ── Synthetic types used by the multi-uncovered tests ───────────────────

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind", defaultImpl = VariantA.class)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = VariantB.class, name = "b"),
            @JsonSubTypes.Type(value = VariantA.class),   // uncovered #1 → defaultImpl
            @JsonSubTypes.Type(value = VariantC.class)    // uncovered #2 → not defaultImpl
    })
    sealed interface MultiUncoveredUnion permits VariantA, VariantB, VariantC {}

    record VariantA(String kind, String name) implements MultiUncoveredUnion {}
    record VariantB(String kind, String value) implements MultiUncoveredUnion {}
    record VariantC(String kind, String extra) implements MultiUncoveredUnion {}
}
