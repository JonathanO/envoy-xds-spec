package net.woaf.jono.envoy.xds.fuzz;

import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.sets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hegel.TestCase;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Random trace vectors, as docs/VECTORS.md describes, that are valid as docs/REPLAY.md says: a disconnect or resync
 * only of an Envoy with a stream, and kept names only on delta, on a type's first request of the stream. Names, groups and views
 * come from small sets, so steps often touch the same things.
 */
final class VectorGenerator {

    static final List<String> TYPES = List.of("CDS", "EDS", "LDS", "RDS", "SRDS", "VHDS", "SDS", "RTDS");

    private static final List<String> NAMES = List.of("a", "b", "c", "*");

    private static final List<String> GROUPS = List.of("g1", "g2", "g3");

    private static final List<String> VIEWS = List.of("v1", "v2");

    private VectorGenerator() {
    }

    static JsonObject vector(TestCase tc) {
        String protocol = tc.draw(sampledFrom("sotw", "delta"), "protocol");
        JsonObject vector = new JsonObject();
        vector.addProperty("name", "fuzz-mismatch");
        vector.addProperty("description", "Found by the differential fuzzer: the ports answered it differently.");
        vector.add("protocols", array(List.of(protocol)));
        List<String> served = List.copyOf(new TreeSet<>(tc.draw(sets(sampledFrom(TYPES)).minSize(1), "serves")));
        vector.add("serves", array(served));

        int envoyCount = tc.draw(integers().min(1).max(2), "envoys");
        JsonArray envoys = new JsonArray();
        // Each Envoy's open stream, if it has one: the types it has asked for on it.
        Map<String, Set<String>> streams = new HashMap<>();
        for (int i = 1; i <= envoyCount; i++) {
            JsonObject envoy = new JsonObject();
            envoy.addProperty("name", "e" + i);
            envoy.addProperty("view", tc.draw(sampledFrom(VIEWS), "view"));
            if (tc.generateBoolean(0.2)) {
                envoy.add("subscribesTo", array(new TreeSet<>(tc.draw(sets(sampledFrom(TYPES)), "subscribesTo"))));
            }
            if (tc.generateBoolean(0.2)) {
                envoy.addProperty("delayUpdatesUntilAckOf", tc.draw(sampledFrom(TYPES), "delayUpdatesUntilAckOf"));
            }
            envoys.add(envoy);
        }
        vector.add("envoys", envoys);

        JsonArray steps = new JsonArray();
        int count = tc.draw(integers().min(1).max(40), "steps");
        for (int i = 0; i < count; i++) {
            int kind = tc.draw(integers().min(0).max(9), "kind");
            List<String> open = streams.keySet().stream().sorted().toList();
            if (kind <= 2) {
                steps.add(update(tc));
            } else if (kind == 8 && tc.generateBoolean(0.5)) {
                JsonObject step = new JsonObject();
                step.addProperty("advance", tc.draw(sampledFrom("1s", "10s", "29s", "30s"), "advance"));
                steps.add(step);
            } else if (kind == 9 && !open.isEmpty()) {
                String envoy = tc.draw(sampledFrom(open), "envoy");
                streams.remove(envoy); // either way, its next request opens a new stream
                JsonObject step = new JsonObject();
                step.addProperty(tc.generateBoolean(0.3) ? "resync" : "disconnect", envoy);
                steps.add(step);
            } else {
                steps.add(request(tc, protocol, envoyCount, served, streams));
            }
        }
        vector.add("steps", steps);
        return vector;
    }

    private static JsonObject update(TestCase tc) {
        JsonArray ops = new JsonArray();
        int count = tc.draw(integers().min(1).max(3), "ops");
        for (int i = 0; i < count; i++) {
            JsonObject op = new JsonObject();
            switch (tc.draw(integers().min(0).max(4), "op")) {
                case 0 -> {
                    op.addProperty("setView", tc.draw(sampledFrom(VIEWS), "view"));
                    op.add("groups", array(new LinkedHashSet<>(tc.draw(lists(sampledFrom(GROUPS)).maxSize(3), "groups"))));
                }
                case 1 -> {
                    op.addProperty("editGroup", tc.draw(sampledFrom(GROUPS), "group"));
                    op.add("put", resources(tc));
                    JsonObject remove = new JsonObject();
                    for (String type : tc.draw(sets(sampledFrom(TYPES)).maxSize(2), "removeTypes")) {
                        remove.add(type, array(new TreeSet<>(tc.draw(sets(sampledFrom(NAMES.subList(0, 3))).minSize(1), "remove"))));
                    }
                    op.add("remove", remove);
                }
                case 2 -> {
                    op.addProperty("setGroup", tc.draw(sampledFrom(GROUPS), "group"));
                    op.add("resources", resources(tc));
                }
                case 3 -> op.addProperty("removeGroup", tc.draw(sampledFrom(GROUPS), "group"));
                default -> {
                    ops.add("then");
                    continue;
                }
            }
            ops.add(op);
        }
        JsonObject step = new JsonObject();
        step.add("update", ops);
        return step;
    }

    /** Resources by type, each named from a small set, in one of two variants, so a put may change one or not. */
    private static JsonObject resources(TestCase tc) {
        JsonObject byType = new JsonObject();
        for (String type : tc.draw(sets(sampledFrom(TYPES)).maxSize(3), "types")) {
            JsonArray resources = new JsonArray();
            for (String name : new TreeSet<>(tc.draw(sets(sampledFrom(NAMES.subList(0, 3))).minSize(1), "names"))) {
                resources.add(resource(type, name, tc.draw(integers().min(1).max(2), "variant")));
            }
            byType.add(type, resources);
        }
        return byType;
    }

    static JsonElement resource(String type, String name, int v) {
        String json = switch (type) {
            case "CDS" -> "{'name': '%s', 'connectTimeout': '%ds'}";
            case "EDS" -> "{'clusterName': '%s', 'policy': {'overprovisioningFactor': %d00}}";
            case "LDS" -> "{'name': '%s', 'statPrefix': 's%d'}";
            case "RDS" -> "{'name': '%s', 'virtualHosts': [{'name': 'vh', 'domains': ['d%d']}]}";
            case "SRDS" -> "{'name': '%s', 'routeConfigurationName': 'r%d'}";
            case "VHDS" -> "{'name': '%s', 'domains': ['d%d']}";
            case "SDS" -> "{'name': '%s', 'genericSecret': {'secret': {'inlineString': 's%d'}}}";
            case "RTDS" -> "{'name': '%s', 'layer': {'k': 'v%d'}}";
            default -> throw new IllegalArgumentException(type);
        };
        return JsonParser.parseString(String.format(json, name, v).replace('\'', '"'));
    }

    private static JsonObject request(TestCase tc, String protocol, int envoyCount, List<String> served,
                                      Map<String, Set<String>> streams) {
        String envoy = "e" + tc.draw(integers().min(1).max(envoyCount), "envoy");
        if (tc.generateBoolean(0.03)) {
            // A type URL that isn't one of the eight: no port serves it (SPEC §4.10).
            JsonObject step = new JsonObject();
            step.addProperty("request", "type.googleapis.com/envoy.config.unknown.v3.Thing");
            step.addProperty("envoy", envoy);
            step.add("subscribe", array(new TreeSet<>(tc.draw(sets(sampledFrom(NAMES)), "subscribe"))));
            streams.computeIfAbsent(envoy, e -> new HashSet<>());
            return step;
        }
        // Mostly served types, where the rules have something to work on.
        String type = tc.draw(sampledFrom(tc.generateBoolean(0.8) ? served : TYPES), "type");
        JsonObject step = new JsonObject();
        step.addProperty("request", type);
        step.addProperty("envoy", envoy);
        Set<String> subscribe = tc.draw(sets(sampledFrom(NAMES)), "subscribe");
        Set<String> unsubscribe = new TreeSet<>(tc.draw(sets(sampledFrom(NAMES)), "unsubscribe"));
        if (!subscribe.isEmpty()) {
            step.add("subscribe", array(new TreeSet<>(subscribe)));
        }
        if (!unsubscribe.isEmpty()) {
            step.add("unsubscribe", array(unsubscribe));
        }
        int answer = tc.draw(integers().min(0).max(19), "answer");
        // Sometimes the response before the last: Envoy's answer to one, sent before the next reached it (SPEC §4.8).
        String ref = tc.generateBoolean(0.2) ? "last-1" : "last";
        if (answer == 19) {
            step.addProperty("nack", ref); // freezes the stream, so seldom
        } else if (answer >= 6) {
            step.addProperty("ack", ref);
        }
        Set<String> asked = streams.computeIfAbsent(envoy, e -> new HashSet<>());
        if (protocol.equals("delta") && asked.add(type) && tc.generateBoolean(0.3)) {
            step.add("kept", array(new TreeSet<>(tc.draw(sets(sampledFrom(NAMES.subList(0, 3))).minSize(1), "kept"))));
        }
        asked.add(type);
        return step;
    }

    private static JsonArray array(Iterable<String> strings) {
        JsonArray array = new JsonArray();
        strings.forEach(array::add);
        return array;
    }
}
