package org.adcontextprotocol.adcp.cli;

import org.adcontextprotocol.adcp.signing.AdcpUse;

import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for the {@code adcp} CLI.
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code signing-probe} — pre-deploy KMS connectivity and key usability check</li>
 * </ul>
 */
public final class Main {

    private Main() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];
        String[] subArgs = new String[args.length - 1];
        System.arraycopy(args, 1, subArgs, 0, subArgs.length);

        switch (command) {
            case "signing-probe" -> runSigningProbe(subArgs);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                System.exit(1);
            }
        }
    }

    private static void runSigningProbe(String[] args) {
        SigningProbeCommand probe = new SigningProbeCommand();
        List<String> errors = new ArrayList<>();

        int i = 0;
        while (i < args.length) {
            String arg = args[i];
            switch (arg) {
                case "--aws-key" -> {
                    if (i + 3 >= args.length) {
                        errors.add("--aws-key requires <use> <key-arn> <region>");
                        i = args.length;
                    } else {
                        try {
                            AdcpUse use = AdcpUse.fromWireName(args[i + 1]);
                            probe.addAwsKey(use, args[i + 2], args[i + 3]);
                        } catch (IllegalArgumentException e) {
                            errors.add("Invalid adcp_use for --aws-key: " + args[i + 1]
                                    + ". Valid values: adcp_req, adcp_whk");
                        }
                        i += 4;
                    }
                }
                case "--gcp-key" -> {
                    if (i + 3 >= args.length) {
                        errors.add("--gcp-key requires <use> <key-version-path> <credentials-path>");
                        i = args.length;
                    } else {
                        try {
                            AdcpUse use = AdcpUse.fromWireName(args[i + 1]);
                            probe.addGcpKey(use, args[i + 2], args[i + 3]);
                        } catch (IllegalArgumentException e) {
                            errors.add("Invalid adcp_use for --gcp-key: " + args[i + 1]
                                    + ". Valid values: adcp_req, adcp_whk");
                        }
                        i += 4;
                    }
                }
                default -> {
                    errors.add("Unknown option: " + arg);
                    i++;
                }
            }
        }

        if (!errors.isEmpty()) {
            for (String error : errors) {
                System.err.println("Error: " + error);
            }
            System.err.println();
            printSigningProbeUsage();
            System.exit(1);
        }

        try {
            int exitCode = probe.call();
            System.exit(exitCode);
        } catch (Exception e) {
            System.err.println("Probe failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: adcp <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  signing-probe    Pre-deploy KMS connectivity and key usability check");
        System.out.println();
        System.out.println("Run 'adcp <command> --help' for command details.");
    }

    private static void printSigningProbeUsage() {
        System.out.println("Usage: adcp signing-probe [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --aws-key <use> <key-arn> <region>");
        System.out.println("      Probe an AWS KMS key. <use> is adcp_req or adcp_whk.");
        System.out.println("  --gcp-key <use> <key-version-path> <credentials-path>");
        System.out.println("      Probe a GCP KMS key. <use> is adcp_req or adcp_whk.");
        System.out.println();
        System.out.println("Exit code: 0 if all probes pass, 1 if any fail.");
    }
}