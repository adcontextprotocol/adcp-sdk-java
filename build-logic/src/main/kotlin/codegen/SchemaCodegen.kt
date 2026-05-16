package codegen

import com.fasterxml.jackson.databind.ObjectMapper
import com.palantir.javapoet.ClassName
import java.io.File
import java.nio.file.Path

/**
 * Thin orchestrator that wires focused generators together and drives
 * the codegen pipeline. All generation logic lives in dedicated classes:
 *
 * - [RecordGenerator] — Java records + builders
 * - [EnumGenerator] — Java enums with `@JsonValue`/`@JsonCreator`
 * - [SealedInterfaceGenerator] — sealed interfaces for `oneOf` unions
 * - [PropertyResolver] — JSON Schema → Java type resolution
 * - [SchemaUtils] — schema walking (collectProperties, mergeAllOf, etc.)
 *
 * Shared state (sealed-interface index, inline-type collector, file I/O)
 * lives in [CodegenContext].
 */
class SchemaCodegen(
    private val basePackage: String,
    private val schemaRegistry: SchemaRegistry? = null,
    private val typeRegistry: TypeRegistry? = null,
    private val preprocessor: SchemaPreprocessor? = null
) {

    private val ctx = CodegenContext(basePackage, schemaRegistry, typeRegistry, preprocessor)
    private val resolver = PropertyResolver(ctx)
    private val recordGen = RecordGenerator(ctx, resolver)
    private val enumGen = EnumGenerator(ctx)
    private val sealedGen = SealedInterfaceGenerator(ctx, resolver, recordGen)

    init {
        resolver.onInlineRecord = recordGen::generateInline
        resolver.onInlineUnion = sealedGen::generateInline
    }

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
                    TypeRegistry.TypeCategory.COMPOSED -> recordGen.generate(path, processed, className, outputDir)
                    TypeRegistry.TypeCategory.ENUM -> enumGen.generate(processed, className, outputDir)
                    TypeRegistry.TypeCategory.POLYMORPHIC -> sealedGen.generate(path, processed, className, outputDir)
                    else -> emptyList()
                }
                generated.addAll(files)
                packages.add(className.packageName())
            } catch (e: Exception) {
                System.err.println("WARN: Failed to generate $path (${className.simpleName()}): ${e.message}")
            }
        }

        for (pkg in packages) {
            generated.add(ctx.writePackageInfo(pkg, outputDir))
        }

        return generated
    }

    /** Legacy single-file API used by MVP test path. */
    fun generate(schemaFile: File, outputDir: File): Path {
        val mapper = ObjectMapper()
        val schema = mapper.readTree(schemaFile)
        val title = schema.required("title").asText()
        val className = NamingConventions.toClassName(title)
        val packageName = "$basePackage.${NamingConventions.jsonPackagePath(schemaFile)}"
        val cn = ClassName.get(packageName, className)
        return recordGen.generate(schemaFile.name, schema, cn, outputDir).first()
    }

    // ── Pre-pass: sealed interface membership index ──────────────────

    private fun buildSealedInterfaceMap(reg: TypeRegistry) {
        ctx.sealedIndex.clear()

        for ((path, className) in reg.allGeneratableEntries()) {
            val category = reg.getCategory(path) ?: continue
            if (category != TypeRegistry.TypeCategory.POLYMORPHIC) continue

            val schema = schemaRegistry?.get(path) ?: continue
            val processed = preprocessor?.preprocess(schema) ?: schema
            val oneOf = processed.path("oneOf")
            if (!oneOf.isArray || oneOf.isEmpty) continue

            val ownProps = processed.path("properties")
            if (ownProps.size() > 0 && !processed.has("discriminator") &&
                !DiscriminatorDetector.hasBranchDiscriminator(oneOf, ctx::resolveBranch)
            ) continue

            val discriminatorProp = DiscriminatorDetector.findDiscriminatorProperty(
                processed, oneOf, ctx::resolveBranch
            )

            for ((index, branch) in oneOf.withIndex()) {
                if (branch.has("\$ref")) {
                    val canonicalPath = schemaRegistry?.toCanonicalPath(branch.get("\$ref").asText())
                    if (canonicalPath != null) {
                        ctx.sealedIndex.register(canonicalPath, className)
                    }
                } else {
                    val (variantSimpleName, _) = SealedInterfaceGenerator.deriveVariantName(
                        branch, discriminatorProp, className.simpleName(), index
                    )
                    val variantFqn = "${className.packageName()}.$variantSimpleName"
                    ctx.sealedIndex.registerByName(variantFqn, className)
                }
            }
        }
    }
}
