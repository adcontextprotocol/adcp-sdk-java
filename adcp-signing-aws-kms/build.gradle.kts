// adcp-signing-aws-kms — AWS KMS signing provider for AdCP.
// Lazy-init; only touches KMS on first sign/verify call.
// Pre-deploy probe is a separate CLI command (adcp-cli), not boot-critical.
plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — AWS KMS signing provider"

dependencies {
    api(project(":adcp"))
    // AWS KMS SDK — lazy-init, not on the boot critical path.
    // Uncomment when implementing the provider:
    // implementation(libs.aws.kms)
}