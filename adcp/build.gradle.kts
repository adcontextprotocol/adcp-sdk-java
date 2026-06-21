// adcp — caller, generated types, version co-existence, schema bundle.
// The main artifact. Per RFC §Reference: covers the TS exports `.`, `/client`,
// `/types`, `/types/v2-5`, `/auth`, `/advanced`, `/schemas`.

plugins {
    id("adcp.schema-codegen-conventions")
}

description = "AdCP Java SDK — caller, types, schema bundle"

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jsr310)
    api(libs.slf4j.api)
    api(libs.jspecify)
    implementation(libs.json.schema.validator)
    // MCP SDK client transport — needed for McpClient, StreamableHTTP, SSE fallback.
    // Same artifacts as adcp-server; here they provide the caller/client side.
    // Exclude json-schema-validator from mcp-json-jackson2 to keep our pinned 1.5.x.
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson2) {
        exclude(group = "com.networknt", module = "json-schema-validator")
    }
    api(libs.a2a.sdk.client)
    // Explicit deps for A2A classes used directly — not transitive reliance on a2a-sdk-client
    implementation(libs.a2a.sdk.client.transport.jsonrpc) // JSONRPCTransport, JSONRPCTransportConfigBuilder
    implementation(libs.a2a.sdk.http.client)              // JdkA2AHttpClient
}

// -- Build-time SDK version constant ----------------------------------------
// Reads ADCP_VERSION (e.g. "3.0.11") and generates AdcpSdkVersion.java with
// the major and release-precision (major.minor) constants. This lets callers
// do cross-major validation at config time without hardcoding a version number.
// Output lands in build/generated/ and is NOT checked in.

val generateSdkVersion = tasks.register("generateSdkVersion") {
    val versionFile = rootProject.file("ADCP_VERSION")
    inputs.file(versionFile)
    val outputDir = layout.buildDirectory.dir("generated/sources/sdk-version/main/java")
    outputs.dir(outputDir)

    doLast {
        val raw = versionFile.readText().trim()
        val parts = raw.split(".")
        require(parts.size >= 2) { "ADCP_VERSION must be in major.minor.patch format: $raw" }
        val major = parts[0].toInt()
        val release = "${parts[0]}.${parts[1]}"   // release-precision, e.g. "3.0"

        val pkg = "org.adcontextprotocol.adcp"
        val dir = outputDir.get().asFile.resolve(pkg.replace('.', '/'))
        dir.mkdirs()
        dir.resolve("AdcpSdkVersion.java").writeText(
            """
            package $pkg;
            
            /**
             * Build-time AdCP SDK version constants — generated from {@code ADCP_VERSION}.
             * Do not edit manually; update {@code ADCP_VERSION} at the repo root instead.
             *
             * <p>Used for cross-major validation: if a caller pins
             * {@code adcpVersion("X.Y")} and {@code X != SDK_MAJOR_VERSION},
             * a {@link org.adcontextprotocol.adcp.error.ConfigurationError} is thrown
             * before any network request is made.
             */
            public final class AdcpSdkVersion {

                private AdcpSdkVersion() {}

                /** Major protocol version this SDK was built for (e.g. {@code 3}). */
                public static final int SDK_MAJOR_VERSION = $major;

                /**
                 * Release-precision protocol version this SDK was built for
                 * (e.g. {@code "3.0"}).
                 */
                public static final String SDK_RELEASE_VERSION = "$release";
            }
            """.trimIndent()
        )
    }
}

sourceSets.named("main") {
    java.srcDir(generateSdkVersion.map { it.outputs.files.singleFile })
}

tasks.named("compileJava") {
    dependsOn(generateSdkVersion)
}
