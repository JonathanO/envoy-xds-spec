package net.woaf.jono.envoy.xds.fuzz;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** A port's replay mode, as docs/REPLAY.md says: one process, given one vector at a time. */
final class Port {

    /** Long enough for a replay command to build itself on its first vector. */
    private static final long TIMEOUT_SECONDS = 300;

    private static final String EOF = "";

    final String name;

    private final Process process;

    private final Writer in;

    private final BlockingQueue<String> results = new LinkedBlockingQueue<>();

    private Port(String name, Process process) {
        this.name = name;
        this.process = process;
        this.in = process.outputWriter(UTF_8);
        BufferedReader out = process.inputReader(UTF_8);
        Thread reader = new Thread(() -> {
            try {
                for (String line = out.readLine(); line != null; line = out.readLine()) {
                    if (line.startsWith("{")) {
                        results.add(line);
                    }
                }
            } catch (IOException e) {
                // it exited
            }
            results.add(EOF);
        }, "replay " + name);
        reader.setDaemon(true);
        reader.start();
    }

    static Port start(String name, Path command, Path stderr) {
        try {
            return new Port(name, new ProcessBuilder(command.toString()).redirectError(stderr.toFile()).start());
        } catch (IOException e) {
            throw new UncheckedIOException("couldn't start " + name + "'s replay mode, " + command, e);
        }
    }

    JsonObject replay(JsonObject vector) {
        try {
            in.write(vector.toString() + "\n");
            in.flush();
            String result = results.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (result == null || result.equals(EOF)) {
                throw new IllegalStateException(name + "'s replay mode " + (result == null ? "didn't answer" : "exited") + ": see its stderr");
            }
            return JsonParser.parseString(result).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(name + "'s replay mode stopped reading", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    void close() throws InterruptedException {
        try {
            in.close();
        } catch (IOException e) {
            // it had exited
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
    }
}
