# Agents

This repo holds how the envoy-xds libraries behave: the spec, the trace vectors every port runs, and the tooling that
checks ports against each other.

- The spec is the source of truth for behaviour. When a port and the spec disagree, the port is wrong, unless the spec
  lists the difference.
- A behaviour change needs a vector that fails without it. Write the vector here, then make each port pass it, or list
  why a port differs.
- A behaviour change updates [SPEC.md](SPEC.md) as well as adding a vector. Link the vector from the rule it shows,
  and take the rule off "Not yet covered by a vector" if it was there.
- Vectors describe what Envoy sees, never a port's internals: config changes in, requests in, responses out. Refer to
  nonces and versions by label.
- Shared tooling (the reference runner, the fuzzer, the conformance harness) lives with the reference implementation,
  in Java. Each port provides a vector runner and a conformance server.
- The conformance harness is a Gradle build in `conformance/`. Run `./gradlew check javadoc` there before pushing:
  `check` doesn't run javadoc, and CI fails on its warnings. A change to the control API changes every port's
  conformance server: say so in docs/CONFORMANCE.md, and in the PR.
