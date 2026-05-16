package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.palantir.javapoet.AnnotationSpec
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.TypeSpec
import java.io.File
import java.nio.file.Path
import javax.lang.model.element.Modifier

/**
 * Generates sealed interfaces for JSON Schema `oneOf` discriminated unions.
 * Handles top-level polymorphic types, inline property-level unions,
 * and marker interfaces for schemas without oneOf branches.
 */
class SealedInterfaceGenerator(
    private val ctx: CodegenContext,
    private val resolver: PropertyResolver,
    private val recordGen: RecordGenerator
) {

    /**
     * Generates a sealed interface (and variant records) from a polymorphic schema.
     * Falls back to [RecordGenerator] if the schema has own properties without
     * a discriminator, or to a marker interface if there are no oneOf branches.
     */
    fun generate(
        sourcePath: String,
        schema: JsonNode,
        className: ClassName,
        outputDir: File
    ): List<Path> {
        val generated = mutableListOf<Path>()
        ctx.inlineTypes.clear()

        val ownProps = schema.path("properties")
        val oneOf = schema.path("oneOf")

        // Schema has own properties but no discriminator → treat as record
        if (ownProps.size() > 0 && !schema.has("discriminator") &&
            !DiscriminatorDetector.hasBranchDiscriminator(oneOf, ctx::resolveBranch)
        ) {
            return recordGen.generate(sourcePath, schema, className, outputDir)
        }

        if (!oneOf.isArray || oneOf.isEmpty) {
            return generateMarker(sourcePath, schema, className, outputDir)
        }

        val discriminatorProp = DiscriminatorDetector.findDiscriminatorProperty(
            schema, oneOf, ctx::resolveBranch
        )

        val variantNames = mutableListOf<Pair<String, ClassName>>()
        for ((index, branch) in oneOf.withIndex()) {
            val isRef = branch.has("\$ref")
            val resolvedBranch = if (isRef) ctx.resolveBranch(branch) else branch

            if (isRef) {
                val canonicalPath = ctx.schemaRegistry?.toCanonicalPath(branch.get("\$ref").asText())
                val existingClassName = canonicalPath?.let { ctx.typeRegistry?.getClassName(it) }
                if (existingClassName != null) {
                    val discValue = discriminatorProp?.let {
                        resolvedBranch.path("properties").path(it).path("const").asText("")
                    } ?: ""
                    variantNames.add(discValue to existingClassName)
                    continue
                }
            }

            val (variantSimpleName, discriminatorValue) = deriveVariantName(
                resolvedBranch, discriminatorProp, className.simpleName(), index
            )
            val variantClassName = ClassName.get(className.packageName(), variantSimpleName)
            variantNames.add(discriminatorValue to variantClassName)

            generated.addAll(
                recordGen.generate(sourcePath, resolvedBranch, variantClassName, outputDir, className)
            )
        }

        val allSamePackage = variantNames.all { it.second.packageName() == className.packageName() }
        val description = schema.path("description").asText("(no description)")
        val interfaceSpec = buildSealedInterface(
            className.simpleName(), sourcePath, description,
            discriminatorProp, variantNames, canBeSealed = allSamePackage
        )
        generated.add(ctx.writeJavaFile(className.packageName(), interfaceSpec, outputDir))
        return generated
    }

    /**
     * Generates an inline sealed interface for a property-level oneOf/anyOf.
     * Adds to [InlineTypeCollector] instead of writing to disk.
     * Returns the ClassName for use in the parent record.
     */
    fun generateInline(
        branches: JsonNode,
        parentClass: ClassName,
        propertyName: String,
        contextPath: String
    ): ClassName {
        val interfaceName = parentClass.simpleName() + NamingConventions.toClassName(propertyName)
        val interfaceClassName = ClassName.get(parentClass.packageName(), interfaceName)

        val discriminatorProp = findBranchDiscriminatorProp(branches, contextPath)
        val variantNames = mutableListOf<Pair<String, ClassName>>()
        val externalVariants = mutableSetOf<ClassName>()

        for ((index, branch) in branches.withIndex()) {
            val isRef = branch.has("\$ref")
            val resolvedBranch = if (isRef) {
                val refPath = branch.get("\$ref").asText()
                (if (refPath.startsWith("#/")) {
                    ctx.schemaRegistry?.resolve(refPath, contextPath)
                } else {
                    ctx.schemaRegistry?.resolve(refPath)
                }) ?: branch
            } else branch

            if (isRef) {
                val canonicalPath = ctx.schemaRegistry?.toCanonicalPath(branch.get("\$ref").asText())
                val existingClassName = canonicalPath?.let { ctx.typeRegistry?.getClassName(it) }
                if (existingClassName != null) {
                    val discValue = discriminatorProp?.let {
                        resolvedBranch.path("properties").path(it).path("const").asText("")
                    } ?: ""
                    variantNames.add(discValue to existingClassName)
                    externalVariants.add(existingClassName)
                    continue
                }
            }

            val mergedBranch = SchemaUtils.mergeAllOfInBranch(
                resolvedBranch, contextPath, ctx.schemaRegistry
            )
            val (variantSimpleName, discriminatorValue) = deriveVariantName(
                mergedBranch, discriminatorProp, interfaceName, index
            )
            val variantClassName = ClassName.get(parentClass.packageName(), variantSimpleName)
            variantNames.add(discriminatorValue to variantClassName)

            // Build inline variant record
            val mergedProperties = SchemaUtils.collectProperties(mergedBranch, contextPath, ctx.schemaRegistry)
            val requiredFields = SchemaUtils.collectRequired(mergedBranch, ctx.schemaRegistry)
            val result = resolver.buildComponents(mergedProperties, requiredFields, variantClassName, contextPath)

            val recordCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameters(result.specs)
                .build()

            val description = mergedBranch.path("description").asText("(no description)")
            ctx.inlineTypes.add(
                parentClass.packageName(),
                TypeSpec.recordBuilder(variantSimpleName)
                    .addModifiers(Modifier.PUBLIC)
                    .addJavadoc("${NamingConventions.escape(description)}\n\n${result.javadoc}")
                    .addAnnotation(Annotations.generated(contextPath))
                    .addSuperinterface(interfaceClassName)
                    .recordConstructor(recordCtor)
                    .build()
            )
        }

        val canBeSealed = variantNames.all {
            it.second.packageName() == interfaceClassName.packageName()
        } && externalVariants.isEmpty()

        ctx.inlineTypes.add(
            parentClass.packageName(),
            buildSealedInterface(
                interfaceName, contextPath, "(no description)",
                discriminatorProp, variantNames, canBeSealed
            )
        )
        return interfaceClassName
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun buildSealedInterface(
        interfaceName: String,
        sourcePath: String,
        description: String,
        discriminatorProp: String?,
        variantNames: List<Pair<String, ClassName>>,
        canBeSealed: Boolean
    ): TypeSpec {
        val builder = TypeSpec.interfaceBuilder(interfaceName)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${NamingConventions.escape(description)}\n")
            .addAnnotation(Annotations.generated(sourcePath))

        if (canBeSealed) builder.addModifiers(Modifier.SEALED)

        addPolymorphicAnnotations(builder, discriminatorProp, variantNames)

        if (canBeSealed) {
            for ((_, variantClass) in variantNames) {
                builder.addPermittedSubclass(variantClass)
            }
        }

        return builder.build()
    }

    private fun addPolymorphicAnnotations(
        builder: TypeSpec.Builder,
        discriminatorProp: String?,
        variantNames: List<Pair<String, ClassName>>
    ) {
        if (discriminatorProp != null) {
            val uncoveredVariants = variantNames.filter { (discValue, _) -> discValue.isBlank() }
            val annotBuilder = AnnotationSpec.builder(Annotations.JSON_TYPE_INFO)
                .addMember("use", "\$T.NAME", Annotations.JSON_TYPE_INFO_ID)
                .addMember("property", "\$S", discriminatorProp)
                .addMember("visible", "true")
            if (uncoveredVariants.size == 1) {
                annotBuilder.addMember("defaultImpl", "\$T.class", uncoveredVariants[0].second)
            }
            builder.addAnnotation(annotBuilder.build())
        } else {
            builder.addAnnotation(
                AnnotationSpec.builder(Annotations.JSON_TYPE_INFO)
                    .addMember("use", "\$T.DEDUCTION", Annotations.JSON_TYPE_INFO_ID)
                    .build()
            )
        }

        val subTypesBuilder = AnnotationSpec.builder(Annotations.JSON_SUB_TYPES)
        for ((discValue, variantClass) in variantNames) {
            val typeAnnotation = AnnotationSpec.builder(Annotations.JSON_SUB_TYPE)
                .addMember("value", "\$T.class", variantClass)
            if (discriminatorProp != null && discValue.isNotBlank()) {
                typeAnnotation.addMember("name", "\$S", discValue)
            }
            subTypesBuilder.addMember("value", "\$L", typeAnnotation.build())
        }
        builder.addAnnotation(subTypesBuilder.build())
    }

    private fun generateMarker(
        sourcePath: String,
        schema: JsonNode,
        className: ClassName,
        outputDir: File
    ): List<Path> {
        val description = schema.path("description").asText("(no description)")
        val typeBuilder = TypeSpec.interfaceBuilder(className.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${NamingConventions.escape(description)}\n")
            .addAnnotation(Annotations.generated(sourcePath))
        return listOf(ctx.writeJavaFile(className.packageName(), typeBuilder.build(), outputDir))
    }

    private fun findBranchDiscriminatorProp(branches: JsonNode, contextPath: String): String? =
        DiscriminatorDetector.pickBestDiscriminator(branches) { branch ->
            SchemaUtils.mergeAllOfInBranch(ctx.resolveBranch(branch), contextPath, ctx.schemaRegistry)
        }

    companion object {
        /**
         * Derives a variant class name and discriminator value from a oneOf branch.
         * Shared between [SealedInterfaceGenerator] and [SchemaCodegen.buildSealedInterfaceMap].
         */
        fun deriveVariantName(
            branch: JsonNode,
            discriminatorProp: String?,
            parentName: String,
            index: Int
        ): Pair<String, String> {
            val branchTitle = branch.path("title").asText(null)
            if (branchTitle != null) {
                val name = NamingConventions.toClassName(branchTitle)
                val discValue = discriminatorProp?.let {
                    branch.path("properties").path(it).path("const").asText("")
                } ?: ""
                return name to discValue
            }

            if (discriminatorProp != null) {
                val constValue = branch.path("properties").path(discriminatorProp).path("const").asText(null)
                if (constValue != null) {
                    return (parentName + NamingConventions.toClassName(constValue)) to constValue
                }
            }

            return "${parentName}Variant${index}" to ""
        }
    }
}
