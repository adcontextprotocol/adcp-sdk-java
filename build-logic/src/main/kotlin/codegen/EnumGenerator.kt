package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.FieldSpec
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.TypeSpec
import org.jspecify.annotations.Nullable
import java.io.File
import java.nio.file.Path
import javax.lang.model.element.Modifier

/**
 * Generates Java enums from JSON Schema enum definitions.
 * Handles wire-value serialization via `@JsonValue`/`@JsonCreator`,
 * `x-enum-descriptions` for per-constant Javadoc, `x-deprecated-enum-values`
 * for `@Deprecated`, and `x-extensible` for nullable fromValue.
 */
class EnumGenerator(private val ctx: CodegenContext) {

    fun generate(
        schema: JsonNode,
        className: ClassName,
        outputDir: File
    ): List<Path> {
        val enumValues = schema.path("enum").map { it.asText() }
        val enumDescriptions = if (schema.has("x-enum-descriptions")) {
            schema.path("x-enum-descriptions")
        } else {
            schema.path("enumDescriptions")
        }
        val deprecatedValues = schema.path("x-deprecated-enum-values")
            .map { it.asText() }.toSet()
        val isExtensible = schema.path("x-extensible").asBoolean(false)
        val description = schema.path("description").asText("(no description)")

        val enumBuilder = TypeSpec.enumBuilder(className.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${NamingConventions.escape(description)}\n")
            .addAnnotation(Annotations.generated(className.simpleName()))

        enumBuilder.addField(
            FieldSpec.builder(ClassName.get("java.lang", "String"), "value", Modifier.PRIVATE, Modifier.FINAL)
                .build()
        )

        enumBuilder.addMethod(
            MethodSpec.constructorBuilder()
                .addParameter(ClassName.get("java.lang", "String"), "value")
                .addStatement("this.value = value")
                .build()
        )

        enumBuilder.addMethod(
            MethodSpec.methodBuilder("value")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get("java.lang", "String"))
                .addAnnotation(Annotations.JSON_VALUE)
                .addStatement("return value")
                .build()
        )

        enumBuilder.addMethod(buildFromValueMethod(className, isExtensible))

        for (value in enumValues) {
            val enumConstant = NamingConventions.toEnumConstant(value)
            val enumDesc = enumDescriptions.path(value).asText(null)
            val isDeprecated = value in deprecatedValues

            val constantBuilder = TypeSpec.anonymousClassBuilder("\$S", value)
            if (enumDesc != null) {
                constantBuilder.addJavadoc("${NamingConventions.escape(enumDesc)}\n")
            }
            if (isDeprecated) {
                constantBuilder.addJavadoc("@deprecated This value is deprecated by the protocol.\n")
                constantBuilder.addAnnotation(java.lang.Deprecated::class.java)
            }
            enumBuilder.addEnumConstant(enumConstant, constantBuilder.build())
        }

        return listOf(ctx.writeJavaFile(className.packageName(), enumBuilder.build(), outputDir))
    }

    private fun buildFromValueMethod(className: ClassName, extensible: Boolean): MethodSpec {
        val builder = MethodSpec.methodBuilder("fromValue")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addAnnotation(Annotations.JSON_CREATOR)
            .addParameter(ClassName.get("java.lang", "String"), "value")
            .addStatement(
                "for (\$T e : values()) { if (e.value.equals(value)) return e; }",
                className
            )

        return if (extensible) {
            builder.addAnnotation(Nullable::class.java)
                .returns(className)
                .addStatement("return null")
                .build()
        } else {
            builder.returns(className)
                .addStatement(
                    "throw new IllegalArgumentException(\"Unknown \" + \$S + \": \" + value)",
                    className.simpleName()
                )
                .build()
        }
    }
}
