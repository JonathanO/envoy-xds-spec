package net.woaf.jono.envoy.xds.fuzz;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.ScopedRouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.Secret;
import io.envoyproxy.envoy.service.runtime.v3.Runtime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * A port's replay of one vector, as docs/REPLAY.md says, in a form to compare: per protocol, per step, each Envoy's
 * responses in order, each with its resources by name, parsed, and the names it removed.
 */
final class Runs {

    private static final Map<String, Supplier<Message.Builder>> BUILDERS = Map.of(
        "CDS", Cluster::newBuilder,
        "EDS", ClusterLoadAssignment::newBuilder,
        "LDS", Listener::newBuilder,
        "RDS", RouteConfiguration::newBuilder,
        "SRDS", ScopedRouteConfiguration::newBuilder,
        "VHDS", VirtualHost::newBuilder,
        "SDS", Secret::newBuilder,
        "RTDS", Runtime::newBuilder);

    /** What an Envoy got: a response, or its stream ending. */
    sealed interface Got permits Response, Ended {
    }

    record Response(String type, Map<String, String> resources, Set<String> removed) implements Got {
    }

    record Ended(String code) implements Got {
    }

    /** One protocol's run: per step, each Envoy's responses. {@code error} is set if the port couldn't run it. */
    record Run(List<Map<String, List<Got>>> steps, String error) {
    }

    private Runs() {
    }

    static Map<String, Run> of(JsonObject result) {
        Map<String, Run> runs = new TreeMap<>();
        for (Map.Entry<String, JsonElement> run : result.getAsJsonObject("runs").entrySet()) {
            JsonObject r = run.getValue().getAsJsonObject();
            List<Map<String, List<Got>>> steps = new ArrayList<>();
            for (JsonElement step : r.getAsJsonArray("steps")) {
                Map<String, List<Got>> byEnvoy = new TreeMap<>();
                for (JsonElement got : step.getAsJsonArray()) {
                    JsonObject g = got.getAsJsonObject();
                    byEnvoy.computeIfAbsent(g.get("envoy").getAsString(), e -> new ArrayList<>()).add(got(g));
                }
                steps.add(byEnvoy);
            }
            runs.put(run.getKey(), new Run(steps, r.has("error") ? r.get("error").getAsString() : null));
        }
        return runs;
    }

    private static Got got(JsonObject g) {
        if (g.has("ended")) {
            return new Ended(g.get("ended").getAsString());
        }
        String type = g.get("type").getAsString();
        Map<String, String> resources = new LinkedHashMap<>();
        for (JsonElement resource : g.getAsJsonArray("resources")) {
            Message message = parse(type, resource);
            String name = type.equals("EDS") ? ((ClusterLoadAssignment) message).getClusterName()
                : (String) message.getField(message.getDescriptorForType().findFieldByName("name"));
            if (resources.put(name, TextFormat.printer().emittingSingleLine(true).printToString(message)) != null) {
                resources.put(name + " (again)", ""); // so it shows as a mismatch
            }
        }
        Set<String> removed = new TreeSet<>();
        if (g.has("removed")) {
            g.getAsJsonArray("removed").forEach(n -> removed.add(n.getAsString()));
        }
        return new Response(type, new TreeMap<>(resources), removed);
    }

    private static Message parse(String type, JsonElement json) {
        Message.Builder builder = BUILDERS.get(type).get();
        try {
            JsonFormat.parser().merge(json.toString(), builder);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("not a " + type + " resource: " + json, e);
        }
        return builder.build();
    }
}
