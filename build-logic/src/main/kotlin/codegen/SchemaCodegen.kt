package codegen

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
 * Full schema codegen — generates Java records, enums, and builders from
 * AdCP JSON Schema files. Uses [SchemaRegistry] for `$ref` resolution
 * and [TypeRegistry] for mapping schemas to Java types.
 *
 * Output shapes per RFC §Type generation and D2:
 * - `*Request` types → record + nested `Builder`
 * - `*Response` types → record only, no builder
 * - String enums → Java enums with `@JsonValue`
 * - `$ref` to type aliases → inline as `String`, `Integer`, etc.
 * - `allOf` → merge properties from all branches
 * - `additionalProperties: true` → `Map<String, Object>` field
 * - Inline objects → separate top-level records
 *
 * - Polymorphic `oneOf` → sealed interfaces with `@JsonTypeInfo`/`@JsonSubTypes`
 */
class SchemaCodegen(
    private val basePackage: String,
    private val schemaRegistry: SchemaRegistry? = null,
    private val typeRegistry: TypeRegistry? = null,
    private val preprocessor: SchemaPreprocessor? = null
) {

    private val mapper = ObjectMapper()

    /** Inline types generated during processing, collected for output. */
    private val pendingInlineTypes = mutableListOf<Pair<String, TypeSpec>>()

    /**
     * Maps schema paths to sealed interface ClassNames they must implement.
     * Also maps derived ClassNames for inline variants that may collide with
     * standalone types. Built by [buildSealedInterfaceMap] before generation starts.
     */
    private val sealedInterfaceMap = mutableMapOf<String, MutableList<ClassName>>()
    private val sealedInterfaceByClassName = mutableMapOf<String, MutableList<ClassName>>()

    /**
     * Generates all types from the registry. Returns paths of all generated files.
     */
    fun generateAll(outputDir: File): List<Path> {
        val reg = typeRegistry ?: error("TypeRegistry required for generateAll")
        val generated = mutableListOf<Path>()
        val packages = mutableSetOf<String>()

        // Pre-pass: build the sealed interface membership map
        buildSealedInterfaceMap(reg)

        for ((path, className) in reg.allGeneratableEntries()) {
            val schema = schemaRegistry?.get(path) ?: continue
            val processed = preprocessor?.preprocess(schema) ?: schema
            val category = reg.getCategory(path) ?: continue

            try {
                val files = when (category) {
                    TypeRegistry.TypeCategory.RECORD,
                    TypeRegistry.TypeCategory.COMPOSED -> generateRecord(path, processed, className, outputDir)
                    TypeRegistry.TypeCategory.ENUM -> generateEnum(processed, className, outputDir)
                    TypeRegistry.TypeCategory.POLYMORPHIC -> generatePolymorphic(path, processed, className, outputDir)
                    else -> emptyList()
                }
                generated.addAll(files)
                packages.add(className.packageName())
            } catch (e: Exception) {
                System.err.println("WARN: Failed to generate $path (${className.simpleName()}): ${e.message}")
            }
        }

        // Generate @NullMarked package-info.java for every generated package
        for (pkg in packages) {
            generated.add(generatePackageInfo(pkg, outputDir))
        }

        return generated
    }

    /**
     * Pre-pass: scans all POLYMORPHIC schemas to determine which types
     * need to implement sealed interfaces. For each oneOf branch that is
     * a `$ref`, maps the referenced schema path → sealed interface ClassName.
     */
    private fun buildSealedInterfaceMap(reg: TypeRegistry) {
        sealedInterfaceMap.clear()
        sealedInterfaceByClassName.clear()
        for ((path, className) in reg.allGeneratableEntries()) {
            val category = reg.getCategory(path) ?: continue
            if (category != TypeRegistry.TypeCategory.POLYMORPHIC) continue

            val schema = schemaRegistry?.get(path) ?: continue
            val processed = preprocessor?.preprocess(schema) ?: schema
            val oneOf = processed.path("oneOf")
            if (!oneOf.isArray || oneOf.isEmpty) continue

            // Skip schemas that have own properties without discriminator (treated as records)
            val ownProps = processed.path("properties")
            if (ownProps.size() > 0 && !processed.has("discriminator") && !hasBranchDiscriminator(oneOf)) continue

            val discriminatorProp = findDiscriminatorProperty(processed, oneOf)

            for ((index, branch) in oneOf.withIndex()) {
                if (branch.has("\$ref")) {
                    val refPath = branch.get("\$ref").asText()
                    val canonicalPath = schemaRegistry?.toCanonicalPath(refPath)
                    if (canonicalPath != null) {
                        sealedInterfaceMap.getOrPut(canonicalPath) { mutableListOf() }.add(className)
                    }
                } else {
                    // Inline branch: derive the variant class name and register by
                    // fully qualified name so standalone types with matching names
                    // pick up the implements clause
                    val resolvedBranch = branch
                    val (variantSimpleName, _) = deriveVariantName(
                        resolvedBranch, discriminatorProp, className.simpleName(), index
                    )
                    val variantFqn = "${className.packageName()}.$variantSimpleName"
                    sealedInterfaceByClassName.getOrPut(variantFqn) { mutableListOf() }.add(className)
                }
            }
        }
    }

    /**
     * Generates a Java record from one schema file (legacy single-file API).
     * Used by the MVP test path. For full generation, use [generateAll].
     */
    fun generate(schemaFile: File, outputDir: File): Path {
        val schema = mapper.readTree(schemaFile)
        val title = schema.required("title").asText()
        val className = toClassName(title)
        val packageName = "$basePackage.${jsonPackagePath(schemaFile)}"
        val cn = ClassName.get(packageName, className)
        val files = generateRecord(schemaFile.name, schema, cn, outputDir)
        return files.first()
    }

    /**
     * Generates a record type for an object schema. Handles:
     * - Flat scalar properties
     * - `$ref` to other schemas (resolved via TypeRegistry)
     * - `allOf` composition (merge properties)
     * - Arrays with typed items
     * - Nested inline objects → generates separate records
     * - `additionalProperties: true` → Map field
     */
    private fun generateRecord(
        sourcePath: String,
        schema: JsonNode,
        className: ClassName,
        outputDir: File
    ): List<Path> {
        val generated = mutableListOf<Path>()
        pendingInlineTypes.clear()

        // Collect properties, merging allOf if present
        val mergedProperties = collectProperties(schema, sourcePath)
        val requiredFields = collectRequired(schema)
        val isRequest = className.simpleName().endsWith("Request")

        val recordComponents = mutableListOf<ParameterSpec>()
        val fieldDocs = StringBuilder()

        for ((jsonName, propSchema) in mergedProperties) {
            val javaName = toCamelCase(jsonName)
            val type = resolvePropertyType(propSchema, className, jsonName, sourcePath)
            val required = jsonName in requiredFields

            val paramBuilder = ParameterSpec.builder(type, javaName)
            if (!required) {
                paramBuilder.addAnnotation(Nullable::class.java)
            }
            paramBuilder.addAnnotation(
                AnnotationSpec.builder(
                    ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty")
                ).addMember("value", "\$S", jsonName).build()
            )

            // Add @XEntity if the schema property has an x-entity annotation
            val xEntity = propSchema.path("x-entity").asText(null)
            if (xEntity != null) {
                paramBuilder.addAnnotation(
                    AnnotationSpec.builder(
                        ClassName.get("$basePackage.annotation", "XEntity")
                    ).addMember("value", "\$S", xEntity).build()
                )
            }

            recordComponents.add(paramBuilder.build())

            val descr = propSchema.path("description").asText("")
            if (descr.isNotBlank()) {
                fieldDocs.append("@param $javaName ${escape(descr)}\n")
            }
        }

        // Handle additionalProperties: true → add a Map field
        val additionalProps = schema.path("additionalProperties")
        if (additionalProps.isBoolean && additionalProps.asBoolean()) {
            val mapType = ParameterizedTypeName.get(
                ClassName.get("java.util", "Map"),
                ClassName.get("java.lang", "String"),
                ClassName.get("java.lang", "Object")
            )
            recordComponents.add(
                ParameterSpec.builder(mapType, "additionalProperties")
                    .addAnnotation(Nullable::class.java)
                    .addAnnotation(
                        AnnotationSpec.builder(
                            ClassName.get("com.fasterxml.jackson.annotation", "JsonAnySetter")
                        ).build()
                    )
                    .build()
            )
        }

        val recordCtor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameters(recordComponents)
            .build()

        val description = schema.path("description").asText("(no description)")
        val typeBuilder = TypeSpec.recordBuilder(className.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${escape(description)}\n\n")
            .addJavadoc(fieldDocs.toString())
            .addAnnotation(generatedAnnotation(sourcePath))
            .recordConstructor(recordCtor)

        // If this type is a member of one or more sealed interfaces, add implements
        val sealedParents = sealedInterfaceMap[sourcePath]
            ?: sealedInterfaceByClassName["${className.packageName()}.${className.simpleName()}"]
        if (sealedParents != null) {
            for (parent in sealedParents) {
                typeBuilder.addSuperinterface(parent)
            }
        }

        if (isRequest && recordComponents.isNotEmpty()) {
            typeBuilder.addType(buildBuilder(className.simpleName(), recordComponents))
            typeBuilder.addMethod(
                MethodSpec.methodBuilder("builder")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .returns(ClassName.bestGuess("Builder"))
                    .addStatement("return new Builder()")
                    .build()
            )
        }

        generated.add(writeJavaFile(className.packageName(), typeBuilder.build(), outputDir))

        // Write any inline types that were generated during property resolution
        for ((inlinePackage, inlineType) in pendingInlineTypes) {
            generated.add(writeJavaFile(inlinePackage, inlineType, outputDir))
        }
        pendingInlineTypes.clear()

        return generated
    }

    /**
     * Generates a Java enum from a string enum schema.
     * Handles x-* extensions:
     * - x-enum-descriptions → per-constant Javadoc
     * - x-deprecated-enum-values → @Deprecated on constants
     * - x-extensible → tolerant fromValue (returns null instead of throwing)
     */
    private fun generateEnum(
        schema: JsonNode,
        className: ClassName,
        outputDir: File
    ): List<Path> {
        val enumValues = schema.path("enum").map { it.asText() }
        // Support both "enumDescriptions" and "x-enum-descriptions"
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
            .addJavadoc("${escape(description)}\n")
            .addAnnotation(generatedAnnotation(className.simpleName()))

        // Private field + constructor + @JsonValue getter for wire format
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
                .addAnnotation(ClassName.get("com.fasterxml.jackson.annotation", "JsonValue"))
                .addStatement("return value")
                .build()
        )

        // @JsonCreator factory for deserialization
        if (isExtensible) {
            // Extensible enums: return null for unknown values instead of throwing.
            // x-extensible means the spec expects new values to be added.
            enumBuilder.addMethod(
                MethodSpec.methodBuilder("fromValue")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addAnnotation(ClassName.get("com.fasterxml.jackson.annotation", "JsonCreator"))
                    .addAnnotation(Nullable::class.java)
                    .returns(className)
                    .addParameter(ClassName.get("java.lang", "String"), "value")
                    .addStatement(
                        "for (\$T e : values()) { if (e.value.equals(value)) return e; }",
                        className
                    )
                    .addStatement("return null")
                    .build()
            )
        } else {
            enumBuilder.addMethod(
                MethodSpec.methodBuilder("fromValue")
                    .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                    .addAnnotation(ClassName.get("com.fasterxml.jackson.annotation", "JsonCreator"))
                    .returns(className)
                    .addParameter(ClassName.get("java.lang", "String"), "value")
                    .addStatement(
                        "for (\$T e : values()) { if (e.value.equals(value)) return e; }",
                        className
                    )
                    .addStatement(
                        "throw new IllegalArgumentException(\"Unknown \" + \$S + \": \" + value)",
                        className.simpleName()
                    )
                    .build()
            )
        }

        for (value in enumValues) {
            val enumConstant = toEnumConstant(value)
            val enumDesc = enumDescriptions.path(value).asText(null)
            val isDeprecated = value in deprecatedValues
            val constantBuilder = TypeSpec.anonymousClassBuilder("\$S", value)
            if (enumDesc != null) {
                constantBuilder.addJavadoc("${escape(enumDesc)}\n")
            }
            if (isDeprecated) {
                constantBuilder.addJavadoc("@deprecated This value is deprecated by the protocol.\n")
                constantBuilder.addAnnotation(java.lang.Deprecated::class.java)
            }
            enumBuilder.addEnumConstant(enumConstant, constantBuilder.build())
        }

        return listOf(writeJavaFile(className.packageName(), enumBuilder.build(), outputDir))
    }

    /**
     * Generates sealed interfaces for `oneOf` unions. Two strategies:
     * - Discriminated: schemas with `discriminator.propertyName` or `const` values
     *   → `@JsonTypeInfo(use = NAME, property = "...")` + `@JsonSubTypes`
     * - Structural: no explicit discriminator
     *   → `@JsonTypeInfo(use = DEDUCTION)` — Jackson deduces type from properties
     *
     * Each oneOf branch becomes a record that `implements` the sealed interface.
     */
    private fun generatePolymorphic(
        sourcePath: String,
        schema: JsonNode,
        className: ClassName,
        outputDir: File
    ): List<Path> {
        val generated = mutableListOf<Path>()
        pendingInlineTypes.clear()

        // If this schema has own properties but also oneOf, treat it as a record
        // (the oneOf constrains which property combos are valid, not type discrimination)
        val ownProps = schema.path("properties")
        val oneOf = schema.path("oneOf")
        if (ownProps.size() > 0 && !schema.has("discriminator") && !hasBranchDiscriminator(oneOf)) {
            return generateRecord(sourcePath, schema, className, outputDir)
        }

        if (!oneOf.isArray || oneOf.isEmpty) {
            // Fallback: generate marker interface
            return generateMarkerInterface(sourcePath, schema, className, outputDir)
        }

        val description = schema.path("description").asText("(no description)")

        // Detect discriminator property
        val discriminatorProp = findDiscriminatorProperty(schema, oneOf)

        // Build variant records for each oneOf branch
        val variantNames = mutableListOf<Pair<String, ClassName>>() // (discriminator value, ClassName)

        for ((index, branch) in oneOf.withIndex()) {
            val isRef = branch.has("\$ref")
            val resolvedBranch = if (isRef && schemaRegistry != null) {
                val refPath = branch.get("\$ref").asText()
                schemaRegistry.resolve(refPath) ?: branch
            } else branch

            if (isRef) {
                // $ref branch: the referenced type will be generated separately with
                // `implements` added via sealedInterfaceMap. Just determine the ClassName.
                val refPath = branch.get("\$ref").asText()
                val canonicalPath = schemaRegistry?.toCanonicalPath(refPath)
                val existingClassName = if (canonicalPath != null) {
                    typeRegistry?.getClassName(canonicalPath)
                } else null

                if (existingClassName != null) {
                    val discValue = if (discriminatorProp != null) {
                        resolvedBranch.path("properties").path(discriminatorProp).path("const").asText("")
                    } else ""
                    variantNames.add(discValue to existingClassName)
                    continue
                }
                // If we couldn't find the referenced type, fall through to inline generation
            }

            // Inline branch (or fallback for unresolved $ref): generate variant record
            val (variantSimpleName, discriminatorValue) = deriveVariantName(
                resolvedBranch, discriminatorProp, className.simpleName(), index
            )

            val variantClassName = ClassName.get(className.packageName(), variantSimpleName)
            variantNames.add(discriminatorValue to variantClassName)

            val variantGenerated = generateVariantRecord(
                sourcePath, resolvedBranch, variantClassName, className, outputDir
            )
            generated.addAll(variantGenerated)
        }

        // Build the interface with Jackson annotations.
        // Use sealed only if all variants are in the same package (Java requirement).
        val allSamePackage = variantNames.all { (_, vc) ->
            vc.packageName() == className.packageName()
        }
        val interfaceBuilder = TypeSpec.interfaceBuilder(className.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${escape(description)}\n")
            .addAnnotation(generatedAnnotation(sourcePath))

        if (allSamePackage) {
            interfaceBuilder.addModifiers(Modifier.SEALED)
        }

        // Add @JsonTypeInfo
        val jsonTypeInfoClass = ClassName.get("com.fasterxml.jackson.annotation", "JsonTypeInfo")
        val jsonTypeInfoId = ClassName.get("com.fasterxml.jackson.annotation", "JsonTypeInfo", "Id")

        if (discriminatorProp != null) {
            // Check if there's exactly one variant without a discriminator value
            // (e.g., RepeatableGroupAsset has no asset_type). Use defaultImpl for it.
            val uncoveredVariants = variantNames.filter { (discValue, _) -> discValue.isBlank() }
            // visible=true keeps the discriminator property in the JSON stream so
            // immutable record constructors can receive it as a regular field.
            val annotBuilder = AnnotationSpec.builder(jsonTypeInfoClass)
                .addMember("use", "\$T.NAME", jsonTypeInfoId)
                .addMember("property", "\$S", discriminatorProp)
                .addMember("visible", "true")
            if (uncoveredVariants.size == 1) {
                annotBuilder.addMember("defaultImpl", "\$T.class", uncoveredVariants[0].second)
            }
            interfaceBuilder.addAnnotation(annotBuilder.build())
        } else {
            interfaceBuilder.addAnnotation(
                AnnotationSpec.builder(jsonTypeInfoClass)
                    .addMember("use", "\$T.DEDUCTION", jsonTypeInfoId)
                    .build()
            )
        }

        // Add @JsonSubTypes
        val jsonSubTypesClass = ClassName.get("com.fasterxml.jackson.annotation", "JsonSubTypes")
        val jsonSubTypeClass = ClassName.get("com.fasterxml.jackson.annotation", "JsonSubTypes", "Type")

        val subTypesBuilder = AnnotationSpec.builder(jsonSubTypesClass)
        for ((discValue, variantClass) in variantNames) {
            val typeAnnotation = AnnotationSpec.builder(jsonSubTypeClass)
                .addMember("value", "\$T.class", variantClass)
            if (discriminatorProp != null && discValue.isNotBlank()) {
                typeAnnotation.addMember("name", "\$S", discValue)
            }
            subTypesBuilder.addMember("value", "\$L", typeAnnotation.build())
        }
        interfaceBuilder.addAnnotation(subTypesBuilder.build())

        // Add permits for each variant (only for sealed interfaces)
        if (allSamePackage) {
            for ((_, variantClass) in variantNames) {
                interfaceBuilder.addPermittedSubclass(variantClass)
            }
        }

        generated.add(writeJavaFile(className.packageName(), interfaceBuilder.build(), outputDir))

        return generated
    }

    /** Check if any oneOf branch has a const discriminator property. */
    private fun hasBranchDiscriminator(oneOf: JsonNode): Boolean {
        if (!oneOf.isArray) return false
        val reg = schemaRegistry
        for (branch in oneOf) {
            val resolved = if (branch.has("\$ref") && reg != null) {
                reg.resolve(branch.get("\$ref").asText()) ?: branch
            } else branch
            val propsIter = resolved.path("properties").fields()
            while (propsIter.hasNext()) {
                val entry = propsIter.next()
                if (entry.value.has("const")) return true
            }
        }
        return false
    }

    /** Find the discriminator property name — from explicit `discriminator` or inferred from `const`. */
    private fun findDiscriminatorProperty(schema: JsonNode, oneOf: JsonNode): String? {
        // Explicit discriminator
        val explicit = schema.path("discriminator").path("propertyName").asText(null)
        if (explicit != null) return explicit

        // Infer from const values in branches
        val reg = schemaRegistry
        return pickBestDiscriminator(oneOf) { branch ->
            if (branch.has("\$ref") && reg != null) {
                reg.resolve(branch.get("\$ref").asText()) ?: branch
            } else branch
        }
    }

    /**
     * Selects the best discriminator property from oneOf/anyOf branches.
     * When multiple const properties exist, picks the one with the most
     * unique values (best discrimination power). Returns null if no
     * const properties are found.
     */
    private fun pickBestDiscriminator(
        branches: JsonNode,
        resolver: (JsonNode) -> JsonNode
    ): String? {
        // Collect const values per property name across all branches
        val constValuesByProp = mutableMapOf<String, MutableList<String>>()
        for (branch in branches) {
            val resolved = resolver(branch)
            resolved.path("properties").fields().forEach { entry ->
                val constVal = entry.value.path("const").asText(null)
                if (constVal != null) {
                    constValuesByProp.getOrPut(entry.key) { mutableListOf() }.add(constVal)
                }
            }
        }

        if (constValuesByProp.isEmpty()) return null
        if (constValuesByProp.size == 1) return constValuesByProp.keys.first()

        val branchCount = branches.size()

        // Best: property that covers ALL branches with ALL-UNIQUE values
        val fullCoverage = constValuesByProp.entries
            .filter { (_, values) -> values.size == branchCount && values.toSet().size == branchCount }
            .maxByOrNull { (_, values) -> values.size }
            ?.key
        if (fullCoverage != null) return fullCoverage

        // Fallback: property with the most unique values (best partial discriminator)
        return constValuesByProp.entries
            .maxByOrNull { (_, values) -> values.toSet().size }
            ?.key
    }

    /** Derive variant class name and discriminator value from a oneOf branch. */
    private fun deriveVariantName(
        branch: JsonNode,
        discriminatorProp: String?,
        parentName: String,
        index: Int
    ): Pair<String, String> {
        // Use branch title if available
        val branchTitle = branch.path("title").asText(null)
        if (branchTitle != null) {
            val name = toClassName(branchTitle)
            val discValue = if (discriminatorProp != null) {
                branch.path("properties").path(discriminatorProp).path("const").asText("")
            } else ""
            return name to discValue
        }

        // Use const discriminator value
        if (discriminatorProp != null) {
            val constValue = branch.path("properties").path(discriminatorProp).path("const").asText(null)
            if (constValue != null) {
                val name = parentName + toClassName(constValue)
                return name to constValue
            }
        }

        // Fallback: parent name + index
        return "${parentName}Variant${index}" to ""
    }

    /**
     * Generates a record type for a oneOf variant branch that implements the sealed parent interface.
     */
    private fun generateVariantRecord(
        sourcePath: String,
        branchSchema: JsonNode,
        variantClassName: ClassName,
        parentInterface: ClassName,
        outputDir: File
    ): List<Path> {
        val generated = mutableListOf<Path>()
        pendingInlineTypes.clear()

        val mergedProperties = collectProperties(branchSchema, sourcePath)
        val requiredFields = collectRequired(branchSchema)

        val recordComponents = mutableListOf<ParameterSpec>()
        val fieldDocs = StringBuilder()

        for ((jsonName, propSchema) in mergedProperties) {
            val javaName = toCamelCase(jsonName)
            val type = resolvePropertyType(propSchema, variantClassName, jsonName, sourcePath)
            val required = jsonName in requiredFields

            val paramBuilder = ParameterSpec.builder(type, javaName)
            if (!required) {
                paramBuilder.addAnnotation(Nullable::class.java)
            }
            paramBuilder.addAnnotation(
                AnnotationSpec.builder(
                    ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty")
                ).addMember("value", "\$S", jsonName).build()
            )

            val xEntity = propSchema.path("x-entity").asText(null)
            if (xEntity != null) {
                paramBuilder.addAnnotation(
                    AnnotationSpec.builder(
                        ClassName.get("$basePackage.annotation", "XEntity")
                    ).addMember("value", "\$S", xEntity).build()
                )
            }

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

        val description = branchSchema.path("description").asText("(no description)")
        val typeBuilder = TypeSpec.recordBuilder(variantClassName.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${escape(description)}\n\n")
            .addJavadoc(fieldDocs.toString())
            .addAnnotation(generatedAnnotation(sourcePath))
            .addSuperinterface(parentInterface)
            .recordConstructor(recordCtor)

        generated.add(writeJavaFile(variantClassName.packageName(), typeBuilder.build(), outputDir))

        for ((inlinePackage, inlineType) in pendingInlineTypes) {
            generated.add(writeJavaFile(inlinePackage, inlineType, outputDir))
        }
        pendingInlineTypes.clear()

        return generated
    }

    /** Generates a plain marker interface (fallback for unhandled polymorphic types). */
    private fun generateMarkerInterface(
        sourcePath: String,
        schema: JsonNode,
        className: ClassName,
        outputDir: File
    ): List<Path> {
        val description = schema.path("description").asText("(no description)")
        val typeBuilder = TypeSpec.interfaceBuilder(className.simpleName())
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${escape(description)}\n")
            .addAnnotation(generatedAnnotation(sourcePath))

        return listOf(writeJavaFile(className.packageName(), typeBuilder.build(), outputDir))
    }

    /**
     * Collects all properties from a schema, including merging allOf branches.
     * Returns a map of property name → property schema.
     */
    private fun collectProperties(schema: JsonNode, contextPath: String): Map<String, JsonNode> {
        val properties = LinkedHashMap<String, JsonNode>()

        // Direct properties
        schema.path("properties").fields().forEach { (name, prop) ->
            properties[name] = prop
        }

        // Merge allOf branches
        val allOf = schema.path("allOf")
        if (allOf.isArray) {
            for (branch in allOf) {
                // If the branch is a $ref, resolve it
                val resolved = if (branch.has("\$ref")) {
                    schemaRegistry?.resolve(branch.path("\$ref").asText(), contextPath)
                } else {
                    branch
                }
                resolved?.path("properties")?.fields()?.forEach { (name, prop) ->
                    if (name !in properties) {
                        properties[name] = prop
                    }
                }
            }
        }

        return properties
    }

    /**
     * Collects all required field names from a schema, including allOf branches.
     * Resolves $ref in allOf branches to pick up required arrays from referenced schemas.
     */
    private fun collectRequired(schema: JsonNode): Set<String> {
        val required = mutableSetOf<String>()
        schema.path("required").forEach { required.add(it.asText()) }
        schema.path("allOf").forEach { branch ->
            val resolved = if (branch.has("\$ref") && schemaRegistry != null) {
                schemaRegistry.resolve(branch.path("\$ref").asText()) ?: branch
            } else branch
            resolved.path("required").forEach { required.add(it.asText()) }
        }
        return required
    }

    /**
     * Resolves the Java type for a property schema. Handles:
     * - `$ref` → look up in TypeRegistry
     * - `type: string/integer/boolean/number` → primitive types
     * - `type: string` with `enum` → inline enum or referenced enum
     * - `type: array` → `List<ItemType>`
     * - `type: object` with properties → generate inline record
     * - `additionalProperties: true` without properties → `Map<String, Object>`
     * - `oneOf`/`anyOf` inside property → inline sealed interface
     */
    private fun resolvePropertyType(
        propSchema: JsonNode,
        parentClass: ClassName,
        propertyName: String,
        contextPath: String
    ): TypeName {
        // $ref takes priority
        val ref = propSchema.path("\$ref").asText(null)
        if (ref != null) {
            if (typeRegistry != null) {
                val typeInfo = typeRegistry.resolveRefType(ref)
                return typeInfo.className
            }
            return ClassName.get("java.lang", "Object")
        }

        val type = propSchema.path("type").asText("")

        // Inline enum
        if (type == "string" && propSchema.has("enum")) {
            return ClassName.get("java.lang", "String")
        }

        // Property-level oneOf/anyOf → inline sealed interface
        // Only if branches are structural (have properties, $ref, type declarations)
        val oneOf = propSchema.path("oneOf")
        val anyOf = propSchema.path("anyOf")
        val unionBranches = when {
            oneOf.isArray && !oneOf.isEmpty -> oneOf
            anyOf.isArray && !anyOf.isEmpty -> anyOf
            else -> null
        }
        if (unionBranches != null && hasStructuralBranches(unionBranches)) {
            return generateInlineUnion(unionBranches, parentClass, propertyName, contextPath)
        }

        // Object with properties → generate inline type
        // (anyOf/oneOf with only validation constraints falls through here)
        if (type == "object" && propSchema.has("properties") && propSchema.path("properties").size() > 0) {
            return generateInlineRecord(propSchema, parentClass, propertyName, contextPath)
        }

        // Object with additionalProperties only → Map
        if (type == "object") {
            return ParameterizedTypeName.get(
                ClassName.get("java.util", "Map"),
                ClassName.get("java.lang", "String"),
                ClassName.get("java.lang", "Object")
            )
        }

        // Array
        if (type == "array") {
            val itemsSchema = propSchema.path("items")
            val itemType = if (itemsSchema.isMissingNode || itemsSchema.isEmpty) {
                ClassName.get("java.lang", "Object")
            } else {
                resolvePropertyType(itemsSchema, parentClass, propertyName + "Item", contextPath)
            }
            return ParameterizedTypeName.get(ClassName.get("java.util", "List"), itemType)
        }

        // Scalars
        return when (type) {
            "string" -> ClassName.get("java.lang", "String")
            "integer" -> ClassName.get("java.lang", "Integer")
            "boolean" -> ClassName.get("java.lang", "Boolean")
            "number" -> ClassName.get("java.lang", "Double")
            else -> {
                // Properties with only `const` (no type) — infer from const value
                if (propSchema.has("const")) {
                    val constValue = propSchema.path("const")
                    return when {
                        constValue.isTextual -> ClassName.get("java.lang", "String")
                        constValue.isInt -> ClassName.get("java.lang", "Integer")
                        constValue.isBoolean -> ClassName.get("java.lang", "Boolean")
                        constValue.isDouble || constValue.isFloat -> ClassName.get("java.lang", "Double")
                        else -> ClassName.get("java.lang", "Object")
                    }
                }
                ClassName.get("java.lang", "Object")
            }
        }
    }

    /**
     * Generates an inline sealed interface for a property-level oneOf/anyOf.
     * Each branch becomes a variant record implementing the interface.
     */
    private fun generateInlineUnion(
        branches: JsonNode,
        parentClass: ClassName,
        propertyName: String,
        contextPath: String
    ): ClassName {
        val interfaceName = parentClass.simpleName() + toClassName(propertyName)
        val interfaceClassName = ClassName.get(parentClass.packageName(), interfaceName)

        // Find discriminator property from branches
        val discriminatorProp = findBranchDiscriminatorProp(branches, contextPath)

        val variantNames = mutableListOf<Pair<String, ClassName>>()
        val externalVariants = mutableSetOf<ClassName>() // top-level types we can't modify

        for ((index, branch) in branches.withIndex()) {
            // Resolve $ref if present
            val isRef = branch.has("\$ref")
            val resolvedBranch = if (isRef) {
                val refPath = branch.get("\$ref").asText()
                if (refPath.startsWith("#/")) {
                    schemaRegistry?.resolve(refPath, contextPath)
                } else {
                    schemaRegistry?.resolve(refPath)
                } ?: branch
            } else branch

            if (isRef) {
                val refPath = branch.get("\$ref").asText()
                val canonicalPath = schemaRegistry?.toCanonicalPath(refPath)
                val existingClassName = if (canonicalPath != null) {
                    typeRegistry?.getClassName(canonicalPath)
                } else null

                if (existingClassName != null) {
                    val discValue = if (discriminatorProp != null) {
                        resolvedBranch.path("properties").path(discriminatorProp).path("const").asText("")
                    } else ""
                    variantNames.add(discValue to existingClassName)
                    externalVariants.add(existingClassName)
                    continue
                }
            }

            // Determine variant name from branch
            val mergedBranch = mergeAllOfInBranch(resolvedBranch, branch, contextPath)
            val (variantSimpleName, discriminatorValue) = deriveVariantName(
                mergedBranch, discriminatorProp, interfaceName, index
            )

            val variantClassName = ClassName.get(parentClass.packageName(), variantSimpleName)
            variantNames.add(discriminatorValue to variantClassName)

            // Generate the variant record
            val mergedProperties = collectProperties(mergedBranch, contextPath)
            val requiredFields = collectRequired(mergedBranch)

            val components = mutableListOf<ParameterSpec>()
            val fieldDocs = StringBuilder()

            for ((jsonName, propSchemaInner) in mergedProperties) {
                val javaName = toCamelCase(jsonName)
                val propType = resolvePropertyType(propSchemaInner, variantClassName, jsonName, contextPath)
                val required = jsonName in requiredFields

                val paramBuilder = ParameterSpec.builder(propType, javaName)
                if (!required) {
                    paramBuilder.addAnnotation(Nullable::class.java)
                }
                paramBuilder.addAnnotation(
                    AnnotationSpec.builder(
                        ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty")
                    ).addMember("value", "\$S", jsonName).build()
                )

                val xEntity = propSchemaInner.path("x-entity").asText(null)
                if (xEntity != null) {
                    paramBuilder.addAnnotation(
                        AnnotationSpec.builder(
                            ClassName.get("$basePackage.annotation", "XEntity")
                        ).addMember("value", "\$S", xEntity).build()
                    )
                }

                components.add(paramBuilder.build())
                val descr = propSchemaInner.path("description").asText("")
                if (descr.isNotBlank()) {
                    fieldDocs.append("@param $javaName ${escape(descr)}\n")
                }
            }

            val recordCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameters(components)
                .build()

            val description = mergedBranch.path("description").asText("(no description)")
            val typeBuilder = TypeSpec.recordBuilder(variantSimpleName)
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("${escape(description)}\n\n")
                .addJavadoc(fieldDocs.toString())
                .addAnnotation(generatedAnnotation(contextPath))
                .addSuperinterface(interfaceClassName)
                .recordConstructor(recordCtor)

            pendingInlineTypes.add(parentClass.packageName() to typeBuilder.build())
        }

        // Build the sealed interface
        // If any variant is a top-level type we can't modify, use non-sealed
        val allSamePackage = variantNames.all { (_, vc) ->
            vc.packageName() == interfaceClassName.packageName()
        }
        val canBeSealedInterface = allSamePackage && externalVariants.isEmpty()

        val interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(generatedAnnotation(contextPath))

        if (canBeSealedInterface) {
            interfaceBuilder.addModifiers(Modifier.SEALED)
        }
        val jsonTypeInfoClass = ClassName.get("com.fasterxml.jackson.annotation", "JsonTypeInfo")
        val jsonTypeInfoId = ClassName.get("com.fasterxml.jackson.annotation", "JsonTypeInfo", "Id")

        if (discriminatorProp != null) {
            val uncoveredVariants = variantNames.filter { (discValue, _) -> discValue.isBlank() }
            val annotBuilder = AnnotationSpec.builder(jsonTypeInfoClass)
                .addMember("use", "\$T.NAME", jsonTypeInfoId)
                .addMember("property", "\$S", discriminatorProp)
                .addMember("visible", "true")
            if (uncoveredVariants.size == 1) {
                annotBuilder.addMember("defaultImpl", "\$T.class", uncoveredVariants[0].second)
            }
            interfaceBuilder.addAnnotation(annotBuilder.build())
        } else {
            interfaceBuilder.addAnnotation(
                AnnotationSpec.builder(jsonTypeInfoClass)
                    .addMember("use", "\$T.DEDUCTION", jsonTypeInfoId)
                    .build()
            )
        }

        // Add @JsonSubTypes
        val jsonSubTypesClass = ClassName.get("com.fasterxml.jackson.annotation", "JsonSubTypes")
        val jsonSubTypeClass = ClassName.get("com.fasterxml.jackson.annotation", "JsonSubTypes", "Type")

        val subTypesBuilder = AnnotationSpec.builder(jsonSubTypesClass)
        for ((discValue, variantClass) in variantNames) {
            val typeAnnotation = AnnotationSpec.builder(jsonSubTypeClass)
                .addMember("value", "\$T.class", variantClass)
            if (discriminatorProp != null && discValue.isNotBlank()) {
                typeAnnotation.addMember("name", "\$S", discValue)
            }
            subTypesBuilder.addMember("value", "\$L", typeAnnotation.build())
        }
        interfaceBuilder.addAnnotation(subTypesBuilder.build())

        // Add permits for sealed interfaces (only inline variants)
        if (canBeSealedInterface) {
            for ((_, variantClass) in variantNames) {
                interfaceBuilder.addPermittedSubclass(variantClass)
            }
        }

        pendingInlineTypes.add(parentClass.packageName() to interfaceBuilder.build())
        return interfaceClassName
    }

    /**
     * Merges allOf branches within an inline oneOf branch.
     * Handles `$defs` references by resolving them in the context schema.
     */
    private fun mergeAllOfInBranch(resolvedBranch: JsonNode, originalBranch: JsonNode, contextPath: String): JsonNode {
        val allOf = resolvedBranch.path("allOf")
        if (!allOf.isArray || allOf.isEmpty) return resolvedBranch

        // Merge allOf into a single combined schema
        val merged = mapper.createObjectNode()

        // Start with direct properties from the branch
        val mergedProps = mapper.createObjectNode()
        resolvedBranch.path("properties").fields().forEach { (name, prop) ->
            mergedProps.set<JsonNode>(name, prop)
        }

        val mergedRequired = mapper.createArrayNode()
        resolvedBranch.path("required").forEach { mergedRequired.add(it) }

        // Merge each allOf branch
        for (allOfBranch in allOf) {
            val resolved = if (allOfBranch.has("\$ref")) {
                val ref = allOfBranch.get("\$ref").asText()
                schemaRegistry?.resolve(ref, contextPath)
            } else {
                allOfBranch
            }
            resolved?.path("properties")?.fields()?.forEach { (name, prop) ->
                if (!mergedProps.has(name)) {
                    mergedProps.set<JsonNode>(name, prop)
                }
            }
            resolved?.path("required")?.forEach { req ->
                if (mergedRequired.none { it.asText() == req.asText() }) {
                    mergedRequired.add(req)
                }
            }
        }

        merged.set<JsonNode>("properties", mergedProps)
        if (mergedRequired.size() > 0) {
            merged.set<JsonNode>("required", mergedRequired)
        }

        // Copy metadata
        resolvedBranch.path("title").asText(null)?.let { merged.put("title", it) }
        resolvedBranch.path("description").asText(null)?.let { merged.put("description", it) }
        if (resolvedBranch.has("additionalProperties")) {
            merged.set<JsonNode>("additionalProperties", resolvedBranch.path("additionalProperties"))
        }

        return merged
    }

    /**
     * Generates an inline record type for a property with object type + properties.
     */
    private fun generateInlineRecord(
        propSchema: JsonNode,
        parentClass: ClassName,
        propertyName: String,
        contextPath: String
    ): ClassName {
        val inlineClassName = parentClass.simpleName() + toClassName(propertyName)
        val inlineCn = ClassName.get(parentClass.packageName(), inlineClassName)
        val inlineRequired = propSchema.path("required").mapNotNull { it.asText() }.toSet()

        val components = mutableListOf<ParameterSpec>()
        propSchema.path("properties").fields().forEach { (name, prop) ->
            val javaName = toCamelCase(name)
            val propType = resolvePropertyType(prop, inlineCn, name, contextPath)
            val paramBuilder = ParameterSpec.builder(propType, javaName)
            if (name !in inlineRequired) {
                paramBuilder.addAnnotation(Nullable::class.java)
            }
            paramBuilder.addAnnotation(
                AnnotationSpec.builder(
                    ClassName.get("com.fasterxml.jackson.annotation", "JsonProperty")
                ).addMember("value", "\$S", name).build()
            )
            components.add(paramBuilder.build())
        }

        val ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameters(components)
            .build()

        val inlineType = TypeSpec.recordBuilder(inlineClassName)
            .addModifiers(Modifier.PUBLIC)
            .addJavadoc("${escape(propSchema.path("description").asText("(no description)"))}\n")
            .addAnnotation(generatedAnnotation(contextPath))
            .recordConstructor(ctor)
            .build()

        pendingInlineTypes.add(parentClass.packageName() to inlineType)
        return inlineCn
    }

    /** Find discriminator property from oneOf/anyOf branches (const fields). */
    private fun findBranchDiscriminatorProp(branches: JsonNode, contextPath: String): String? {
        return pickBestDiscriminator(branches) { branch ->
            val resolved = if (branch.has("\$ref")) {
                val ref = branch.get("\$ref").asText()
                schemaRegistry?.resolve(ref, contextPath) ?: branch
            } else branch
            mergeAllOfInBranch(resolved, branch, contextPath)
        }
    }

    /**
     * Checks whether oneOf/anyOf branches are structural (represent distinct types)
     * vs just validation constraints (only `required`, `not`, `if`/`then`/`else`).
     * Validation-only branches should not generate union types.
     */
    private fun hasStructuralBranches(branches: JsonNode): Boolean {
        val validationOnly = setOf("required", "not", "if", "then", "else")
        for (branch in branches) {
            val keys = branch.fieldNames().asSequence().toSet()
            // A branch is structural if it has properties, $ref, type, allOf, or title
            if (keys.any { it !in validationOnly }) return true
        }
        return false
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

    /**
     * Generates a package-info.java file with @NullMarked for the given package.
     * Per ROADMAP: "JSpecify @Nullable annotations on every public type."
     */
    private fun generatePackageInfo(packageName: String, outputDir: File): Path {
        val targetDir = outputDir.toPath().resolve(packageName.replace('.', '/'))
        targetDir.toFile().mkdirs()
        val targetFile = targetDir.resolve("package-info.java")
        targetFile.toFile().writeText(
            """@org.jspecify.annotations.NullMarked
package $packageName;
"""
        )
        return targetFile
    }

    private fun writeJavaFile(packageName: String, typeSpec: TypeSpec, outputDir: File): Path {
        val javaFile = JavaFile.builder(packageName, typeSpec)
            .skipJavaLangImports(true)
            .indent("    ")
            .build()

        val typeName = typeSpec.name() ?: error("TypeSpec has no name")
        val targetDir = outputDir.toPath().resolve(packageName.replace('.', '/'))
        targetDir.toFile().mkdirs()
        val targetFile = targetDir.resolve("$typeName.java")
        targetFile.toFile().writeText(javaFile.toString())
        return targetFile
    }

    private fun generatedAnnotation(source: String): AnnotationSpec {
        return AnnotationSpec.builder(
            ClassName.get("javax.annotation.processing", "Generated")
        )
            .addMember("value", "\$S", "org.adcontextprotocol.adcp.codegen.SchemaCodegen")
            .addMember("comments", "\$S", "from $source")
            .build()
    }

    private fun toClassName(title: String): String {
        // Strip parenthetical suffixes: "Format Reference (Structured Object)" → "Format Reference"
        val cleaned = title.replace(Regex("\\s*\\([^)]*\\)\\s*"), " ").trim()
        return cleaned.split(" ", "-", "_")
            .filter { it.isNotBlank() }
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    private fun toCamelCase(jsonName: String): String {
        // Strip leading $ (e.g., "$schema" → "schema")
        val stripped = jsonName.removePrefix("${'$'}")
        val parts = stripped.split("_", "-")
        val raw = parts.first() + parts.drop(1).joinToString("") {
            it.replaceFirstChar(Char::uppercaseChar)
        }
        return sanitizeJavaName(raw)
    }

    private fun toEnumConstant(value: String): String {
        if (value.isBlank()) return "_EMPTY"
        val raw = value.removePrefix("${'$'}")
            .uppercase()
            .replace('.', '_')
            .replace('-', '_')
            .replace(' ', '_')
            .replace(Regex("[^A-Z0-9_]"), "_")
        // Prefix with underscore if starts with digit
        val cleaned = if (raw.firstOrNull()?.isDigit() == true) "_$raw" else raw
        // Collapse multiple underscores
        return cleaned.replace(Regex("_+"), "_").trimEnd('_')
    }

    /**
     * Sanitizes a string to be a valid Java identifier. Escapes reserved
     * words by appending an underscore.
     */
    private fun sanitizeJavaName(name: String): String {
        val reserved = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "var", "yield", "record", "sealed", "permits"
        )
        return if (name in reserved) "${name}_" else name
    }

    private fun jsonPackagePath(schemaFile: File): String {
        val parent = schemaFile.parentFile.name
        return parent.replace('-', '_').lowercase()
    }

    private fun escape(text: String): String =
        text.replace("${'$'}", "${'$'}${'$'}")
}
