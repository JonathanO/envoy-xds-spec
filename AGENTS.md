# Agents

This repo holds how the envoy-xds libraries behave: the spec, the trace vectors every port runs, and the tooling that
checks ports against each other.

- The spec is the source of truth for behaviour. When a port and the spec disagree, the port is wrong, unless the spec
  lists the difference.
- A behaviour change needs a vector that fails without it. Write the vector here, then make each port pass it, or list
  why a port differs.
- Vectors describe what Envoy sees, never a port's internals: config changes in, requests in, responses out. Refer to
  nonces and versions by label.
- Shared tooling (the reference runner, the fuzzer, the conformance harness) lives with the reference implementation,
  in Java. Each port provides a vector runner and a conformance server.
