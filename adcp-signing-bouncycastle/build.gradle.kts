plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — Bouncy Castle FIPS signing provider (optional)"

dependencies {
    api(project(":adcp"))
    // Bouncy Castle FIPS — optional, for FIPS environments only.
    // Uncomment when implementing the provider:
    // implementation(libs.bouncycastle.fips)
}