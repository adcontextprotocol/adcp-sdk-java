// Schema codegen wiring (per Track 2). Generates Java records, enums, and
// builders from ALL AdCP JSON Schemas in the protocol tarball. Uses
// SchemaRegistry for $ref resolution, TypeRegistry for type mapping,
// and SchemaPreprocessor for schema normalization.
//
// Two generation modes:
// 1. Full mode (default): generates all schemas via SchemaRegistry
// 2. Legacy mode: generates specific files by path (MVP compat)
//
// Two schema versions are generated:
// - Primary (v3.x): org.adcontextprotocol.adcp.generated.*
// - Co-existence (v2.5): org.adcontextprotocol.adcp.generated.v2_5.*

import codegen.SchemaCodegen
import codegen.SchemaPreprocessor
import codegen.SchemaRegistry
import codegen.TypeRegistry

plugins {
    id("adcp.java-library-conventions")
    id("adcp.schema-bundle-conventions")
}

abstract class GenerateSchemas : DefaultTask() {

    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val versionNamespace: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.InputDirectory
    abstract val schemaRoot: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val basePackage: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val schemaFiles: org.gradle.api.provider.ListProperty<String>

    @get:org.gradle.api.tasks.Input
    abstract val fullMode: org.gradle.api.provider.Property<Boolean>

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val root = schemaRoot.get().asFile

        if (fullMode.getOrElse(true) && root.exists()) {
            // Full mode: generate all schemas via registry
            val ns = versionNamespace.orNull
            val registry = SchemaRegistry(root)
            val typeRegistry = TypeRegistry(basePackage.get(), registry, ns)
            val preprocessor = SchemaPreprocessor()
            val codegen = SchemaCodegen(
                basePackage = basePackage.get(),
                schemaRegistry = registry,
                typeRegistry = typeRegistry,
                preprocessor = preprocessor,
                versionNamespace = ns
            )

            val generated = codegen.generateAll(out)
            logger.lifecycle("Generated ${generated.size} Java files from ${registry.allGeneratableSchemas().size} schemas")
        } else {
            // Legacy mode: generate specific files (MVP compat)
            val codegen = SchemaCodegen(basePackage.get())
            schemaFiles.get().forEach { relativePath ->
                val schemaFile = root.resolve(relativePath)
                check(schemaFile.exists()) {
                    "Schema file not found: $schemaFile (relativePath=$relativePath, root=$root)"
                }
                val generated = codegen.generate(schemaFile, out)
                logger.lifecycle("Generated: ${out.toPath().relativize(generated)}")
            }
        }
    }
}

val generateTask = tasks.register<GenerateSchemas>("generateSchemas") {
    description = "Generate Java records and enums from AdCP JSON Schemas."
    group = "build setup"
    dependsOn("fetchSchemaBundle")

    val adcpVersion = project.rootProject.file("ADCP_VERSION").readText().trim()
    schemaRoot.set(
        project.layout.buildDirectory.dir("schemas/adcp-$adcpVersion/schemas")
    )
    basePackage.set("org.adcontextprotocol.adcp")
    schemaFiles.set(
        listOf(
            "core/pagination-request.json",
            "core/pagination-response.json"
        )
    )
    fullMode.set(true)
    outputDir.set(project.layout.buildDirectory.dir("generated/sources/codegen/main/java"))
}

// Generate frozen v2.5.1 types under the v2_5 co-existence namespace.
// These land in org.adcontextprotocol.adcp.generated.v2_5.* so both
// versions can coexist on the same classpath (per ROADMAP adcp-v2-5).
val generateV25Task = tasks.register<GenerateSchemas>("generateSchemasV25") {
    description = "Generate v2.5.1 co-existence types under the v2_5 namespace."
    group = "build setup"
    dependsOn("fetchSchemaBundleV25")

    schemaRoot.set(project.layout.buildDirectory.dir("schemas-v25"))
    basePackage.set("org.adcontextprotocol.adcp")
    versionNamespace.set("v2_5")
    schemaFiles.set(listOf())
    fullMode.set(true)
    outputDir.set(project.layout.buildDirectory.dir("generated/sources/codegen-v25/main/java"))
}

// Wire both generated source trees into the main source set so compileJava
// picks them up — primary (v3.x) and co-existence (v2.5) types compile together.
extensions.configure<SourceSetContainer>("sourceSets") {
    named("main") {
        java.srcDir(generateTask.map { it.outputDir })
        java.srcDir(generateV25Task.map { it.outputDir })
    }
}

tasks.named("compileJava") {
    dependsOn(generateTask)
    dependsOn(generateV25Task)
}

// Copy schema files into the JAR so they're available on the classpath at runtime.
// Used by AdcpSchemaValidator and SchemaBundle.
val copySchemaResources = tasks.register<Copy>("copySchemaResources") {
    description = "Copies AdCP schemas into build resources for runtime access."
    group = "build setup"
    dependsOn("fetchSchemaBundle")

    val adcpVersion = project.rootProject.file("ADCP_VERSION").readText().trim()
    from(project.layout.buildDirectory.dir("schemas/adcp-$adcpVersion/schemas"))
    into(project.layout.buildDirectory.dir("resources/main/schemas/$adcpVersion"))
}

// Copy v2.5.1 schemas into the JAR for runtime access alongside v3.x schemas.
val copySchemaResourcesV25 = tasks.register<Copy>("copySchemaResourcesV25") {
    description = "Copies v2.5.1 AdCP schemas into build resources for runtime access."
    group = "build setup"
    dependsOn("fetchSchemaBundleV25")

    from(project.layout.buildDirectory.dir("schemas-v25"))
    into(project.layout.buildDirectory.dir("resources/main/schemas/2.5.1"))
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(copySchemaResources)
    dependsOn(copySchemaResourcesV25)
}

// Jackson annotations referenced by the generated classes need to be on
// the compile classpath of the consuming module.
val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()
dependencies {
    "implementation"(libs.jackson.databind)
}
