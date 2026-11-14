package com.stocktracker.packs.runtime;

import java.time.Duration;
import java.util.List;

/**
 * Runs one local process with a fixed argv (never through a shell) and stdin.
 * The seam that lets tests exercise the SSH runtime without a machine.
 */
public interface CommandRunner {

    record Result(int exitCode, String stdout, String stderr, boolean timedOut) { }

    Result run(List<String> argv, String stdin, Duration timeout);

    /** Starts a long-lived process (SSH tunnel). */
    Process start(List<String> argv);
}
