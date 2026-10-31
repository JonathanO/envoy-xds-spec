# Conformance against real Envoy

[conformance/](../conformance) is the harness: it runs real Envoys in Docker, through
[Testcontainers](https://java.testcontainers.org/), points them at a control plane, records the xDS conversation, and
checks it, Envoy's stats and its log. Its suite, `XdsConformanceTest`, gives the control plane config and checks that
a real Envoy gets there safely: make before break, every response ACKed, nothing sent unasked, no warnings. Its
[README](../conformance/README.md) lists the scenarios and the checks.

Every port runs the same suite, in its own CI, on every Envoy release upstream supports. The trace vectors
([VECTORS.md](VECTORS.md)) check what a port sends; this checks that a real Envoy takes it.

There are two ways to run it:

- **In process**, for a Java library: extend `XdsConformanceTest` and give it a control plane, as its README shows.
  java-envoy-xds does this, including the build with `includeBuild("spec/conformance")`.
- **Against a conformance server**: a small binary the port provides, which the harness starts and drives over the
  control API below. Every other port does this.

## Running it against a conformance server

The harness is a Gradle build, and needs JDK 21 or later, and Docker or another
[container runtime](../conformance/README.md#container-runtimes).

```sh
cd conformance
./gradlew conformance -Pserver=/absolute/path/to/server -PenvoyVersions=1.40 -PtestParallelism=4
```

- `-Pserver` is the binary. A relative path is taken from `conformance/`.
- `-PenvoyVersions` is a comma separated list of Envoy releases; by default every one upstream supports.
- `-PtestParallelism` is how many tests run at once, each with its own Envoy and its own server; by default 4.

Results are in `build/test-results/conformance/`, and a report in `build/reports/tests/conformance/`. A failed test
prints Envoy's log.

## The conformance server

The harness starts the server once per test, and once more to read its deviations. Each run serves one Envoy.

### Starting

The server takes no arguments. It listens on two free TCP ports on `127.0.0.1`: one serving ADS over gRPC, SotW and
delta, and one serving the control API over HTTP/1.1. Once both are listening, it prints one line of JSON to stdout:

```json
{"ads": 41007, "control": 41008, "deviations": {"sotw/changesWhileDisconnected": "why"}}
```

- `ads` and `control` are the ports.
- `deviations` is optional: scenarios the port is known to fail, which the harness skips with the reason given. A key
  is a test's path, or part of it: any of protocol (`sotw`, `delta`), Envoy version (`v1.36`), mode (`normal`,
  `rate-limited`) and scenario (`changesWhileDisconnected`), joined by `/`.

Anything else it prints to stdout is ignored; it logs to stderr, which the harness shows. It exits when its stdin
closes, so it never outlives the harness, and also when sent SIGTERM.

Envoy doesn't connect to the ADS port directly. It connects to the harness, which records each stream and forwards it
to the server. When the suite disconnects Envoy, the harness closes the stream to the server too.

### Serving

The server serves ADS from the port's server, over its snapshot module, as the trace vector runner does:

- config builders for `LDS`, `RDS`, `SRDS`, `VHDS`, `CDS` and `EDS`;
- each Envoy's client details are its node ID, and so is its view: the snapshot source's `viewOf` returns the node ID;
- each Envoy's client config subscribes it to `LDS`, `RDS`, `CDS` and `EDS`, so stale clusters after a reconnect
  don't wait for the types only some scenarios use;
- the backlog that merges updates, the default.

### The control API

Requests and responses are JSON. A request that succeeds answers `200`; one that fails answers `4xx` or `5xx`, with a
message as its body, and the test fails with it.

#### `POST /update`: change the config

The body is one snapshot source update: a list of operations, applied in order, as a trace vector's `update` step
([VECTORS.md](VECTORS.md#update-a-config-change)):

| Operation | |
|---|---|
| `{"setView": "v", "groups": ["a", "b"]}` | Set a view's groups, first first. |
| `{"editGroup": "g", "put": {...}, "remove": {...}}` | Edit a group, making it if need be. `put` lists resources by type; `remove` lists names by type. Puts go before removes. |
| `{"setGroup": "g", "resources": {...}}` | Replace a group with these resources, by type. |
| `{"removeGroup": "g"}` | Remove a group. |
| `"then"` | End a phase: what follows is the next. |

Types are named as in vectors: `LDS`, `RDS`, `SRDS`, `VHDS`, `CDS`, `EDS`. Unlike in vectors, each resource is a
string: the base64 (standard, padded) of its protobuf binary encoding, not its protobuf JSON. The suite's listeners
hold `Any` fields, an HTTP connection manager and its filters, and binary leaves those as bytes: a server needs only
the six resource types it serves, not every type nested in them.

```json
[{"setView": "test-envoy-0", "groups": ["routing", "clusters"]},
 {"setGroup": "routing", "resources": {"RDS": ["CglhbGxfcm91dGVz..."]}},
 {"setGroup": "clusters", "resources": {"CDS": ["CgNmb28..."], "EDS": ["CgNmb28..."]}}]
```

The update is made before the response is sent; the server may push it to Envoy after.

The suite only sends `setView` and `setGroup`. Its first update sets the view of its Envoy, `test-envoy-0`, to two
groups, `routing` (listeners, route configs, scoped route configs and virtual hosts) and `clusters` (clusters and
endpoints), so that real Envoys check that a view merges its groups. Each later update replaces both groups: each
names the whole desired config.

#### `POST /resync`: make an Envoy sync from scratch

```json
{"node": "test-envoy-0"}
```

Calls resync on the client of the latest stream from that node: the server drops the stream, and Envoy reconnects
and syncs afresh. The suite does this to get a fixed config to an Envoy that NACKed. It answers `4xx` if no Envoy has
connected as that node.

#### `GET /idle`: has the server stopped talking?

```json
{"idle": true}
```

Whether the latest stream has nothing in flight or waiting to be sent: no push under way, no ACK awaited, and nothing
for a quiet period (500ms in both servers so far), as the port's stream metrics tell it. The suite polls this, as well
as the recorded conversation, to know a change has settled. Before any stream, it's idle.

### Why HTTP and JSON

Every port can serve it with what it has: Go with its standard library's `net/http` and `encoding/json`, Rust with
hyper and serde_json beside tonic, and Java with the JDK's HTTP server and client and Gson. gRPC would need each port
to generate code from a control proto, adding protoc to its build, for three calls; it would buy streaming and types,
which the API doesn't need. The JSON shapes are the vector format's, so a port's server and its vector runner apply
updates the same way.

## Writing a conformance server

A server is a page or two of code, most of it what a vector runner already has: parsing an update and applying it to
the snapshot source. go-envoy-xds's, in its `e2e/` module, is one to follow:

1. Make the snapshot source, with `viewOf` returning the node ID, and the server, configured as above.
2. Record the client of each node's latest stream as it connects, for `/resync`, and keep the stream metrics of the
   latest stream, for `/idle`.
3. Listen on `127.0.0.1:0` twice: serve ADS on one and the control API on the other.
4. Print the ports, and the port's deviations, as one line of JSON.
5. Serve until stdin closes.

Then, in the port's CI, on each Envoy release:

1. check out the spec, e.g. as a submodule;
2. set up a JDK, 21 or later;
3. build the server;
4. run `./gradlew conformance -Pserver=... -PenvoyVersions=<release>` in `conformance/`.
