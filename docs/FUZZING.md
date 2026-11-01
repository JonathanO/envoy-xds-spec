# The differential fuzzer

[fuzz/](../fuzz) generates random trace vectors, runs each through every port's replay mode
([REPLAY.md](REPLAY.md)), and compares what each port sent. The ports should agree on every vector, unless SPEC §8
lists the difference, and none of those differences show in a replay. So a mismatch is a bug in one port, or a rule
the spec doesn't pin down yet.

## Running it

It's a Gradle build that shares `conformance/`'s wrapper and version catalog, and needs JDK 22 or later:

```sh
cd fuzz
../conformance/gradlew fuzz -Pports=java=/path/to/java/replay,go=/path/to/go/replay -PfuzzCases=10000
```

- `-Pports` names each port's replay command, as `name=path`, comma separated. The first is the one the others are
  compared with.
  - java-envoy-xds: `./gradlew :envoy-xds-spec-tests:replayScript` writes `envoy-xds-spec-tests/build/replay`.
  - go-envoy-xds: `spectest/replay` builds its test binary and runs it.
- `-PfuzzCases` is how many vectors to try; by default 1000. Each takes a few milliseconds.

Each port's stderr goes to `build/fuzz/<name>.stderr`.

## A mismatch

The fuzzer uses [Hegel](https://hegel.dev), which shrinks a failing vector to a small one that still fails. The
failure shows that vector, the step where the ports differ, and what each sent. The vector is also written to
`build/fuzz/fuzz-mismatch.json`.

To fix one:

1. Decide which port is right, from SPEC.md. If the spec doesn't say, decide what it should say, and add the rule.
2. Make the vector a real one: name it after the rule, describe it, add each step's `expect`, and put it in
   [vectors/](../vectors), linked from the rule. Then every port runs it in CI.
3. Fix the port that's wrong.

## What it generates

Vectors with one or two Envoys, on one protocol, over a few names (`a`, `b`, `c` and `*`), three groups and two
views, with steps chosen at random: updates (views, group edits and replacements, removals, phases), requests
(subscribing, unsubscribing, ACKing or NACKing the last response or the one before it, on delta what a reconnecting
Envoy kept, and now and then a type URL no port serves), time passing, resyncs and disconnects. Each resource is one
of two variants, so a put may change it or not. Hegel steers towards vectors that make the ports send more.

