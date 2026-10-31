package net.woaf.jono.envoy.testing.conformance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The harness's side of the conformance server protocol, against a fake server, without Envoy. */
@DisabledOnOs(OS.WINDOWS) // the server is started from a shell script
class ExternalServerTest {

    private static final Gson GSON = new Gson();

    private ExternalServer server;

    @BeforeEach
    void start(@TempDir Path dir) throws IOException {
        Path script = dir.resolve("server");
        Files.writeString(script, "#!/bin/sh\nexec '" + Path.of(System.getProperty("java.home"), "bin", "java") + "' --enable-native-access=ALL-UNNAMED -cp '"
            + System.getProperty("java.class.path") + "' " + FakeConformanceServer.class.getName() + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        server = ExternalServer.start(script.toString());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void readsTheDeviationsTheServerPrints() {
        assertThat(server.deviations).containsExactly(Map.entry("sotw/startsEmpty", "a fake"));
    }

    @Test
    void proxiesBothProtocolsToTheServer() throws Exception {
        Server proxy = ServerBuilder.forPort(0).addService(server.service()).build().start();
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", proxy.getPort()).usePlaintext().build();
        try {
            AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub envoy = AggregatedDiscoveryServiceGrpc.newStub(channel);
            CompletableFuture<DiscoveryResponse> sotw = new CompletableFuture<>();
            envoy.streamAggregatedResources(firstOf(sotw)).onNext(DiscoveryRequest.newBuilder().setTypeUrl("cds").build());
            CompletableFuture<DeltaDiscoveryResponse> delta = new CompletableFuture<>();
            envoy.deltaAggregatedResources(firstOf(delta)).onNext(DeltaDiscoveryRequest.newBuilder().setTypeUrl("eds").build());

            assertThat(sotw.get(10, TimeUnit.SECONDS).getTypeUrl()).isEqualTo("cds");
            assertThat(delta.get(10, TimeUnit.SECONDS).getTypeUrl()).isEqualTo("eds");
        } finally {
            channel.shutdownNow();
            proxy.shutdownNow();
        }
    }

    @Test
    void setsTheViewOnceThenReplacesBothGroups() throws Exception {
        Cluster cluster = Cluster.newBuilder().setName("foo").build();
        RouteConfiguration routes = RouteConfiguration.newBuilder().setName("routes").build();

        server.setConfig(List.of(cluster, routes));
        server.setConfig(List.of());

        String clusterBytes = Base64.getEncoder().encodeToString(cluster.toByteArray());
        String routesBytes = Base64.getEncoder().encodeToString(routes.toByteArray());
        assertThat(received()).containsExactly(
            Map.entry("POST /update", GSON.toJsonTree(List.of(
                Map.of("setView", "test-envoy-0", "groups", List.of("routing", "clusters")),
                Map.of("setGroup", "routing", "resources", Map.of("RDS", List.of(routesBytes))),
                Map.of("setGroup", "clusters", "resources", Map.of("CDS", List.of(clusterBytes)))))),
            Map.entry("POST /update", GSON.toJsonTree(List.of(
                Map.of("setGroup", "routing", "resources", Map.of()),
                Map.of("setGroup", "clusters", "resources", Map.of())))));
    }

    @Test
    void resyncsTheSuitesEnvoy() throws Exception {
        assertThat(server.resync()).isTrue();

        assertThat(received()).containsExactly(Map.entry("POST /resync", GSON.toJsonTree(Map.of("node", "test-envoy-0"))));
    }

    @Test
    void asksTheServerIfItsIdle() throws Exception {
        assertThat(server.idleHook().isIdle()).isTrue();
    }

    @Test
    void closeStopsTheServer() throws Exception {
        server.close();

        assertThat(server.process.isAlive()).isFalse();
    }

    /** The control API requests the server got, each as its method and path, and its body. */
    private List<Map.Entry<String, JsonElement>> received() throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(server.control.resolve(URI.create("/received"))).GET().build(), HttpResponse.BodyHandlers.ofString(UTF_8));
        List<String> requests = GSON.fromJson(response.body(), new TypeToken<List<String>>() { }.getType());
        return requests.stream().map(r -> {
            int body = r.indexOf(' ', r.indexOf(' ') + 1);
            return Map.entry(r.substring(0, body), JsonParser.parseString(r.substring(body + 1)));
        }).toList();
    }

    private static <R> StreamObserver<R> firstOf(CompletableFuture<R> first) {
        return new StreamObserver<>() {
            @Override
            public void onNext(R response) {
                first.complete(response);
            }

            @Override
            public void onError(Throwable t) {
                first.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
            }
        };
    }
}
