// Schema codegen wiring (per Track 2 / harness MVP). Generates Java
// records from the extracted JSON Schemas. Today the MVP runs against
// one pair (PaginationRequest / PaginationResponse from
// schemas/core/) to prove the architecture; full coverage lands on the
// codegen track.

import codegen.SchemaCodegen

plugins {
    id("adcp.java-library-conventions")
    id("adcp.schema-bundle-conventions")
}

abstract class GenerateSchemas : DefaultTask() {

    @get:org.gradle.api.tasks.InputDirectory
    abstract val schemaRoot: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.Input
    abstract val basePackage: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    abstract val schemaFiles: org.gradle.api.provider.ListProperty<String>

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val codegen = SchemaCodegen(basePackage.get())
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val root = schemaRoot.get().asFile
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

val generateTask = tasks.register<GenerateSchemas>("generateSchemas") {
    description = "Generate Java records from a subset of AdCP JSON Schemas (MVP)."
    group = "build setup"
    dependsOn("fetchSchemaBundle")

    val adcpVersion = project.rootProject.file("ADCP_VERSION").readText().trim()
    schemaRoot.set(
        project.layout.buildDirectory.dir("schemas/adcp-$adcpVersion/schemas")
    )
    basePackage.set("org.adcontextprotocol.adcp.generated")
    schemaFiles.set(
        listOf(
            "core/pagination-request.json",
            "core/pagination-response.json"
        )
    )
    outputDir.set(project.layout.buildDirectory.dir("generated/sources/codegen/main/java"))
}

// Wire generated sources into the main source set so compileJava picks
// them up.
extensions.configure<SourceSetContainer>("sourceSets") {
    named("main") {
        java.srcDir(generateTask.map { it.outputDir })
    }
}

tasks.named("compileJava") {
    dependsOn(generateTask)
}

// Jackson annotations referenced by the generated classes need to be on
// the compile classpath of the consuming module.
val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()
dependencies {
    "implementation"(libs.jackson.databind)
}
