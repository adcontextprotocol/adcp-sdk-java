// adcp — caller, generated types, version co-existence, schema bundle.
// The main artifact. Per RFC §Reference: covers the TS exports `.`, `/client`,
// `/types`, `/types/v2-5`, `/auth`, `/advanced`, `/schemas`.

plugins {
    id("adcp.java-library-conventions")
    id("adcp.schema-bundle-conventions")
}

description = "AdCP Java SDK — caller, types, schema bundle"

dependencies {
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jsr310)
    api(libs.slf4j.api)
    api(libs.jspecify)
    implementation(libs.json.schema.validator)
}
