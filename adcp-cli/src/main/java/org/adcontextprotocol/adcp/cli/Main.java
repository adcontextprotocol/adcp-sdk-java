package org.adcontextprotocol.adcp.cli;

/**
 * Entry point for the {@code adcp} CLI. Commands land here as the CLI track
 * is implemented; today this is a placeholder that prints a usage stub.
 */
public final class Main {

    private Main() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) {
        System.out.println("adcp <not yet implemented>");
        System.out.println("see ROADMAP.md track 13 (cli) for the planned surface");
    }
}
