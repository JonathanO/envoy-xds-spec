package net.woaf.jono.envoy.testing.conformance;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Suppliers;
import com.google.gson.Gson;
import com.google.protobuf.Message;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.woaf.jono.envoy.testing.Settled;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Runs the suite against a port's conformance server: the binary that system property {@code conformance.server}
 * names. {@code ./gradlew conformance -Pserver=/path/to/server} runs it. docs/CONFORMANCE.md in envoy-xds-spec says
 * what the server does.
 */
@EnabledIfSystemProperty(named = "conformance.server", matches = ".+")
class ExternalServerConformanceTest extends XdsConformanceTest {

    /** Started once, to ask the server its deviations. */
    private final Supplier<Map<String, String>> deviations = Suppliers.memoize(() -> {
        ExternalServer server = ExternalServer.start();
        server.close();
        return server.deviations;
    });

    @Override
    protected ControlPlane newControlPlane() {
        return ExternalServer.start();
    }

    /**
     * Every release upstream supports, or those {@code -PenvoyVersions=1.36,1.40} names.
     */
    @Override
    protected List<String> envoyImages() {
        String versions = System.getProperty("envoyVersions", "1.36,1.37,1.38,1.39,1.40");
        return Arrays.stream(versions.split(",")).map(v -> "envoyproxy/envoy:v" + v.strip() + "-latest").toList();
    }

    @Override
    protected Map<String, String> knownDeviations() {
        return deviations.get();
    }

    /** One run of the server: ADS proxied to it, so the suite records the conversation, and its control API. */
    private static final class ExternalServer implements ControlPlane {

        private static final Gson GSON = new Gson();
        private static final HttpClient HTTP = HttpClient.newHttpClient();
        /** The node ID in the suite's Envoy bootstraps. */
        private static final String NODE = "test-envoy-0";
        private static final Map<String, String> TYPES = Map.of(
            "envoy.config.cluster.v3.Cluster", "CDS",
            "envoy.config.endpoint.v3.ClusterLoadAssignment", "EDS",
            "envoy.config.listener.v3.Listener", "LDS",
            "envoy.config.route.v3.RouteConfiguration", "RDS",
            "envoy.config.route.v3.ScopedRouteConfiguration", "SRDS",
            "envoy.config.route.v3.VirtualHost", "VHDS");
        /** Routing goes in one group and clusters in another, so real Envoys check that a view merges its groups. */
        private static final Set<String> ROUTING = Set.of("LDS", "RDS", "SRDS", "VHDS");

        /** The line the server prints once it's listening. */
        private record Started(int ads, int control, Map<String, String> deviations) {
        }

        private record Idle(boolean idle) {
        }

        private final Process process;
        private final ManagedChannel ads;
        private final URI control;
        private final Map<String, String> deviations;
        private boolean viewSet;

        private ExternalServer(Process process, Started started) {
            this.process = process;
            this.ads = ManagedChannelBuilder.forAddress("127.0.0.1", started.ads()).usePlaintext().build();
            this.control = URI.create("http://127.0.0.1:" + started.control());
            this.deviations = started.deviations() == null ? Map.of() : started.deviations();
        }

        static ExternalServer start() {
            Process process;
            try {
                process = new ProcessBuilder(System.getProperty("conformance.server")).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            BufferedReader out = process.inputReader(UTF_8);
            try {
                String line = CompletableFuture.supplyAsync(() -> {
                    try {
                        return out.readLine();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).get(30, TimeUnit.SECONDS);
                if (line == null) {
                    throw new IllegalStateException("the conformance server exited without saying its ports");
                }
                Started started = GSON.fromJson(line, Started.class);
                // Anything more it prints is drained, so it never blocks writing.
                Thread drain = new Thread(() -> {
                    try {
                        out.transferTo(Writer.nullWriter());
                    } catch (IOException e) {
                        // it exited
                    }
                });
                drain.setDaemon(true);
                drain.start();
                return new ExternalServer(process, started);
            } catch (Exception e) {
                process.destroyForcibly();
                throw new IllegalStateException("couldn't start the conformance server", e);
            }
        }

        @Override
        public BindableService service() {
            AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub server = AggregatedDiscoveryServiceGrpc.newStub(ads);
            return new AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase() {
                @Override
                public StreamObserver<DiscoveryRequest> streamAggregatedResources(StreamObserver<DiscoveryResponse> envoy) {
                    // Envoy gone: what the server still sends is dropped. Its stream is cancelled too.
                    ((ServerCallStreamObserver<DiscoveryResponse>) envoy).setOnCancelHandler(() -> { });
                    return server.streamAggregatedResources(envoy);
                }

                @Override
                public StreamObserver<DeltaDiscoveryRequest> deltaAggregatedResources(StreamObserver<DeltaDiscoveryResponse> envoy) {
                    ((ServerCallStreamObserver<DeltaDiscoveryResponse>) envoy).setOnCancelHandler(() -> { });
                    return server.deltaAggregatedResources(envoy);
                }
            };
        }

        @Override
        public void setConfig(Collection<? extends Message> resources) {
            Map<String, List<String>> routing = new LinkedHashMap<>();
            Map<String, List<String>> clusters = new LinkedHashMap<>();
            for (Message resource : resources) {
                String type = TYPES.get(resource.getDescriptorForType().getFullName());
                if (type == null) {
                    throw new IllegalArgumentException("no xDS type for " + resource.getDescriptorForType().getFullName());
                }
                (ROUTING.contains(type) ? routing : clusters).computeIfAbsent(type, t -> new ArrayList<>())
                    .add(Base64.getEncoder().encodeToString(resource.toByteArray()));
            }
            List<Object> update = new ArrayList<>();
            if (!viewSet) {
                update.add(Map.of("setView", NODE, "groups", List.of("routing", "clusters")));
                viewSet = true;
            }
            update.add(Map.of("setGroup", "routing", "resources", routing));
            update.add(Map.of("setGroup", "clusters", "resources", clusters));
            send(HttpRequest.newBuilder(control.resolve("/update")).POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(update))));
        }

        @Override
        public Settled.Hook idleHook() {
            return () -> GSON.fromJson(send(HttpRequest.newBuilder(control.resolve("/idle")).GET()), Idle.class).idle();
        }

        @Override
        public boolean resync() {
            send(HttpRequest.newBuilder(control.resolve("/resync")).POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(Map.of("node", NODE)))));
            return true;
        }

        @Override
        public void close() {
            ads.shutdownNow();
            process.destroy();
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        private String send(HttpRequest.Builder request) {
            try {
                HttpResponse<String> response = HTTP.send(request.header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(response.request().uri().getPath() + " answered " + response.statusCode() + ": " + response.body());
                }
                return response.body();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
