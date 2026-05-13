package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.palantir.javapoet.AnnotationSpec
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.FieldSpec
import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.ParameterSpec
import com.palantir.javapoet.ParameterizedTypeName
import com.palantir.javapoet.TypeName
import com.palantir.javapoet.TypeSpec
import org.jspecify.annotations.Nullable
import java.io.File
import java.nio.file.Path
import javax.lang.model.element.Modifier

/**
 * Schema codegen — MVP scope (per the harness §Pre-contributor table in
 * ROADMAP). Generates one record per JSON Schema file. Two output shapes:
 *
 * - `*Request` types → record + nested `Builder` ([D2] / RFC §Type
 *   generation: `*Request` types always have builders).
 * - `*Response` types → record only, no builder.
 *
 * The MVP supports flat objects with scalar fields (string, integer,
 * boolean) and `additionalProperties: false`. Polymorphism (`oneOf` with
 * `discriminator`), arrays, nested objects, and refs to other schemas
 * land on the codegen track ([Track 2 — L0 types & codegen](../../../../../../ROADMAP.md#track-2--l0-types--codegen)).
 */
class SchemaCodegen(private val basePackage: String) {

    private val mapper = ObjectMapper()

    /**
     * Generates a Java record from one schema file. Writes the result to
     * `outputDir/<package-path>/<ClassName>.java`. Returns the generated
     * file path.
     */
    fun generate(schemaFile: File, outputDir: File): Path {
        val schema = mapper.readTree(schemaFile)
        val title = schema.required("title").asText()
        val className = toClassName(title)
        val isRequest = className.endsWith("Request")
        val requiredFields = schema.path("required").mapNotNull { it.asText() }.toSet()

        val properties = schema.path("properties")
        val recordComponents = mutableListOf<ParameterSpec>()
        val fieldDocs = StringBuilder()
        properties.fields().forEach { (jsonName, propSchema) ->
            val javaName = toCamelCase(jsonName)
            val type = jsonSchemaToJavaType(propSchema)
            val required = jsonName in requiredFields

            val paramBuilder = ParameterSpec.builder(type, javaName)
            if (!required) {
                paramBuilder.addAnnotation(Nullable::class.java)
            }
            // JsonProperty maps the snake_case wire name back to the
            // camelCase record component.
            paramBuilder.addAnnotation(
                AnnotationSpec.builder(
                    ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty")
                ).addMember("value", "\$S", jsonName).build()
            )
            recordComponents.add(paramBuilder.build())

            val descr = propSchema.path("description").asText("")
            if (descr.isNotBlank()) {
                fieldDocs.append("@param $javaName ${escape(descr)}\n")
            }
        }

        val recordCtor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameters(recordComponents)
            .build()

        val typeBuilder = TypeSpec.recordBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc(schema.path("description").asText("(no description)") + "\n\n")
            .addJavadoc(fieldDocs.toString())
            .addAnnotation(generatedAnnotation(schemaFile))
            .recordConstructor(recordCtor)

        if (isRequest) {
            typeBuilder.addType(buildBuilder(className, recordComponents))
            typeBuilder.addMethod(
                MethodSpec.methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ClassName.bestGuess("Builder"))
                    .addStatement("return new Builder()")
                    .build()
            )
        }

        val packageName = "$basePackage.${jsonPackagePath(schemaFile)}"
        val javaFile = JavaFile.builder(packageName, typeBuilder.build())
            .skipJavaLangImports(true)
            .indent("    ")
            .build()

        val targetDir = outputDir.toPath().resolve(packageName.replace('.', '/'))
        targetDir.toFile().mkdirs()
        val targetFile = targetDir.resolve("$className.java")
        targetFile.toFile().writeText(javaFile.toString())
        return targetFile
    }

    private fun buildBuilder(
        recordClassName: String,
        components: List<ParameterSpec>
    ): TypeSpec {
        val builderType = ClassName.bestGuess("Builder")
        val recordType = ClassName.bestGuess(recordClassName)

        val builder = TypeSpec.classBuilder("Builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc("Fluent builder for {@link \$T}. Per RFC §Type generation: " +
                    "*Request types always have builders; *Response types are records " +
                    "and never do.\n", recordType)

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

    private fun jsonSchemaToJavaType(prop: JsonNode): TypeName {
        return when (prop.path("type").asText("")) {
            "string" -> ClassName.get("java.lang", "String")
            "integer" -> ClassName.get("java.lang", "Integer")
            "boolean" -> ClassName.get("java.lang", "Boolean")
            "number" -> ClassName.get("java.lang", "Double")
            "array" -> ParameterizedTypeName.get(
                ClassName.get("java.util", "List"),
                jsonSchemaToJavaType(prop.path("items"))
            )
            else -> ClassName.get("java.lang", "Object")
        }
    }

    private fun generatedAnnotation(source: File): AnnotationSpec {
        return AnnotationSpec.builder(
            ClassName.get("javax.annotation.processing", "Generated")
        )
            .addMember("value", "\$S", "org.adcontextprotocol.adcp.codegen.SchemaCodegen")
            .addMember("comments", "\$S", "from ${source.name}")
            .build()
    }

    private fun jsonPackagePath(schemaFile: File): String {
        // schemas/core/pagination-request.json -> "core"
        // schemas/media-buy/get-products-request.json -> "media_buy"
        val parent = schemaFile.parentFile.name
        return parent.replace('-', '_').lowercase()
    }

    private fun toClassName(title: String): String =
        title.split(" ", "-", "_").joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

    private fun toCamelCase(jsonName: String): String {
        val parts = jsonName.split("_")
        return parts.first() + parts.drop(1).joinToString("") {
            it.replaceFirstChar(Char::uppercaseChar)
        }
    }

    private fun escape(text: String): String = text.replace("\$", "\$\$")
}
