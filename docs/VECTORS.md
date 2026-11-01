# Trace vectors

A trace vector is a scenario as data: changes to the config, the requests Envoys send, and the responses each should
get back. Every port runs every vector in its CI, through its snapshot module, against its real server code, with a
fake Envoy per Envoy in the vector. No real Envoy is involved; [the conformance suite](../README.md) does that.

Vectors live in [vectors/](../vectors), one per file, named after the vector. [schema/vector.schema.json](../schema/vector.schema.json)
is their JSON Schema, and CI checks every vector against it.

## The format, and why

A vector is a JSON file, described by a JSON Schema. Envoy resources inside it are written in the
[protobuf JSON mapping](https://protobuf.dev/programming-guides/json/) of their message type.

Why JSON:

- **Every port can read it with what it has.** Go reads JSON with its standard library, and resources with
  `protojson`, from google.golang.org/protobuf, which it already depends on. Java has `JsonFormat` in
  protobuf-java-util, which brings Gson for the rest. Rust has serde_json, and needs a protobuf JSON reader for the
  resources (pbjson, or prost-reflect) beside prost.
- **Resources stay readable.** The JSON mapping is protobuf's own text form for messages, the one Envoy's docs use.
  A cluster is `{"name": "a", "connectTimeout": "1s"}`.
- **It's checked cheaply.** A JSON Schema validator in CI catches a malformed vector before any port runs it.
- **Diffs review well.** Vectors are formatted with each step on lines of its own, so a change to one step is a change
  to those lines.

JSON has no comments, so the format has a `comment` field instead: on the vector, its envoys, every step, update
operation and expected response. Runners ignore it.

The alternatives:

- **A protobuf schema for vectors, read as text proto**, with resources as `Any`. Text proto has comments, and
  expands `Any` readably. But each port would have to generate code from the vector schema, adding protoc to its
  build, or walk dynamic messages by field name. Text proto readers also differ more between languages than JSON
  readers do: Go and Java have them, and in Rust it means prost-reflect.
- **The same protobuf schema, read as protobuf JSON.** No comments, and still the code generation, for little over
  plain JSON: resources are protobuf JSON either way.
- **YAML.** Readable, with comments, but no port has a YAML reader already, and YAML's implicit typing makes data
  depend on the reader (`no`, `1e3`, `010`).

## A vector

```json
{
  "name": "routes-to-a-new-cluster-wait-for-its-ack",
  "description": "A route to a new cluster goes out only once Envoy has ACKed the cluster.",
  "protocols": ["sotw", "delta"],
  "serves": ["CDS", "EDS", "LDS", "RDS"],
  "envoys": [{"name": "envoy", "view": "v"}],
  "steps": [
    {"update": [{"setView": "v", "groups": ["g"]}, {"editGroup": "g", "put": {"CDS": [{"name": "a", "connectTimeout": "1s"}]}}]},
    {"request": "CDS", "expect": [{"type": "CDS", "resources": [{"name": "a", "connectTimeout": "1s"}]}]},
    {"request": "CDS", "ack": "last"},
    ...
  ]
}
```

| Field | |
|---|---|
| `name` | Its file's name, without `.json`: lower case words joined by `-`. |
| `description` | One line: what it checks. |
| `comment` | Optional: anything more the reader needs. |
| `protocols` | `["sotw"]`, `["delta"]`, or both. A vector on both runs once per protocol, with the same steps. |
| `differs` | Optional: ports known to differ, each `{"port": "go", "reason": "..."}`. Their runners skip the vector, giving the reason. A difference the spec accepts belongs here; a bug does not. |
| `serves` | The types the server has a config builder for. Others are answered with nothing, and count as not served. |
| `envoys` | The Envoys, at least one. |
| `steps` | What happens, in order. |

### Types

Types are named by their discovery service:

| Name | Resource | Named by |
|---|---|---|
| `LDS` | `envoy.config.listener.v3.Listener` | `name` |
| `RDS` | `envoy.config.route.v3.RouteConfiguration` | `name` |
| `SRDS` | `envoy.config.route.v3.ScopedRouteConfiguration` | `name` |
| `VHDS` | `envoy.config.route.v3.VirtualHost` | `name` |
| `CDS` | `envoy.config.cluster.v3.Cluster` | `name` |
| `EDS` | `envoy.config.endpoint.v3.ClusterLoadAssignment` | `cluster_name` |
| `SDS` | `envoy.extensions.transport_sockets.tls.v3.Secret` | `name` |
| `RTDS` | `envoy.service.runtime.v3.Runtime` | `name` |

### Envoys

```json
{"name": "envoy", "view": "v", "subscribesTo": ["CDS", "EDS", "LDS"], "delayUpdatesUntilAckOf": "RDS"}
```

- `name` is the Envoy's node ID, and its client details: the server's per-client config lookup returns it.
- `view` is the view the snapshot source's `viewOf` returns for it.
- `subscribesTo` and `delayUpdatesUntilAckOf` are optional, and set those options of its client config. Without
  `subscribesTo`, the Envoy gets every type served.

## Steps

After each step, the runner delivers every source update the step caused, so that every response to the step has been
sent. Then it checks the step's `expect`: the responses each Envoy got during the step, in order. A step without
`expect`, or with an empty one, expects no response at all. Every response is accounted for: one the step doesn't
expect fails the vector.

There are five kinds of step.

### `update`: a config change

One snapshot source update, as a list of operations, applied in order:

| Operation | |
|---|---|
| `{"setView": "v", "groups": ["a", "b"]}` | Set a view's groups, first first. Where two have a resource of the same type and name, the first's wins. |
| `{"editGroup": "g", "put": {...}, "remove": {...}}` | Edit a group, making it if need be. `put` lists resources by type; `remove` lists names by type. Puts go before removes. |
| `{"setGroup": "g", "resources": {...}}` | Replace a group with these resources, by type. |
| `{"removeGroup": "g"}` | Remove a group. |
| `"then"` | End a phase: what follows is the next. |

```json
{"update": [{"editGroup": "g", "put": {"RDS": [{"name": "r", "virtualHosts": [{}]}]}}, "then", {"editGroup": "g", "remove": {"CDS": ["a"]}}]}
```

An update before any Envoy connects sets up the config, and expects nothing.

### `request`: an Envoy's request

```json
{"request": "EDS", "subscribe": ["b"], "unsubscribe": ["a"], "ack": "last", "kept": ["a"], "envoy": "one"}
```

| Field | |
|---|---|
| `request` | The request's type: one of the eight, or any other type URL, such as `type.googleapis.com/an.Unknown`, which no port serves. |
| `subscribe`, `unsubscribe` | Names the Envoy starts or stops wanting. A type's first request with neither, on a wildcard type, is a wildcard subscription. `*` is a name like any other. |
| `ack` or `nack` | The response it answers, accepting or rejecting it: `"last"`, the last response of this type on the Envoy's stream, `"last-1"` the one before it (and so on), or a label (see below). Not both. |
| `kept` | Delta only: names the Envoy kept from an earlier stream, sent in `initial_resource_versions`. |
| `envoy` | Which Envoy sends it. Needed only when there are several. |

The runner turns this into the protocol's request:

- **Both.** The Envoy's first request on each stream has a `node` with its name as ID; later ones have none. A
  `nack` sets `error_detail`.
- **Delta.** `resource_names_subscribe` and `resource_names_unsubscribe` are the step's lists. `response_nonce` is the
  answered response's nonce, if the step answers one, and empty otherwise. `initial_resource_versions` maps each kept
  name to an arbitrary version: ports must compare only names. A vector that needs real versions will need the format
  to name them by label.
- **SotW.** The runner keeps the names each type wants, adds `subscribe`, takes away `unsubscribe`, and sends the
  whole set in `resource_names`. `response_nonce` is the answered response's nonce; a request answering none answers
  the last response of its type on the stream, if there is one, as Envoy does. `version_info` is the version the Envoy
  last ACKed of that type, or empty; an `ack` first makes that the answered response's version.

So a SotW request that names no response, sent while the last response of its type is unanswered, carries that
response's nonce with an older version. Vectors ACK or NACK a SotW response before the next plain request of its type,
unless that is what they test.

The conformance suite checks that real Envoys' requests follow these rules (`requestedAsVectorsModel`), on every
scenario, so a vector's requests are ones Envoy could send. The node is the exception: whether Envoy sends it on every
request is its config's (`set_node_on_first_message_only`), and the ports read it only from a stream's first request.

### `disconnect`: an Envoy closes its stream

```json
{"disconnect": "envoy"}
```

The Envoy's next request opens a new stream, and is its first: it has the node, and the Envoy has no last responses
and wants no names. Pair it with `kept` to play an Envoy reconnecting with the config it kept.

### `resync`: the user resyncs a stream

```json
{"resync": "envoy", "expect": [{"ended": "OK"}]}
```

The user asks the port to resync the Envoy's stream, as each port lets them (SPEC §5.3): the server ends it, and the
Envoy's next request opens a new stream, as after a `disconnect`. Pair it with `kept` to play Envoy reconnecting with
the config it has.

### `advance`: time passes

```json
{"advance": "30s"}
```

The server's clock moves on by that many whole seconds. Each run starts its clock at zero, and it moves only on this
step, so a run takes no real time. Ports have no timers (SPEC §6.3), so the step itself sends nothing: what time
changes shows on the next request or update.

## Expected responses

```json
{"type": "CDS", "as": "cds-add", "resources": [{"name": "b", "connectTimeout": "1s"}], "removed": ["a"]}
```

| Field | |
|---|---|
| `type` | The response's type. |
| `resources` | On SotW, the whole set; on delta, what it adds or changes. Omitted: none. |
| `removed` | Delta only: `removed_resources`. Omitted: none. |
| `sotw`, `delta` | Optional, each `{"resources": [...], "removed": [...]}`: replaces the fields it has, on that protocol. |
| `as` | Optional: a label for this response, unique in the vector. |
| `envoy` | Which Envoy gets it: needed only when there are several and the step doesn't say. A request's responses go to its Envoy. |

When the server ends an Envoy's stream, the step expects `{"ended": "INVALID_ARGUMENT"}` there instead of a
response: the gRPC status code it ended with, `OK` if it ended cleanly. The Envoy's next request opens a new stream.

Resources are compared as a set, by name and content: the same names, and each resource equal, as a protobuf message,
to the one expected. Order doesn't count, on either protocol.
Removed names are compared as a set too. Versions and nonces aren't compared at all.

A vector that runs on both protocols says where they differ with `sotw` and `delta`:

```json
{"type": "CDS", "sotw": {"resources": [{"name": "a", "connectTimeout": "1s"}]}, "delta": {"removed": ["b"]}}
```

### Labels, not versions

Ports format versions and nonces differently, on purpose, so vectors never contain them. A request answers a response
by reference: `"last"` for the last one of its type on the Envoy's stream, `"last-1"` for the one before it, `"last-2"`
for the one before that, and so on, or a label given with `as`, so a step can answer an earlier response, or one of a
type other than the last it got. Labels are lower case words joined by `-`, other than `last` and `last-<n>`.

An answer to an earlier response is how Envoy's ACK of one response arrives after the next has been sent: Envoy sent
it before the next reached it. The port has moved on, so its nonce is stale (SPEC §4.8).

## Resources

Resources are protobuf JSON, listed under their type. Keep them small: a name, and just enough that a change shows,
such as a cluster's `connectTimeout`, a route's target cluster or an endpoint's address.

```json
{"name": "a", "connectTimeout": "1s"}
{"name": "r", "virtualHosts": [{"routes": [{"route": {"cluster": "a"}}]}]}
{"clusterName": "a", "endpoints": [{"lbEndpoints": [{"endpoint": {"address": {"socketAddress": {"address": "10.0.0.1"}}}}]}]}
```

No Envoy reads them, so they needn't be valid config, only clear. They must not contain `Any` fields, such as a
listener's filter configs: every port would need each nested type registered to read them.

## What runners do

A runner, for each vector and each of its protocols:

1. skips the vector if `differs` names its port;
2. makes a snapshot source whose `viewOf` maps each Envoy's name to its view, delivering updates when the runner says;
3. makes the server, with the snapshot config builder for each type in `serves`, a client config lookup that gives
   each Envoy its name as details and its options, and a backlog that merges updates (the default, in every port);
4. runs the steps, and checks each step's responses as above.

A failure says which vector, protocol and step, what each Envoy was expected to get, and what it got.

## Not yet in the format

- **Events and metrics.** Vectors see only what Envoy sees.
- **Versions in `kept`.** See the delta request rules above.
