// Maven Central publishing convention (per D6 / D20).
//
// Scaffolds `maven-publish` + `signing` for every library module. The first
// actual publish is at v0.3 (D6). Until then, artifacts are local / SNAPSHOT.
//
// Required Gradle properties for a real publish (set via env or
// ~/.gradle/gradle.properties — never checked in):
//
//   ossrhUsername        – Sonatype OSSRH token user
//   ossrhPassword        – Sonatype OSSRH token password
//   signing.keyId        – GPG key ID (last 8 hex chars)
//   signing.password     – GPG key passphrase
//   signing.secretKeyRingFile – path to GPG keyring (or use in-memory key)
//
// In CI the release workflow injects these from GitHub secrets.

plugins {
    `maven-publish`
    signing
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            // Coordinates: group comes from allprojects{} in root build,
            // artifactId defaults to project.name (matches D3 artifact names).
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
    }

    repositories {
        maven {
            name = "ossrh"
            val releasesUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
            val snapshotsUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
            url = uri(
                if (project.version.toString().endsWith("-SNAPSHOT")) snapshotsUrl
                else releasesUrl
            )
            credentials {
                username = project.findProperty("ossrhUsername") as String? ?: ""
                password = project.findProperty("ossrhPassword") as String? ?: ""
            }
        }
    }
}

// Sign only when credentials are available (CI / release builds).
signing {
    // Use GPG agent or in-memory key when available; skip otherwise.
    isRequired = project.hasProperty("signing.keyId")
    sign(publishing.publications["mavenJava"])
}

// Javadoc + sources JARs are already wired in adcp.java-base-conventions
// via java.withJavadocJar() and java.withSourcesJar(). No duplication here.
