// adcp-testing — storyboard runner, conformance harness, mock-server forwarding
// adapter, signing test fixtures, personas. Per RFC §Reference: covers TS
// exports `/testing`, `/testing/personas`, `/conformance`, `/compliance`,
// `/compliance-fixtures`, `/substitution`, `/signing/testing`, `/mock-server`.
// Mock-server forwarding contract: storyboards certify against the shared
// @adcp/sdk/mock-server (D5/D8), not an in-process Java mock.

plugins {
    id("adcp.java-library-conventions")
}

description = "AdCP Java SDK — storyboard runner, conformance harness, JUnit 5 fixtures"

dependencies {
    api(project(":adcp"))
    // JUnit Jupiter is part of the public surface — adopters write tests against
    // AdcpAgentExtension on the api scope.
    api(libs.junit.jupiter.api)
}
