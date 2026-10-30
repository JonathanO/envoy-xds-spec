# envoy-xds-spec

How the envoy-xds libraries behave, written once, and the tests that hold every port to it:

- [java-envoy-xds](https://github.com/JonathanO/java-envoy-xds), the reference;
- [go-envoy-xds](https://github.com/JonathanO/go-envoy-xds);
- the Rust port, to come.

Each library serves Envoy over ADS, SotW or delta, make before break: whatever a change adds is in place before
anything refers to it, and nothing is removed while anything still refers to it.

## What's here

- **The spec**: the rules every port follows. Make before break, which requests wait and for what, how a request
  that arrives mid-change is answered, what a NACK does, what a reconnecting Envoy kept and when it goes. Also the
  differences between ports that are on purpose, such as version and nonce formats.
- **Trace vectors**: scenarios as data. Each gives changes to the config, the requests an Envoy sends, and the
  responses expected back. Nonces and versions are referred to by label, so each port's own formats don't matter.
  Every port runs every vector in its own CI, through its snapshot module. [docs/VECTORS.md](docs/VECTORS.md)
  describes the format, and [vectors/](vectors) holds them.
- **A differential fuzzer**: random scenarios run through every port, with the responses compared. A mismatch is a
  bug, or a difference to add to the spec; either way, its scenario becomes a vector.
- **Conformance against real Envoy**: one harness, java-envoy-xds's envoy-xds-testing, drives a small conformance
  server that each port provides.

## Changing behaviour

A change to how the libraries behave starts here: change the spec, and add or change a vector. Each port's CI then
fails until it does the same. A change that only one port needs, or that a port can't make, is a difference: list it
in the spec, with the reason.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
