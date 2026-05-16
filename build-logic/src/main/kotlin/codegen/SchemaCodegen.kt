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
 * Generates Java records, enums, sealed interfaces, and builders from
 * AdCP JSON Schema files.
 *
 * Output shapes per RFC §Type generation and D2:
 * - `*Request` → record + nested Builder
 * - `*Response` → record only
 * - String enums → Java enum with `@JsonValue`
 * - `oneOf` → sealed interface with `@JsonTypeInfo` / `@JsonSubTypes`
 * - `$ref` to aliases → inlined as `String`, `Integer`, etc.
 *
 * Delegates to [Annotations] for annotation constants,
 * [NamingConventions] for naming, and [DiscriminatorDetector] for
 * discriminator inference.
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
     * Maps schema paths → sealed interface ClassNames they must implement.
     * Built by [buildSealedInterfaceMap] before generation starts.
     */
    private val sealedInterfaceMap = mutableMapOf<String, MutableList<ClassName>>()
    private val sealedInterfaceByClassName = mutableMapOf<String, MutableList<ClassName>>()

    // ── Orchestration ────────────────────────────────────────────────

    fun generateAll(outputDir: File): List<Path> {
        val reg = typeRegistry ?: error("TypeRegistry required for generateAll")
        val generated = mutableListOf<Path>()
        val packages = mutableSetOf<String>()

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

        for (pkg in packages) {
            generated.add(generatePackageInfo(pkg, outputDir))
        }

        return generated
    }

    /** Legacy single-file API used by MVP test path. */
    fun generate(schemaFile: File, outputDir: File): Path {
        val schema = mapper.readTree(schemaFile)
        val title = schema.required("title").asText()
        val className = NamingConventions.toClassName(title)
        val packageName = "$basePackage.${NamingConventions.jsonPackagePath(schemaFile)}"
        val cn = ClassName.get(packageName, className)
        return generateRecord(schemaFile.name, schema, cn, outputDir).first()
    }

    // ── Pre-pass ─────────────────────────────────────────────────────

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

            val ownProps = processed.path("properties")
            if (ownProps.size() > 0 && !processed.has("discriminator") &&
                !DiscriminatorDetector.hasBranchDiscriminator(oneOf, ::resolveBranch)
            ) continue

            val discriminatorProp = DiscriminatorDetector.findDiscriminatorProperty(
                processed, oneOf, ::resolveBranch
            )

            for ((index, branch) in oneOf.withIndex()) {
                if (branch.has("\$ref")) {
                    val canonicalPath = schemaRegistry?.toCanonicalPath(branch.get("\$ref").asText())
                    if (canonicalPath != null) {
                        sealedInterfaceMap.getOrPut(canonicalPath) { mutableListOf() }.add(className)
                    }
                } else {
                    val (variantSimpleName, _) = deriveVariantName(
                        branch, discriminatorProp, className.simpleName(), index
                    )
                    val variantFqn = "${className.packageName()}.$variantSimpleName"
                    sealedInterfaceByClassName.getOrPut(variantFqn) { mutableListOf() }.add(className)
                }
            }
        }
    }

    // ── Record generation ────────────────────────────────────────────

    /**
     * Generates a record type for an object schema.
     *
     * @param parentInterface when non-null, the record implements this sealed
     *        interface (used for oneOf variant records instead of a separate method)
     */
    private fun generateRecord(
        sourcePath: String,
        schema: JsonNode,
        className: ClassName,
        outputDir: File,
        parentInterface: ClassName? = null
    ): List<Path> {
        val generated = mutableListOf<Path>()
        pendingInlineTypes.clear()

        val mergedProperties = collectProperties(schema, sourcePath)
        val requiredFields = collectRequired(schema)
        val isRequest = className.simpleName().endsWith("Request")
        val (recordComponents, fieldDocs) = buildRecordComponents(
            mergedProperties, requiredFields, className, sourcePath
        )
        val components = recordComponents.toMutableList()

        // additionalProperties → Map field (standalone records only)
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
            .addJavadoc(fieldDocs)
            .addAnnotation(Annotations.generated(sourcePath))
            .recordConstructor(recordCtor)

        // Parent interfaces
        if (parentInterface != null) {
            typeBuilder.addSuperinterface(parentInterface)
        } else {
            val parents = sealedInterfaceMap[sourcePath]
                ?: sealedInterfaceByClassName["${className.packageName()}.${className.simpleName()}"]
            parents?.forEach { typeBuilder.addSuperinterface(it) }
        }

        // Builder for standalone *Request types
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

        generated.add(writeJavaFile(className.packageName(), typeBuilder.build(), outputDir))
        for ((inlinePackage, inlineType) in pendingInlineTypes) {
            generated.add(writeJavaFile(inlinePackage, inlineType, outputDir))
        }
        pendingInlineTypes.clear()

        return generated
    }

    // ── Enum generation ──────────────────────────────────────────────

    private fun generateEnum(
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

        // value field + constructor + @JsonValue getter
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

        // @JsonCreator factory
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

        return listOf(writeJavaFile(className.packageName(), enumBuilder.build(), outputDir))
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

    // ── Sealed interface generation ──────────────────────────────────

    private fun generatePolymorphic(
        sourcePath: String,
        schema: JsonNode,
        className: ClassName,
        outputDir: File
    ): List<Path> {
        val generated = mutableListOf<Path>()
        pendingInlineTypes.clear()

        val ownProps = schema.path("properties")
        val oneOf = schema.path("oneOf")
        if (ownProps.size() > 0 && !schema.has("discriminator") &&
            !DiscriminatorDetector.hasBranchDiscriminator(oneOf, ::resolveBranch)
        ) {
            return generateRecord(sourcePath, schema, className, outputDir)
        }

        if (!oneOf.isArray || oneOf.isEmpty) {
            return generateMarkerInterface(sourcePath, schema, className, outputDir)
        }

        val discriminatorProp = DiscriminatorDetector.findDiscriminatorProperty(
            schema, oneOf, ::resolveBranch
        )
        val variantNames = mutableListOf<Pair<String, ClassName>>()

        for ((index, branch) in oneOf.withIndex()) {
            val isRef = branch.has("\$ref")
            val resolvedBranch = if (isRef) resolveBranch(branch) else branch

            if (isRef) {
                val canonicalPath = schemaRegistry?.toCanonicalPath(branch.get("\$ref").asText())
                val existingClassName = canonicalPath?.let { typeRegistry?.getClassName(it) }

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
                generateRecord(sourcePath, resolvedBranch, variantClassName, outputDir, className)
            )
        }

        val allSamePackage = variantNames.all { it.second.packageName() == className.packageName() }
        val description = schema.path("description").asText("(no description)")
        val interfaceSpec = buildSealedInterface(
            className.simpleName(), sourcePath, description,
            discriminatorProp, variantNames, canBeSealed = allSamePackage
        )
        generated.add(writeJavaFile(className.packageName(), interfaceSpec, outputDir))
        return generated
    }

    /** Generates an inline sealed interface for a property-level oneOf/anyOf. */
    private fun generateInlineUnion(
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
                    schemaRegistry?.resolve(refPath, contextPath)
                } else {
                    schemaRegistry?.resolve(refPath)
                }) ?: branch
            } else branch

            if (isRef) {
                val canonicalPath = schemaRegistry?.toCanonicalPath(branch.get("\$ref").asText())
                val existingClassName = canonicalPath?.let { typeRegistry?.getClassName(it) }

                if (existingClassName != null) {
                    val discValue = discriminatorProp?.let {
                        resolvedBranch.path("properties").path(it).path("const").asText("")
                    } ?: ""
                    variantNames.add(discValue to existingClassName)
                    externalVariants.add(existingClassName)
                    continue
                }
            }

            val mergedBranch = mergeAllOfInBranch(resolvedBranch, branch, contextPath)
            val (variantSimpleName, discriminatorValue) = deriveVariantName(
                mergedBranch, discriminatorProp, interfaceName, index
            )
            val variantClassName = ClassName.get(parentClass.packageName(), variantSimpleName)
            variantNames.add(discriminatorValue to variantClassName)

            // Build inline variant record
            val mergedProperties = collectProperties(mergedBranch, contextPath)
            val requiredFields = collectRequired(mergedBranch)
            val (components, fieldDocs) = buildRecordComponents(
                mergedProperties, requiredFields, variantClassName, contextPath
            )
            val recordCtor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameters(components)
                .build()
            val description = mergedBranch.path("description").asText("(no description)")
            pendingInlineTypes.add(
                parentClass.packageName() to TypeSpec.recordBuilder(variantSimpleName)
                    .addModifiers(Modifier.PUBLIC)
                    .addJavadoc("${NamingConventions.escape(description)}\n\n$fieldDocs")
                    .addAnnotation(Annotations.generated(contextPath))
                    .addSuperinterface(interfaceClassName)
                    .recordConstructor(recordCtor)
                    .build()
            )
        }

        val canBeSealed = variantNames.all {
            it.second.packageName() == interfaceClassName.packageName()
        } && externalVariants.isEmpty()
        pendingInlineTypes.add(
            parentClass.packageName() to buildSealedInterface(
                interfaceName, contextPath, "(no description)",
                discriminatorProp, variantNames, canBeSealed
            )
        )
        return interfaceClassName
    }

    // ── Shared builders ──────────────────────────────────────────────

    /**
     * Builds record components (ParameterSpecs) from schema properties.
     * Adds `@JsonProperty`, `@Nullable`, and `@XEntity` as appropriate.
     *
     * @return pair of (component list, Javadoc `@param` tags string)
     */
    private fun buildRecordComponents(
        properties: Map<String, JsonNode>,
        requiredFields: Set<String>,
        parentClass: ClassName,
        contextPath: String
    ): Pair<List<ParameterSpec>, String> {
        val components = mutableListOf<ParameterSpec>()
        val fieldDocs = StringBuilder()

        for ((jsonName, propSchema) in properties) {
            val javaName = NamingConventions.toCamelCase(jsonName)
            val type = resolvePropertyType(propSchema, parentClass, jsonName, contextPath)
            val required = jsonName in requiredFields

            val paramBuilder = ParameterSpec.builder(type, javaName)
            if (!required) {
                paramBuilder.addAnnotation(Nullable::class.java)
            }
            paramBuilder.addAnnotation(
                AnnotationSpec.builder(Annotations.JSON_PROPERTY)
                    .addMember("value", "\$S", jsonName).build()
            )

            val xEntity = propSchema.path("x-entity").asText(null)
            if (xEntity != null) {
                paramBuilder.addAnnotation(
                    AnnotationSpec.builder(Annotations.xEntity(basePackage))
                        .addMember("value", "\$S", xEntity).build()
                )
            }

            components.add(paramBuilder.build())
            val descr = propSchema.path("description").asText("")
            if (descr.isNotBlank()) {
                fieldDocs.append("@param $javaName ${NamingConventions.escape(descr)}\n")
            }
        }

        return components to fieldDocs.toString()
    }

    /**
     * Builds a sealed (or non-sealed) interface TypeSpec with Jackson
     * polymorphic annotations (`@JsonTypeInfo` + `@JsonSubTypes`).
     */
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

        if (canBeSealed) {
            builder.addModifiers(Modifier.SEALED)
        }

        addPolymorphicAnnotations(builder, discriminatorProp, variantNames)

        if (canBeSealed) {
            for ((_, variantClass) in variantNames) {
                builder.addPermittedSubclass(variantClass)
            }
        }

        return builder.build()
    }

    /** Adds `@JsonTypeInfo` and `@JsonSubTypes` to a type builder. */
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

    private fun buildBuilder(
        recordClassName: String,
        components: List<ParameterSpec>
    ): TypeSpec {
        val builderType = ClassName.bestGuess("Builder")
        val recordType = ClassName.bestGuess(recordClassName)

        val builder = TypeSpec.classBuilder("Builder")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addJavadoc(
                "Fluent builder for {@link \$T}. Per RFC §Type generation: " +
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

    // ── Schema property helpers ──────────────────────────────────────

    private fun collectProperties(schema: JsonNode, contextPath: String): Map<String, JsonNode> {
        val properties = LinkedHashMap<String, JsonNode>()
        schema.path("properties").fields().forEach { (name, prop) ->
            properties[name] = prop
        }
        val allOf = schema.path("allOf")
        if (allOf.isArray) {
            for (branch in allOf) {
                val resolved = if (branch.has("\$ref")) {
                    schemaRegistry?.resolve(branch.path("\$ref").asText(), contextPath)
                } else branch
                resolved?.path("properties")?.fields()?.forEach { (name, prop) ->
                    if (name !in properties) {
                        properties[name] = prop
                    }
                }
            }
        }
        return properties
    }

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
     * Resolves the Java type for a property schema. Handles `$ref`,
     * primitives, arrays, inline objects, inline oneOf/anyOf unions,
     * and const-only properties.
     */
    private fun resolvePropertyType(
        propSchema: JsonNode,
        parentClass: ClassName,
        propertyName: String,
        contextPath: String
    ): TypeName {
        val ref = propSchema.path("\$ref").asText(null)
        if (ref != null) {
            return typeRegistry?.resolveRefType(ref)?.className
                ?: ClassName.get("java.lang", "Object")
        }

        val type = propSchema.path("type").asText("")

        if (type == "string" && propSchema.has("enum")) {
            return ClassName.get("java.lang", "String")
        }

        // Property-level oneOf/anyOf with structural branches → inline sealed interface
        val unionBranches = listOf("oneOf", "anyOf")
            .map { propSchema.path(it) }
            .firstOrNull { it.isArray && !it.isEmpty }
        if (unionBranches != null && hasStructuralBranches(unionBranches)) {
            return generateInlineUnion(unionBranches, parentClass, propertyName, contextPath)
        }

        if (type == "object" && propSchema.has("properties") && propSchema.path("properties").size() > 0) {
            return generateInlineRecord(propSchema, parentClass, propertyName, contextPath)
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
                resolvePropertyType(itemsSchema, parentClass, propertyName + "Item", contextPath)
            }
            return ParameterizedTypeName.get(ClassName.get("java.util", "List"), itemType)
        }

        return when (type) {
            "string" -> ClassName.get("java.lang", "String")
            "integer" -> ClassName.get("java.lang", "Integer")
            "boolean" -> ClassName.get("java.lang", "Boolean")
            "number" -> ClassName.get("java.lang", "Double")
            else -> {
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

    // ── Discriminator helpers ────────────────────────────────────────

    /** Resolves a branch's `$ref` if present; returns unchanged otherwise. */
    private fun resolveBranch(branch: JsonNode): JsonNode {
        if (branch.has("\$ref") && schemaRegistry != null) {
            return schemaRegistry.resolve(branch.get("\$ref").asText()) ?: branch
        }
        return branch
    }

    private fun findBranchDiscriminatorProp(branches: JsonNode, contextPath: String): String? =
        DiscriminatorDetector.pickBestDiscriminator(branches) { branch ->
            mergeAllOfInBranch(resolveBranch(branch), branch, contextPath)
        }

    private fun deriveVariantName(
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

    // ── Inline type helpers ──────────────────────────────────────────

    private fun generateInlineRecord(
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

        val (components, _) = buildRecordComponents(inlineProps, inlineRequired, inlineCn, contextPath)

        val ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC)
            .addParameters(components)
            .build()

        pendingInlineTypes.add(
            parentClass.packageName() to TypeSpec.recordBuilder(inlineClassName)
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("${NamingConventions.escape(propSchema.path("description").asText("(no description)"))}\n")
                .addAnnotation(Annotations.generated(contextPath))
                .recordConstructor(ctor)
                .build()
        )
        return inlineCn
    }

    private fun generateMarkerInterface(
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
        return listOf(writeJavaFile(className.packageName(), typeBuilder.build(), outputDir))
    }

    private fun mergeAllOfInBranch(
        resolvedBranch: JsonNode,
        originalBranch: JsonNode,
        contextPath: String
    ): JsonNode {
        val allOf = resolvedBranch.path("allOf")
        if (!allOf.isArray || allOf.isEmpty) return resolvedBranch

        val merged = mapper.createObjectNode()
        val mergedProps = mapper.createObjectNode()
        resolvedBranch.path("properties").fields().forEach { (name, prop) ->
            mergedProps.set<JsonNode>(name, prop)
        }

        val mergedRequired = mapper.createArrayNode()
        resolvedBranch.path("required").forEach { mergedRequired.add(it) }

        for (allOfBranch in allOf) {
            val resolved = if (allOfBranch.has("\$ref")) {
                schemaRegistry?.resolve(allOfBranch.get("\$ref").asText(), contextPath)
            } else allOfBranch
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

        resolvedBranch.path("title").asText(null)?.let { merged.put("title", it) }
        resolvedBranch.path("description").asText(null)?.let { merged.put("description", it) }
        if (resolvedBranch.has("additionalProperties")) {
            merged.set<JsonNode>("additionalProperties", resolvedBranch.path("additionalProperties"))
        }
        return merged
    }

    private fun hasStructuralBranches(branches: JsonNode): Boolean {
        val validationOnly = setOf("required", "not", "if", "then", "else")
        for (branch in branches) {
            val keys = branch.fieldNames().asSequence().toSet()
            if (keys.any { it !in validationOnly }) return true
        }
        return false
    }

    // ── File I/O ─────────────────────────────────────────────────────

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
}
