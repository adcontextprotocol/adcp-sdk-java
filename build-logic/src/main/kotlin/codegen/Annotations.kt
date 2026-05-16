package codegen

import com.palantir.javapoet.AnnotationSpec
import com.palantir.javapoet.ClassName

/**
 * Shared JavaPoet [ClassName] constants for annotations used in generated code.
 * Centralizes all annotation references — no hardcoded package strings in generators.
 */
object Annotations {

    // Jackson
    val JSON_PROPERTY: ClassName = ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty")
    val JSON_TYPE_INFO: ClassName = ClassName.get("com.fasterxml.jackson.annotation", "JsonTypeInfo")
    val JSON_TYPE_INFO_ID: ClassName = ClassName.get("com.fasterxml.jackson.annotation", "JsonTypeInfo", "Id")
    val JSON_SUB_TYPES: ClassName = ClassName.get("com.fasterxml.jackson.annotation", "JsonSubTypes")
    val JSON_SUB_TYPE: ClassName = ClassName.get("com.fasterxml.jackson.annotation", "JsonSubTypes", "Type")
    val JSON_VALUE: ClassName = ClassName.get("com.fasterxml.jackson.annotation", "JsonValue")
    val JSON_CREATOR: ClassName = ClassName.get("com.fasterxml.jackson.annotation", "JsonCreator")
    val JSON_ANY_SETTER: ClassName = ClassName.get("com.fasterxml.jackson.annotation", "JsonAnySetter")

    // javax
    val GENERATED: ClassName = ClassName.get("javax.annotation.processing", "Generated")

    private const val CODEGEN_FQN = "org.adcontextprotocol.adcp.codegen.SchemaCodegen"

    /** Builds a `@Generated` annotation with source provenance. */
    fun generated(source: String): AnnotationSpec =
        AnnotationSpec.builder(GENERATED)
            .addMember("value", "\$S", CODEGEN_FQN)
            .addMember("comments", "\$S", "from $source")
            .build()

    /** [ClassName] for `@XEntity` in the given base package. */
    fun xEntity(basePackage: String): ClassName =
        ClassName.get("$basePackage.annotation", "XEntity")
}
