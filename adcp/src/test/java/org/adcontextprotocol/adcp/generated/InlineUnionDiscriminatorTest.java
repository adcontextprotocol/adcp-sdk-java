package org.adcontextprotocol.adcp.generated;

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
}
