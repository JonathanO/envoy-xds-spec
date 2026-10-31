package net.woaf.jono.envoy.testing.conformance;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A conformance server, as docs/CONFORMANCE.md describes, that serves no config: it answers each ADS request with an
 * empty response, records each control API request, and lists them at {@code GET /received}.
 */
final class FakeConformanceServer {

    private FakeConformanceServer() {
    }

    public static void main(String[] args) throws IOException {
        // Stdout is for the line saying the ports: anything else, such as logging, goes to stderr.
        final PrintStream stdout = System.out;
        System.setOut(System.err);
        Gson gson = new Gson();
        List<String> received = new CopyOnWriteArrayList<>();
        Server ads = ServerBuilder.forPort(0).addService(new AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase() {
            @Override
            public StreamObserver<DiscoveryRequest> streamAggregatedResources(StreamObserver<DiscoveryResponse> envoy) {
                return new Answer<>(r -> envoy.onNext(DiscoveryResponse.newBuilder().setTypeUrl(r.getTypeUrl()).setNonce("1").build()), envoy);
            }

            @Override
            public StreamObserver<DeltaDiscoveryRequest> deltaAggregatedResources(StreamObserver<DeltaDiscoveryResponse> envoy) {
                return new Answer<>(r -> envoy.onNext(DeltaDiscoveryResponse.newBuilder().setTypeUrl(r.getTypeUrl()).setNonce("1").build()), envoy);
            }
        }).build().start();
        HttpServer control = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        control.createContext("/", exchange -> {
            String request = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
            switch (request) {
                case "POST /update", "POST /resync" -> {
                    received.add(request + " " + body);
                    reply(exchange, 200, "{}");
                }
                case "GET /idle" -> reply(exchange, 200, "{\"idle\": true}");
                case "GET /received" -> reply(exchange, 200, gson.toJson(received));
                default -> reply(exchange, 404, "no " + request);
            }
        });
        control.start();
        stdout.println(gson.toJson(Map.of("ads", ads.getPort(), "control", control.getAddress().getPort(),
            "deviations", Map.of("sotw/startsEmpty", "a fake"))));
        stdout.flush();
        System.in.transferTo(OutputStream.nullOutputStream());
        System.exit(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Answers each request, until Envoy closes the stream. */
    private record Answer<Q, R>(java.util.function.Consumer<Q> answer, StreamObserver<R> envoy) implements StreamObserver<Q> {
        @Override
        public void onNext(Q request) {
            answer.accept(request);
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
            envoy.onCompleted();
        }
    }
}
