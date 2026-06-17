plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — GCP KMS signing provider"

dependencies {
    api(project(":adcp"))
    // GCP KMS SDK — lazy-init, not on the boot critical path.
    // Uncomment when implementing the provider:
    // implementation(libs.gcp.kms)
}