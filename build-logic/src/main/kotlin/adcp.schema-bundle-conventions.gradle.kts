// Schema-bundle fetcher (per D4 — Sigstore-signed tarball at
// https://adcontextprotocol.org/protocol/{version}.tgz with .sig + .crt
// sidecars). Applied to the adcp module; the codegen track depends on
// this output landing under build/schemas/.
//
// The verification follows
// https://github.com/adcontextprotocol/adcp/blob/main/docs/reference/verifying-protocol-tarballs.mdx
// — cosign verify-blob against the AdCP release workflow identity. The
// SHA-256 sidecar is also fetched for in-transit integrity.
//
// `cosign` is expected on PATH. CI installs it via
// sigstore/cosign-installer@v3.

import java.security.MessageDigest

abstract class FetchSchemaBundle : DefaultTask() {

    @get:org.gradle.api.tasks.Input
    abstract val adcpVersion: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val baseUrl: org.gradle.api.provider.Property<String>

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty

    @get:org.gradle.api.tasks.Input
    @get:org.gradle.api.tasks.Optional
    abstract val skipCosign: org.gradle.api.provider.Property<Boolean>

    @org.gradle.api.tasks.TaskAction
    fun fetch() {
        val version = adcpVersion.get()
        val base = baseUrl.getOrElse("https://adcontextprotocol.org/protocol")
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()

        val tarball = java.io.File(out, "$version.tgz")
        val sha256 = java.io.File(out, "$version.tgz.sha256")
        val sig = java.io.File(out, "$version.tgz.sig")
        val crt = java.io.File(out, "$version.tgz.crt")

        download("$base/$version.tgz", tarball)
        download("$base/$version.tgz.sha256", sha256)
        download("$base/$version.tgz.sig", sig)
        download("$base/$version.tgz.crt", crt)

        verifySha256(tarball, sha256)
        if (skipCosign.getOrElse(false)) {
            logger.warn(
                "Skipping cosign verification of $version.tgz (skipCosign=true). " +
                "Never skip on release builds — D4 requires Sigstore verification."
            )
        } else {
            verifyCosign(tarball, sig, crt)
        }

        extract(tarball, out)
        logger.lifecycle("Schema bundle $version extracted to ${out.absolutePath}")
    }

    private fun download(url: String, target: java.io.File) {
        logger.info("Downloading $url")
        java.net.URI.create(url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun verifySha256(tarball: java.io.File, shaFile: java.io.File) {
        // Format: "<hex>  <filename>" on a single line.
        val expected = shaFile.readText().trim().substringBefore(' ').lowercase()
        val actual = MessageDigest.getInstance("SHA-256").let { md ->
            tarball.inputStream().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        check(expected == actual) {
            "SHA-256 mismatch on $tarball: expected $expected, got $actual"
        }
        logger.info("SHA-256 verified: $actual")
    }

    private fun verifyCosign(tarball: java.io.File, sig: java.io.File, crt: java.io.File) {
        // Identity regex from the spec doc:
        // ^https://github.com/adcontextprotocol/adcp/.github/workflows/release.yml@refs/(heads|tags)/.*$
        val identityRegex =
            "^https://github\\.com/adcontextprotocol/adcp/\\.github/workflows/release\\.yml@refs/(heads|tags)/.*$"

        val process = ProcessBuilder(
            "cosign", "verify-blob",
            "--signature", sig.absolutePath,
            "--certificate", crt.absolutePath,
            "--certificate-identity-regexp", identityRegex,
            "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com",
            tarball.absolutePath
        )
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "cosign verify-blob failed (exit $exitCode):\n$output"
        }
        logger.info("Sigstore verification OK")
    }

    private fun extract(tarball: java.io.File, dest: java.io.File) {
        // Plain JDK extraction — avoid pulling in a tar library when the
        // tooling is one external invocation. Mirrors how the TS SDK's
        // download.sh uses tar.
        val process = ProcessBuilder("tar", "xzf", tarball.absolutePath, "-C", dest.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "tar extraction failed (exit $exitCode):\n$output"
        }
    }
}

// Register a top-level `fetchSchemaBundle` task on every module that applies
// this convention. Today the adcp module is the consumer; if other modules
// need raw schema access later they can depend on adcp's output directory.
tasks.register<FetchSchemaBundle>("fetchSchemaBundle") {
    description = "Downloads + Sigstore-verifies the AdCP protocol tarball (per D4)."
    group = "build setup"

    // Default to whatever is pinned in ADCP_VERSION (root-level file, same
    // shape the TS SDK uses). Override per-call with -PadcpVersion=X.Y.Z.
    val pinned = project.findProperty("adcpVersion") as String?
        ?: project.rootProject.file("ADCP_VERSION").takeIf { it.exists() }?.readText()?.trim()
        ?: error(
            "No AdCP version pinned. Set -PadcpVersion=X.Y.Z or create " +
            "ADCP_VERSION at the repo root."
        )
    adcpVersion = pinned
    outputDir = project.layout.buildDirectory.dir("schemas")

    // Override with -PskipCosign=true only for offline development. Never
    // for release builds.
    skipCosign = (project.findProperty("skipCosign") as String?)?.toBoolean() ?: false
}
