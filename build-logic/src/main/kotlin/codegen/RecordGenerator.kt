package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.palantir.javapoet.AnnotationSpec
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.FieldSpec
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.ParameterSpec
import com.palantir.javapoet.ParameterizedTypeName
import com.palantir.javapoet.TypeSpec
import org.jspecify.annotations.Nullable
import java.io.File
import java.nio.file.Path
import javax.lang.model.element.Modifier

/**
 * Generates Java records from JSON Schema object definitions.
 * Handles standalone records, inline nested records (via property resolution),
 * and `*Request` builder generation.
 */
class RecordGenerator(
    private val ctx: CodegenContext,
    private val resolver: PropertyResolver
) {

    /**
     * Generates a record (and its inline types) from a schema, writes to disk.
     * When [parentInterface] is set, the record implements that sealed interface.
     */
    fun generate(
        sourcePath: String,
        schema: JsonNode,
        className: ClassName,
        outputDir: File,
        parentInterface: ClassName? = null
    ): List<Path> {
        val generated = mutableListOf<Path>()
        ctx.inlineTypes.clear()

        val mergedProperties = SchemaUtils.collectProperties(schema, sourcePath, ctx.schemaRegistry)
        val requiredFields = SchemaUtils.collectRequired(schema, ctx.schemaRegistry)
        val isRequest = className.simpleName().endsWith("Request")
        val result = resolver.buildComponents(mergedProperties, requiredFields, className, sourcePath)
        val components = result.specs.toMutableList()

        if (parentInterface == null) {
            val additionalProps = schema.path("additionalProperties")
            if (additionalProps.isBoolean && additionalProps.asBoolean()) {
                val mapType = ParameterizedTypeName.get(
                    ClassName.get("java.util", "Map"),
                    ClassName.get("java.lang", "String"),
                    ClassName.get("java.lang", "Object")
                )
                components.add(
                    ParameterSpec.builder(mapType, "additionalProperties")
                        .addAnnotation(Nullable::class.java)
                        .addAnnotation(AnnotationSpec.builder(Annotations.JSON_ANY_SETTER).build())
                        .build()
                )
            }
        }

        val recordCtor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameters(components)
            .build()

        val description = schema.path("description").asText("(no description)")
        val typeBuilder = TypeSpec.recordBuilder(className.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${NamingConventions.escape(description)}\n\n")
            .addJavadoc(result.javadoc)
            .addAnnotation(Annotations.generated(sourcePath))
            .recordConstructor(recordCtor)

        if (parentInterface != null) {
            typeBuilder.addSuperinterface(parentInterface)
        } else {
            ctx.sealedIndex.parentsFor(sourcePath, className).forEach {
                typeBuilder.addSuperinterface(it)
            }
        }

        if (parentInterface == null && isRequest && components.isNotEmpty()) {
            typeBuilder.addType(buildBuilder(className.simpleName(), components))
            typeBuilder.addMethod(
                MethodSpec.methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ClassName.bestGuess("Builder"))
                    .addStatement("return new Builder()")
                    .build()
            )
        }

        generated.add(ctx.writeJavaFile(className.packageName(), typeBuilder.build(), outputDir))
        for (inline in ctx.inlineTypes.drain()) {
            generated.add(ctx.writeJavaFile(inline.packageName, inline.typeSpec, outputDir))
        }
        return generated
    }

    /**
     * Generates an inline record for a property-level nested object.
     * Adds to [InlineTypeCollector] instead of writing to disk directly.
     * Returns the ClassName for use in the parent record.
     */
    fun generateInline(
        propSchema: JsonNode,
        parentClass: ClassName,
        propertyName: String,
        contextPath: String
    ): ClassName {
        val inlineClassName = parentClass.simpleName() + NamingConventions.toClassName(propertyName)
        val inlineCn = ClassName.get(parentClass.packageName(), inlineClassName)
        val inlineRequired = propSchema.path("required").mapNotNull { it.asText() }.toSet()
        val inlineProps = propSchema.path("properties").fields().asSequence()
            .associate { it.key to it.value }
        val result = resolver.buildComponents(inlineProps, inlineRequired, inlineCn, contextPath)

        val ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameters(result.specs)
            .build()

        ctx.inlineTypes.add(
            parentClass.packageName(),
            TypeSpec.recordBuilder(inlineClassName)
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("${NamingConventions.escape(propSchema.path("description").asText("(no description)"))}\n")
                .addAnnotation(Annotations.generated(contextPath))
                .recordConstructor(ctor)
                .build()
        )
        return inlineCn
    }

    internal fun buildBuilder(
        recordClassName: String,
        components: List<ParameterSpec>
    ): TypeSpec {
        val builderType = ClassName.bestGuess("Builder")
        val recordType = ClassName.bestGuess(recordClassName)

        val builder = TypeSpec.classBuilder("Builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc(
                "Fluent builder for {@link \$T}. Per RFC \u00a7Type generation: " +
                        "*Request types always have builders; *Response types are records " +
                        "and never do.\n", recordType
            )

        components.forEach { c ->
            builder.addField(
                FieldSpec.builder(c.type(), c.name(), Modifier.PRIVATE)
                    .addAnnotation(Nullable::class.java)
                    .build()
            )
        }

        components.forEach { c ->
            builder.addMethod(
                MethodSpec.methodBuilder(c.name())
                    .addModifiers(Modifier.PUBLIC)
                    .returns(builderType)
                    .addParameter(c.type(), c.name())
                    .addStatement("this.\$N = \$N", c.name(), c.name())
                    .addStatement("return this")
                    .build()
            )
        }

        builder.addMethod(
            MethodSpec.methodBuilder("build")
                .addModifiers(Modifier.PUBLIC)
                .returns(recordType)
                .addStatement(
                    "return new \$T(${components.joinToString(", ") { it.name() }})",
                    recordType
                )
                .build()
        )

        return builder.build()
    }
}
