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
}
