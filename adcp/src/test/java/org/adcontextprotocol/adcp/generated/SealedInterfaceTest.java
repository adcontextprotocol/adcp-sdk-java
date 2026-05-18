package org.adcontextprotocol.adcp.generated;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.adcontextprotocol.adcp.generated.collection.BaseCollectionSource;
import org.adcontextprotocol.adcp.generated.collection.DistributionIDsSource;
import org.adcontextprotocol.adcp.generated.collection.PublisherCollectionsSource;
import org.adcontextprotocol.adcp.generated.enums.DeliveryType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for sealed interface generation and Jackson polymorphic
 * deserialization of generated oneOf union types.
 */
class SealedInterfaceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void baseCollectionSource_is_sealed_interface() {
        assertTrue(BaseCollectionSource.class.isSealed(),
                "BaseCollectionSource should be a sealed interface");

        Class<?>[] permitted = BaseCollectionSource.class.getPermittedSubclasses();
        assertNotNull(permitted);
        assertTrue(permitted.length >= 3,
                "Should have at least 3 permitted subtypes, got " + permitted.length);
    }

    @Test
    void distributionIDsSource_implements_baseCollectionSource() {
        assertTrue(BaseCollectionSource.class.isAssignableFrom(DistributionIDsSource.class),
                "DistributionIDsSource should implement BaseCollectionSource");
    }

    @Test
    void discriminated_union_round_trips_via_jackson() throws Exception {
        String json = """
                {
                    "selection_type": "publisher_collections",
                    "publisher_domain": "example.com",
                    "collection_ids": ["c1", "c2"]
                }
                """;

        BaseCollectionSource source = mapper.readValue(json, BaseCollectionSource.class);
        assertInstanceOf(PublisherCollectionsSource.class, source);

        PublisherCollectionsSource pcs = (PublisherCollectionsSource) source;
        assertEquals("example.com", pcs.publisherDomain());
        assertEquals(List.of("c1", "c2"), pcs.collectionIds());

        // Re-serialize and verify wire format
        String reserialized = mapper.writeValueAsString(source);
        assertTrue(reserialized.contains("\"selection_type\":\"publisher_collections\""));
    }

    @Test
    void pattern_matching_exhaustiveness_with_sealed_types() throws Exception {
        String json = """
                {
                    "selection_type": "publisher_collections",
                    "publisher_domain": "test.com",
                    "collection_ids": ["id1"]
                }
                """;

        BaseCollectionSource source = mapper.readValue(json, BaseCollectionSource.class);

        // Demonstrates Java 21 pattern matching with sealed types
        String result = switch (source) {
            case DistributionIDsSource d -> "distribution: " + d.selectionType();
            case PublisherCollectionsSource p -> "publisher: " + p.publisherDomain();
            default -> "other";
        };
        assertEquals("publisher: test.com", result);
    }
}
