package org.adcontextprotocol.adcp.cli;

import org.adcontextprotocol.adcp.signing.AdcpUse;
import org.adcontextprotocol.adcp.signing.SigningException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Pre-deploy KMS probe CLI command.
 *
 * <p>Verifies KMS connectivity and key usability for each configured KMS key.
 * Per the ROADMAP: "separate CLI command, not part of boot critical path."
 *
 * <p>Supports AWS and GCP KMS providers. Outputs structured JSON results
 * with exit code 0 (all pass) or 1 (any fail).
 */
public final class SigningProbeCommand implements Callable<Integer> {

    private final List<KeyProbe> probes = new ArrayList<>();

    public SigningProbeCommand addAwsKey(AdcpUse use, String keyArn, String region) {
        probes.add(new AwsKeyProbe(use, keyArn, region));
        return this;
    }

    public SigningProbeCommand addGcpKey(AdcpUse use, String keyVersionPath, String credentialsPath) {
        probes.add(new GcpKeyProbe(use, keyVersionPath, credentialsPath));
        return this;
    }

    @Override
    public Integer call() {
        List<ProbeResult> results = new ArrayList<>();
        boolean allPassed = true;

        for (KeyProbe probe : probes) {
            ProbeResult result = probe.run();
            results.add(result);
            if (!result.passed()) {
                allPassed = false;
            }
        }

        System.out.println(formatResults(results));
        return allPassed ? 0 : 1;
    }

    String formatResults(List<ProbeResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"probe_results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            ProbeResult r = results.get(i);
            if (i > 0) sb.append(",\n");
            sb.append("    {\n");
            sb.append("      \"provider\": \"").append(escapeJson(r.provider())).append("\",\n");
            sb.append("      \"use\": \"").append(escapeJson(r.use().wireName())).append("\",\n");
            sb.append("      \"key_id\": \"").append(escapeJson(r.keyId())).append("\",\n");
            sb.append("      \"connectivity\": ").append(r.connectivityOk() ? "\"ok\"" : "\"failed: " + escapeJson(r.connectivityError()) + "\"").append(",\n");
            sb.append("      \"signing\": ").append(r.signingOk() ? "\"ok\"" : "\"failed: " + escapeJson(r.signingError()) + "\"").append(",\n");
            sb.append("      \"purpose\": ").append(r.purposeOk() ? "\"ok\"" : "\"failed: " + escapeJson(r.purposeError()) + "\"").append(",\n");
            sb.append("      \"passed\": ").append(r.passed());
            sb.append("\n    }");
        }
        sb.append("\n  ]\n}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    record ProbeResult(
            String provider,
            AdcpUse use,
            String keyId,
            boolean connectivityOk,
            String connectivityError,
            boolean signingOk,
            String signingError,
            boolean purposeOk,
            String purposeError,
            boolean passed
    ) {}

    interface KeyProbe {
        ProbeResult run();
    }

    static final class AwsKeyProbe implements KeyProbe {
        private final AdcpUse use;
        private final String keyArn;
        private final String region;

        AwsKeyProbe(AdcpUse use, String keyArn, String region) {
            this.use = use;
            this.keyArn = keyArn;
            this.region = region;
        }

        @Override
        public ProbeResult run() {
            boolean connectivityOk = false;
            String connectivityError = null;
            boolean signingOk = false;
            String signingError = null;
            boolean purposeOk = false;
            String purposeError = null;

            try {
                Class<?> providerClass = Class.forName(
                        "org.adcontextprotocol.adcp.signing.aws.AwsKmsSigningProvider");
                Object provider = providerClass.getMethod("builder").invoke(null);
                provider.getClass().getMethod("keyArn", AdcpUse.class, String.class)
                        .invoke(provider, use, keyArn);
                provider.getClass().getMethod("region", String.class)
                        .invoke(provider, region);

                Object built = provider.getClass().getMethod("build").invoke(provider);

                connectivityOk = true;

                try {
                    built.getClass().getMethod("initialize").invoke(built);
                    signingOk = true;
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof SigningException) {
                        signingError = cause.getMessage();
                    } else {
                        signingError = cause.getMessage();
                    }
                }

                try {
                    @SuppressWarnings("unchecked")
                    Map<AdcpUse, ?> algorithms = (Map<AdcpUse, ?>) built.getClass()
                            .getMethod("getAlgorithms").invoke(built);
                    purposeOk = algorithms.containsKey(use);
                    if (!purposeOk) {
                        purposeError = "No algorithm resolved for use: " + use;
                    }
                } catch (Exception e) {
                    purposeError = e.getMessage();
                }

            } catch (ClassNotFoundException e) {
                connectivityError = "AWS KMS provider not on classpath";
            } catch (Exception e) {
                connectivityError = e.getMessage();
            }

            boolean passed = connectivityOk && signingOk && purposeOk;
            return new ProbeResult("aws", use, keyArn, connectivityOk, connectivityError,
                    signingOk, signingError, purposeOk, purposeError, passed);
        }
    }

    static final class GcpKeyProbe implements KeyProbe {
        private final AdcpUse use;
        private final String keyVersionPath;
        private final String credentialsPath;

        GcpKeyProbe(AdcpUse use, String keyVersionPath, String credentialsPath) {
            this.use = use;
            this.keyVersionPath = keyVersionPath;
            this.credentialsPath = credentialsPath;
        }

        @Override
        public ProbeResult run() {
            boolean connectivityOk = false;
            String connectivityError = null;
            boolean signingOk = false;
            String signingError = null;
            boolean purposeOk = false;
            String purposeError = null;

            try {
                Class<?> providerClass = Class.forName(
                        "org.adcontextprotocol.adcp.signing.gcp.GcpKmsSigningProvider");
                Object provider = providerClass.getMethod("builder").invoke(null);
                provider.getClass().getMethod("keyVersionPath", AdcpUse.class, String.class)
                        .invoke(provider, use, keyVersionPath);
                if (credentialsPath != null) {
                    provider.getClass().getMethod("credentialsPath", String.class)
                            .invoke(provider, credentialsPath);
                }

                Object built = provider.getClass().getMethod("build").invoke(provider);

                connectivityOk = true;

                try {
                    built.getClass().getMethod("initialize").invoke(built);
                    signingOk = true;
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof SigningException) {
                        signingError = cause.getMessage();
                    } else {
                        signingError = cause.getMessage();
                    }
                }

                try {
                    @SuppressWarnings("unchecked")
                    Map<AdcpUse, ?> algorithms = (Map<AdcpUse, ?>) built.getClass()
                            .getMethod("getAlgorithms").invoke(built);
                    purposeOk = algorithms.containsKey(use);
                    if (!purposeOk) {
                        purposeError = "No algorithm resolved for use: " + use;
                    }
                } catch (Exception e) {
                    purposeError = e.getMessage();
                }

            } catch (ClassNotFoundException e) {
                connectivityError = "GCP KMS provider not on classpath";
            } catch (Exception e) {
                connectivityError = e.getMessage();
            }

            boolean passed = connectivityOk && signingOk && purposeOk;
            return new ProbeResult("gcp", use, keyVersionPath, connectivityOk, connectivityError,
                    signingOk, signingError, purposeOk, purposeError, passed);
        }
    }
}