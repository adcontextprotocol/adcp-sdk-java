// Maven Central publishing convention (per D6 / D20).
//
// Uses the vanniktech maven-publish plugin (≥ 0.30) which targets the
// Central Portal API (central.sonatype.com) — the legacy OSSRH s01
// staging endpoint is being sunset and does not accept new namespace
// registrations. Confirmed by bokelley review on PR #3.
//
// Required Gradle project properties for a real publish (injected as
// ORG_GRADLE_PROJECT_* env vars by the release workflow — never checked in):
//
//   mavenCentralUsername          – Sonatype Central Portal token user
//   mavenCentralPassword          – Sonatype Central Portal token password
//   signingInMemoryKey            – ASCII-armored GPG private key (no keyring needed)
//   signingInMemoryKeyId          – GPG key ID (last 8 hex chars)
//   signingInMemoryKeyPassword    – GPG key passphrase
//
// The plugin auto-wires maven-publish and in-memory signing; no separate
// `signing { }` block required. Sources + javadoc JARs are already
// registered in adcp.java-base-conventions via withSourcesJar() /
// withJavadocJar() — vanniktech detects and reuses them.

import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    pom {
        name = provider { project.name }
        description = provider { project.description ?: "AdCP Java SDK – ${project.name}" }
        url = "https://github.com/adcontextprotocol/adcp-sdk-java"
        inceptionYear = "2026"

        licenses {
            license {
                name = "Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }

        developers {
            developer {
                id = "adcontextprotocol"
                name = "AdCP Working Group"
                url = "https://adcontextprotocol.org"
            }
        }

        scm {
            connection = "scm:git:https://github.com/adcontextprotocol/adcp-sdk-java.git"
            developerConnection = "scm:git:git@github.com:adcontextprotocol/adcp-sdk-java.git"
            url = "https://github.com/adcontextprotocol/adcp-sdk-java"
        }
    }
}
