package org.adcontextprotocol.adcp.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as an entity identifier in the AdCP schema.
 * Mirrors the {@code x-entity} annotation from the protocol's JSON Schemas.
 *
 * <p>Entity types identify what domain object a string field references
 * (e.g., {@code "product"}, {@code "account"}, {@code "advertiser_brand"}).
 * The full list of entity types is defined in
 * {@code schemas/core/x-entity-types.json} in the protocol tarball.
 *
 * <p>This annotation is retained at runtime so that storyboard runners,
 * conformance harnesses, and entity-tracking lint tools can introspect
 * entity identity relationships via reflection.
 *
 * @see <a href="https://adcontextprotocol.org">AdCP Protocol</a>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER})
public @interface XEntity {
    /**
     * The entity type name (e.g., {@code "product"}, {@code "account"},
     * {@code "advertiser_brand"}).
     */
    String value();
}
