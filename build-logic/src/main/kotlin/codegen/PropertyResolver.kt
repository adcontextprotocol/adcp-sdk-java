package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.palantir.javapoet.AnnotationSpec
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.ParameterSpec
import com.palantir.javapoet.ParameterizedTypeName
import com.palantir.javapoet.TypeName
import org.jspecify.annotations.Nullable

/**
 * Resolves JSON Schema property types to Java types. When a property
 * contains an inline object or oneOf/anyOf, delegates to registered
 * inline generators via [onInlineRecord] and [onInlineUnion].
 *
 * The callbacks are set by [SchemaCodegen] after construction to break
 * the circular dependency between type resolution and code generation.
 */
class PropertyResolver(private val ctx: CodegenContext) {

    internal lateinit var onInlineRecord: (JsonNode, ClassName, String, String) -> ClassName
    internal lateinit var onInlineUnion: (JsonNode, ClassName, String, String) -> ClassName

    fun resolve(
        propSchema: JsonNode,
        parentClass: ClassName,
        propertyName: String,
        contextPath: String
    ): TypeName {
        val ref = propSchema.path("\$ref").asText(null)
        if (ref != null) {
            return ctx.typeRegistry?.resolveRefType(ref)?.className
                ?: ClassName.get("java.lang", "Object")
        }

        val type = propSchema.path("type").asText("")

        if (type == "string" && propSchema.has("enum")) {
            return ClassName.get("java.lang", "String")
        }

        val unionBranches = listOf("oneOf", "anyOf")
            .map { propSchema.path(it) }
            .firstOrNull { it.isArray && !it.isEmpty }
        if (unionBranches != null && SchemaUtils.hasStructuralBranches(unionBranches)) {
            return onInlineUnion(unionBranches, parentClass, propertyName, contextPath)
        }

        if (type == "object" && propSchema.has("properties") && propSchema.path("properties").size() > 0) {
            return onInlineRecord(propSchema, parentClass, propertyName, contextPath)
        }

        if (type == "object") {
            return ParameterizedTypeName.get(
                ClassName.get("java.util", "Map"),
                ClassName.get("java.lang", "String"),
                ClassName.get("java.lang", "Object")
            )
        }

        if (type == "array") {
            val itemsSchema = propSchema.path("items")
            val itemType = if (itemsSchema.isMissingNode || itemsSchema.isEmpty) {
                ClassName.get("java.lang", "Object")
            } else {
                resolve(itemsSchema, parentClass, propertyName + "Item", contextPath)
            }
            return ParameterizedTypeName.get(ClassName.get("java.util", "List"), itemType)
        }

        return when (type) {
            "string" -> ClassName.get("java.lang", "String")
            "integer" -> ClassName.get("java.lang", "Integer")
            "boolean" -> ClassName.get("java.lang", "Boolean")
            "number" -> ClassName.get("java.lang", "Double")
            else -> inferFromConst(propSchema)
        }
    }

    /**
     * Builds ParameterSpecs from schema properties with `@JsonProperty`,
     * `@Nullable`, and `@XEntity` annotations.
     */
    fun buildComponents(
        properties: Map<String, JsonNode>,
        requiredFields: Set<String>,
        parentClass: ClassName,
        contextPath: String
    ): ComponentsResult {
        val specs = mutableListOf<ParameterSpec>()
        val docs = StringBuilder()

        for ((jsonName, propSchema) in properties) {
            val javaName = NamingConventions.toCamelCase(jsonName)
            val type = resolve(propSchema, parentClass, jsonName, contextPath)
            val required = jsonName in requiredFields

            val param = ParameterSpec.builder(type, javaName)
            if (!required) param.addAnnotation(Nullable::class.java)
            param.addAnnotation(
                AnnotationSpec.builder(Annotations.JSON_PROPERTY)
                    .addMember("value", "\$S", jsonName).build()
            )

            val xEntity = propSchema.path("x-entity").asText(null)
            if (xEntity != null) {
                param.addAnnotation(
                    AnnotationSpec.builder(Annotations.xEntity(ctx.basePackage))
                        .addMember("value", "\$S", xEntity).build()
                )
            }

            specs.add(param.build())
            val descr = propSchema.path("description").asText("")
            if (descr.isNotBlank()) {
                docs.append("@param $javaName ${NamingConventions.escape(descr)}\n")
            }
        }

        return ComponentsResult(specs, docs.toString())
    }

    private fun inferFromConst(propSchema: JsonNode): ClassName {
        if (propSchema.has("const")) {
            val v = propSchema.path("const")
            return when {
                v.isTextual -> ClassName.get("java.lang", "String")
                v.isInt -> ClassName.get("java.lang", "Integer")
                v.isBoolean -> ClassName.get("java.lang", "Boolean")
                v.isDouble || v.isFloat -> ClassName.get("java.lang", "Double")
                else -> ClassName.get("java.lang", "Object")
            }
        }
        return ClassName.get("java.lang", "Object")
    }
}

data class ComponentsResult(val specs: List<ParameterSpec>, val javadoc: String)
