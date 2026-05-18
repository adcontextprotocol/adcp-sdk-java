package org.adcontextprotocol.adcp.generated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.annotation.XEntity;
import org.adcontextprotocol.adcp.generated.collection.CreateCollectionListResponse;
import org.adcontextprotocol.adcp.generated.collection.CollectionList;
import org.adcontextprotocol.adcp.generated.core.PaginationRequest;
import org.adcontextprotocol.adcp.generated.enums.DeliveryType;
import org.adcontextprotocol.adcp.generated.media_buy.GetProductsRequest;
import org.adcontextprotocol.adcp.schema.AdcpObjectMapperFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip serialization tests for generated types, covering:
 * <ul>
 *     <li>Complex request types with builders, nested $ref types, enums</li>
 *     <li>Enum serialization with @JsonValue / @JsonCreator</li>
 *     <li>@XEntity annotation presence on entity-ID fields</li>
 *     <li>Builder pattern on *Request types</li>
 * </ul>
 */
class CodegenRoundTripTest {

    // AdcpObjectMapperFactory includes JavaTimeModule (required for OffsetDateTime/LocalDate)
    private final ObjectMapper mapper = AdcpObjectMapperFactory.create();

    @Test
    void getProductsRequest_builder_and_round_trip() throws Exception {
        GetProductsRequest req = GetProductsRequest.builder()
                .buyingMode("brief")
                .brief("We need display ads for spring campaign")
                .preferredDeliveryTypes(List.of(DeliveryType.GUARANTEED))
                .build();

        String json = mapper.writeValueAsString(req);
        JsonNode parsed = mapper.readTree(json);

        assertEquals("brief", parsed.get("buying_mode").asText());
        assertEquals("We need display ads for spring campaign", parsed.get("brief").asText());
        assertFalse(parsed.has("buyingMode"), "camelCase leaked into wire format");

        // Round-trip
        GetProductsRequest decoded = mapper.readValue(json, GetProductsRequest.class);
        assertEquals(req.buyingMode(), decoded.buyingMode());
        assertEquals(req.brief(), decoded.brief());
    }

    @Test
    void enum_serializes_to_wire_value() throws Exception {
        assertEquals("guaranteed", DeliveryType.GUARANTEED.value());
        assertEquals("non_guaranteed", DeliveryType.NON_GUARANTEED.value());

        // Jackson round-trip
        String json = mapper.writeValueAsString(DeliveryType.GUARANTEED);
        assertEquals("\"guaranteed\"", json);
        DeliveryType decoded = mapper.readValue(json, DeliveryType.class);
        assertEquals(DeliveryType.GUARANTEED, decoded);
    }

    @Test
    void enum_fromValue_throws_on_unknown() {
        assertThrows(IllegalArgumentException.class,
                () -> DeliveryType.fromValue("nonexistent"));
    }

    @Test
    void paginationRequest_nested_in_getProductsRequest() throws Exception {
        GetProductsRequest req = GetProductsRequest.builder()
                .buyingMode("wholesale")
                .pagination(PaginationRequest.builder()
                        .maxResults(50)
                        .cursor("page2")
                        .build())
                .build();

        String json = mapper.writeValueAsString(req);
        JsonNode parsed = mapper.readTree(json);

        JsonNode pagination = parsed.get("pagination");
        assertNotNull(pagination, "nested pagination should be present");
        assertEquals(50, pagination.get("max_results").asInt());
        assertEquals("page2", pagination.get("cursor").asText());
    }

    @Test
    void xEntity_annotation_present_on_entity_id_fields() {
        RecordComponent[] components = CollectionList.class.getRecordComponents();
        RecordComponent listIdComponent = Arrays.stream(components)
                .filter(c -> c.getName().equals("listId"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("listId component not found"));

        XEntity xEntity = listIdComponent.getAnnotation(XEntity.class);
        assertNotNull(xEntity, "@XEntity annotation should be present on listId");
        assertEquals("collection_list", xEntity.value());
    }

    @Test
    void additionalProperties_round_trips_at_parent_level() throws Exception {
        // CreateCollectionListResponse uses additionalProperties:true, so unknown
        // JSON fields must survive a full serialize → deserialize cycle at the
        // parent object level (not nested under an "additionalProperties" key).
        String json = """
                {
                    "list": {
                        "list_id": "lst_abc",
                        "name": "My List"
                    },
                    "auth_token": "tok_secret",
                    "x_custom_field": "extra_value",
                    "x_another": 42
                }
                """;
        CreateCollectionListResponse decoded = mapper.readValue(json, CreateCollectionListResponse.class);

        // Extra fields must be captured in the map, not dropped.
        assertNotNull(decoded.additionalProperties(), "additionalProperties map must not be null");
        assertEquals("extra_value", decoded.additionalProperties().get("x_custom_field").asText());
        assertEquals(42, decoded.additionalProperties().get("x_another").asInt());

        // Re-serialize: extra fields must appear at the top level, NOT nested under
        // an "additionalProperties" object — that would be a broken round-trip.
        String reserialised = mapper.writeValueAsString(decoded);
        JsonNode reserialisedNode = mapper.readTree(reserialised);

        assertFalse(reserialisedNode.has("additionalProperties"),
                "Extra fields must NOT be wrapped under 'additionalProperties' key on the wire");
        assertEquals("extra_value", reserialisedNode.get("x_custom_field").asText(),
                "Extra fields must appear at the parent level after re-serialization");
        assertEquals(42, reserialisedNode.get("x_another").asInt());
    }
}
