package com.stocktracker.packs.runtime;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** {@link CommandRunner} backed by {@link ProcessBuilder}. No shell is involved locally. */
@Component
public class ProcessCommandRunner implements CommandRunner {

    @Override
    public Result run(List<String> argv, String stdin, Duration timeout) {
        try {
            Process p = new ProcessBuilder(argv).start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
            try (OutputStream in = p.getOutputStream()) {
                if (stdin != null) in.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // process exited before reading stdin; its exit code tells the story
            }
            boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return new Result(-1, out.getNow(""), err.getNow(""), true);
            }
            return new Result(p.exitValue(), out.get(5, TimeUnit.SECONDS), err.get(5, TimeUnit.SECONDS), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "", "interrupted", false);
        } catch (Exception e) {
            return new Result(-1, "", e.getClass().getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    @Override
    public Process start(List<String> argv) {
        try {
            return new ProcessBuilder(argv).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            throw new IllegalStateException("Could not start " + argv.get(0) + ": " + e.getMessage());
        }
    }

    private static String read(java.io.InputStream s) {
        try {
            byte[] b = s.readNBytes(1 << 20); // keep at most 1 MiB per stream…
            s.transferTo(OutputStream.nullOutputStream()); // …but keep draining so the child never blocks
            return new String(b, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
