# Replaying vectors

Each port's trace vector runner has a replay mode: it runs vectors as [VECTORS.md](VECTORS.md) says, but prints the
responses each Envoy got instead of checking them. The differential fuzzer uses it to run the same generated vectors
through every port and compare what they send.

## Running

A port provides a command that takes no arguments. It reads vectors from stdin, one per line, each a vector as compact
JSON. For each, in order, it writes one line of JSON to stdout, and flushes it. Lines it writes that don't start with
`{`, such as a test framework's own output, are ignored. It exits when stdin closes.

## What it runs

Each vector runs as VECTORS.md says, once per protocol in its `protocols`, with these differences:

- `expect` is ignored, and so is `differs`.
- An `ack` or `nack` of `"last"` or `"last-<n>"`, when the Envoy hasn't had that many responses of that type on its
  stream, is left out: the request answers nothing. So a generated vector needn't know which responses a port sends.

A vector that is wrong in any other way, such as a `disconnect` of an Envoy with no stream, `kept` on SotW, or a label
no response has, is an error. The fuzzer never generates one.

## What it prints

```json
{"name": "a-vector", "runs": {
  "sotw": {"steps": [[], [{"envoy": "envoy", "type": "CDS", "resources": [{"name": "a"}]}]]},
  "delta": {"steps": [[], [{"envoy": "envoy", "type": "CDS", "resources": [{"name": "a"}], "removed": ["b"]}]]}
}}
```

- `name` is the vector's.
- `runs` has a run for each protocol the vector names.
- `steps` has a list for each step run: the responses the step caused. Each has the Envoy that got it, its `type`, its
  `resources` as protobuf JSON, and on delta the names it `removed`. An Envoy's responses are in the order it got them;
  the Envoys are in the order the vector lists them.
- When the server ends an Envoy's stream, that Envoy's list has `{"envoy": "envoy", "ended": "INVALID_ARGUMENT"}`: the
  gRPC status code it ended with, `OK` if it ended cleanly. The run stops after that step.
- A run of a vector that is in error has `"error"`, a message, as well as the steps run before it.

The fuzzer compares runs step by step: each Envoy's responses in order, each response's resources as a set, by name
and content, and the names it removed as a set. Each port prints protobuf JSON its own way, so it compares the
resources parsed, not their text.
