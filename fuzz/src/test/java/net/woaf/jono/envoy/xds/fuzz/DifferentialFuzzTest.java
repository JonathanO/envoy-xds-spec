package net.woaf.jono.envoy.xds.fuzz;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.hegel.Hegel;
import dev.hegel.Settings;
import dev.hegel.TestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The differential fuzzer: random vectors run through every port's replay mode, and what each port sent compared. A
 * mismatch fails with the smallest vector that shows it, which is also written to build/fuzz/fuzz-mismatch.json, ready
 * to become a vector once someone decides which port is right. See docs/FUZZING.md.
 */
@EnabledIfSystemProperty(named = "fuzz.ports", matches = ".+")
class DifferentialFuzzTest {

    @Test
    void everyPortSendsTheSame() throws Exception {
        Path out = Files.createDirectories(Path.of(System.getProperty("fuzz.out")));
        List<Port> ports = new ArrayList<>();
        try {
            for (String port : System.getProperty("fuzz.ports").split(",")) {
                String[] nameAndCommand = port.split("=", 2);
                ports.add(Port.start(nameAndCommand[0], Path.of(nameAndCommand[1]), out.resolve(nameAndCommand[0] + ".stderr")));
            }
            Hegel.test(tc -> {
                JsonObject vector = VectorGenerator.vector(tc);
                compare(tc, vector, ports, out);
            }, new Settings().testCases(Long.parseLong(System.getProperty("fuzz.cases"))));
        } finally {
            for (Port port : ports) {
                port.close();
            }
        }
    }

    private static void compare(TestCase tc, JsonObject vector, List<Port> ports, Path out) {
        Port first = ports.get(0);
        Map<String, Runs.Run> expected = Runs.of(first.replay(vector));
        check(first, expected, vector);
        // Steer towards vectors that make the ports say more: there's more to differ in.
        tc.target(expected.values().stream().flatMap(r -> r.steps().stream()).mapToLong(s -> s.values().stream().mapToLong(List::size).sum()).sum());
        for (Port port : ports.subList(1, ports.size())) {
            Map<String, Runs.Run> actual = Runs.of(port.replay(vector));
            check(port, actual, vector);
            for (Map.Entry<String, Runs.Run> run : expected.entrySet()) {
                List<Map<String, List<Runs.Got>>> want = run.getValue().steps();
                List<Map<String, List<Runs.Got>>> got = actual.get(run.getKey()).steps();
                for (int i = 0; i < Math.max(want.size(), got.size()); i++) {
                    Object a = i < want.size() ? want.get(i) : "nothing: the run had stopped";
                    Object b = i < got.size() ? got.get(i) : "nothing: the run had stopped";
                    if (!a.equals(b)) {
                        String shown = new GsonBuilder().setPrettyPrinting().create().toJson(vector);
                        try {
                            Files.writeString(out.resolve("fuzz-mismatch.json"), shown + "\n");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                        throw new AssertionError(String.format("%s, step %d: %s got%n  %s%nbut %s got%n  %s%nThe vector:%n%s",
                            run.getKey(), i + 1, first.name, a, port.name, b, shown));
                    }
                }
            }
        }
    }

    /** A port that can't run a vector has a bug in its replay mode, or the generator has one. */
    private static void check(Port port, Map<String, Runs.Run> runs, JsonObject vector) {
        runs.forEach((protocol, run) -> {
            if (run.error() != null) {
                throw new AssertionError(port.name + " couldn't run the vector on " + protocol + ": " + run.error() + "\n" + vector);
            }
        });
    }
}
