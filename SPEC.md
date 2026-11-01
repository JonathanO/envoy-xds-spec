# The envoy-xds spec

This is how every envoy-xds port behaves, as an Envoy sees it: which responses it gets, in what order, and when. It
says nothing about how a port is built. The [trace vectors](vectors) are its executable examples, and
[docs/VECTORS.md](docs/VECTORS.md) describes their format. Rules that rest on what Envoy does also link the
[conformance](docs/CONFORMANCE.md) scenarios that check them against a real Envoy.

Each rule has a number, so a vector, a pull request or a test can cite it, as "SPEC §4.2". When a port and this spec
disagree, the port is wrong, unless [§8](#8-differences) lists the difference.

## 1. Scope and terms

### 1.1 Scope

A port is the server side of Envoy's Aggregated Discovery Service. It takes the config each Envoy should have, and
sends it to that Envoy in a safe order. It is not a control plane: deciding what the config is belongs to the user.

The rules here hold for the snapshot model ([§1.3](#13-groups-views-and-changes)), which every port provides and every
vector uses. A port that lets users write their own config builders follows the same rules for the rounds those
builders return.

### 1.2 Terms

- **ADS** (Aggregated Discovery Service): one gRPC stream per Envoy that carries every resource type.
- **Stream**: one ADS connection from one Envoy. Everything in this spec is per stream, unless it says otherwise.
- **Resource**: one piece of Envoy config, such as a cluster, with a name.
- **Type**: a kind of resource. There are eight, named by their discovery service:

  | Type | Resource | Wildcard |
  |---|---|---|
  | LDS | listener | yes |
  | RDS | route configuration | no |
  | SRDS | scoped route configuration | yes |
  | VHDS | virtual host | yes |
  | CDS | cluster | yes |
  | EDS | cluster load assignment (a cluster's endpoints) | no |
  | SDS | secret | no |
  | RTDS | runtime layer | no |

  A **wildcard** type is one Envoy may subscribe to all of at once, rather than name by name ([§4.9](#49-wildcards)).
- **Served type**: a type the port has config for. A type that isn't served is still answered, always with nothing
  ([§4.10](#410-types-not-served-unknown-types-and-bad-requests)).
- **SotW** (state of the world): the protocol where each response for a type carries every resource of that type the
  Envoy subscribed to.
- **Delta**: the protocol where each response carries only the resources added or changed, and the names removed.
- **Request**: a message from Envoy. It may change what Envoy subscribes to, and it may answer a response.
- **Subscription**: a request that adds names Envoy wants, or starts a wildcard subscription.
- **ACK** and **NACK**: a request that answers a response. A NACK has `error_detail`: Envoy rejected the response. An
  ACK accepts it. [§7](#7-versions-and-nonces) says how a port tells which response a request answers.
- **Awaiting an ACK**: a type has sent a response that Envoy hasn't yet answered.
- **Answer**: a response to a subscription.
- **Change**: one update to the config, as one Envoy sees it. A port sends a change in two rounds.
- **Round**: the part of a change sent one type at a time in one order. The **add round** goes in add order, and the
  **remove round** in remove order.
- **Add order**: SDS, RTDS, CDS, EDS, LDS, RDS, SRDS, VHDS.
- **Remove order**: LDS, SRDS, RDS, VHDS, CDS, EDS, RTDS, SDS.
- **Phase**: part of a change that must be sent, and ACKed, before the rest ([§2.3](#23-phases)).
- **Held request**: a request a port has put aside to handle later ([§4.3](#43-route-requests-wait-for-clusters)).
- **Freezing**: what a NACK does to a stream: it gets no more changes ([§5](#5-nacks-and-freezing)).

### 1.3 Groups, views and changes

In the snapshot model, the user publishes **groups** and **views**. A group is a named set of resources, of any types.
A view is a named, ordered list of groups. Each Envoy sees one view: a port picks it from the Envoy's details when its
stream starts. The Envoy gets every resource in its view's groups. Where two groups have a resource of the same type
and name, the first group's wins.

The user changes groups and views in **updates**. An update may have several phases. For each Envoy, an update is a
change from what its view was to what it is now: what's new, what's different, and what's gone.

## 2. Make before break

Make before break means: whatever a change adds is in place before anything refers to it, and nothing is removed
while anything still refers to it. A route to a new cluster goes only once Envoy has ACKed the cluster, and a cluster
goes only once Envoy has ACKed the routes that stopped using it.

### 2.1 Two rounds, a type at a time

A change goes in two rounds: first the add round, in add order, then the remove round, in remove order. In each round,
a type sends its response, and the next type waits until Envoy has ACKed it. A type with nothing to send in a round
sends nothing, and the next type goes at once. So does a type Envoy has never asked for. The change is complete once
the last type of the remove round is done.

These orders put what is referred to before what refers to it on the way in, and after it on the way out: clusters
and their endpoints before the listeners and routes that use them, and the reverse.

Example: [change-goes-out-in-add-order-then-remove-order](vectors/change-goes-out-in-add-order-then-remove-order.json).

Example: [routes-to-a-new-cluster-wait-for-its-ack](vectors/routes-to-a-new-cluster-wait-for-its-ack.json).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries):
`addsAndRemovesServices`, `movesRouteToNewCluster`, `addsAndRemovesListener`, `scopedRoutes`.

### 2.2 What goes in each round

- The add round sends what the change adds or changes: each resource Envoy subscribes to that is in the view after
  the change and differs from the one before.
- The remove round sends what the change removes: each name Envoy subscribes to that was in the view before the change
  and isn't after.

On delta, a round's response carries just that: the resources added or changed, or the names removed. On SotW, a
round's response is the type's whole set, with the round applied.

Every SotW response, a round's or an answer's, lists its resources sorted by name, so the same set always makes the
same response. A delta response may list its resources in any order.

Example: [cluster-goes-once-the-route-stops-using-it](vectors/cluster-goes-once-the-route-stops-using-it.json).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries):
`changesEndpoints`.

### 2.3 Phases

Make before break orders types, not resources within a type. Some changes need an order it can't see. Say a route
stops using a subset of a cluster, and the cluster drops the subset: both are changes, so both go in the add round,
clusters first, and the route briefly uses a subset that's gone.

So an update can be split into phases. An Envoy is sent the whole of one phase, both rounds, and ACKs it, before it is
sent anything of the next. Each phase is make before break within itself. Only an Envoy whose view changes in a later
phase waits for an earlier one. An Envoy whose stream starts during an update gets the end result.

An update has no empty phases: starting a new phase while the current one has no edits starts nothing.

Example: [each-phase-is-acked-before-the-next](vectors/each-phase-is-acked-before-the-next.json).

### 2.4 Which Envoys get a change

An update reaches only the Envoys whose view it changes. An Envoy whose view has no group the update changed gets
nothing. Groups and views changed in one update reach each Envoy as one change, so a cluster added in one group arrives
before the route to it in another. A group made, or removed, in the same update as the view change that adds or drops
it, reaches or leaves that view's Envoys in that change. An Envoy whose view doesn't exist gets nothing until it does.

Example: [only-envoys-whose-view-changes-get-the-change](vectors/only-envoys-whose-view-changes-get-the-change.json).

Example: [group-created-or-removed-with-its-view-change](vectors/group-created-or-removed-with-its-view-change.json).

## 3. When changes start

### 3.1 What a change waits for

A change starts only when all of these hold:

- no other change is under way;
- no type is awaiting an ACK of an answer ([§4.1](#41-answers-come-from-current-state)) or of a stale removal
  ([§6.1](#61-stale-removals));
- no request is held ([§4.3](#43-route-requests-wait-for-clusters));
- the stream isn't frozen ([§5](#5-nacks-and-freezing));
- `delayUpdatesUntilAckOf` doesn't hold it back ([§3.2](#32-delayupdatesuntilackof)).

A port checks whenever something happens on the stream: a request, an update, or the end of a change. Before it
starts a change, it sends any stale removals that may go ([§6.1](#61-stale-removals)), and if it sent one, the change
waits for its ACK.

A change waits for held requests because a type that hasn't had its first request is passed over (§2.1), though Envoy
may have some of it.

Example: [change-waits-for-held-requests](vectors/change-waits-for-held-requests.json).

### 3.2 `delayUpdatesUntilAckOf`

A per-Envoy option, naming one type. Changes to that Envoy wait until it first ACKs an answer of that type. Answers,
and stale removals, don't wait. It works around Envoy problems with updates during its start up, such as
[envoy#16035](https://github.com/envoyproxy/envoy/issues/16035), where endpoint updates interfere with Envoy
initialising. RDS is the usual choice, as Envoy ACKs routes only once its clusters are ready.

If `subscribesTo` ([§6.2](#62-subscribesto)) leaves the type out, the option is ignored, and the port logs a warning.
Otherwise, if 30 seconds pass without that ACK, changes go anyway ([§6.3](#63-the-30-second-give-up)).

### 3.3 Waiting updates merge

Updates that arrive while a change can't start wait for it. By default, in every port, waiting updates merge into one
change, so a busy Envoy gets one change, not a queue of them, each needing its own round trips. Updates merge in the
order they were published: the merged change goes from the oldest one's before to the newest one's after.

Updates never merge across phases. An update that ends a phase takes no later update into it; the next phase waits
behind it, and is sent only once it is complete.

A port sends whatever an update makes, even if it is the same as what the stream has. Leaving out updates that change
nothing is the config source's job, which the snapshot model does: an update that changes no Envoy's view reaches no
Envoy (§2.4).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries): `churns`.

## 4. Requests

### 4.1 Answers come from current state

A port answers a subscription at once, unless the request is held (§4.3). It answers from the stream's current state:
the latest update it has started sending, or the config as it was at the stream's first request. An update still waiting
(§3.3) isn't in answers.

- On delta, an answer carries the newly subscribed resources. It names in `removed_resources` each newly subscribed
  name that doesn't exist, so Envoy stops waiting for it.
- On SotW, an answer is the type's whole set, with the new names added.

An answer isn't part of a change. A change under way carries on while an answer awaits its ACK. But no new change
starts until every answer is ACKed (§3.1).

Example: [route-requests-wait-for-the-clusters-ack](vectors/route-requests-wait-for-the-clusters-ack.json).

### 4.2 Answers mid-change

From when a change starts until a type's remove round has gone, that type's answers come from the change's mid-way
state: what the change adds, and what it is yet to remove. So nothing goes before what may still refer to it. After
the type's remove round, or with no change under way, answers come from the state once the change is done.

An answer during a change never makes the change send the same thing twice:

- On SotW, if the type's add round is still to come, the answer applies it, and the add round then sends nothing more.
- On delta, the type's add round leaves out a resource an answer has already sent during this change, if the answer
  sent it as the add round would. Ports compare the resources' content, not just their names.

The remove round always goes in its turn, on both protocols.

Example: [delta-subscription-before-its-remove-round-gets-what-the-round-removes](vectors/delta-subscription-before-its-remove-round-gets-what-the-round-removes.json).

Example: [sotw-subscription-before-its-remove-round-keeps-what-the-round-removes](vectors/sotw-subscription-before-its-remove-round-keeps-what-the-round-removes.json).

Example: [sotw-first-wildcard-request-keeps-what-its-remove-round-removes](vectors/sotw-first-wildcard-request-keeps-what-its-remove-round-removes.json).

Example: [routes-wait-for-the-clusters-of-a-change-not-sent-yet](vectors/routes-wait-for-the-clusters-of-a-change-not-sent-yet.json).

### 4.3 Route requests wait for clusters

LDS, RDS, SRDS and VHDS may refer to clusters. A request of one of these types is held while clusters are pending:

- CDS or EDS is awaiting an ACK; or
- a change is under way and its EDS add round hasn't gone yet. The EDS add round comes after the CDS one, so this
  covers a change whose clusters haven't gone yet either.

When Envoy reconnects, it subscribes to everything at once. Without this rule its routes could arrive before the
clusters they use.

Held requests are handled in the order they came, as soon as clusters are no longer pending. The exceptions:

- A request of a type that is itself awaiting an ACK isn't held, so the ACK a change waits for is never held.
- CDS and EDS requests are never held. Envoy holds its CDS ACK until new clusters have warmed, which needs the EDS
  answer.

Example: [route-requests-wait-for-the-clusters-ack](vectors/route-requests-wait-for-the-clusters-ack.json).

Example: [routes-wait-for-the-clusters-of-a-change-not-sent-yet](vectors/routes-wait-for-the-clusters-of-a-change-not-sent-yet.json).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries): `reconnects`,
`controlPlaneRestarts`, `changesWhileDisconnected`.

### 4.4 A request never overtakes a held one of its type

If a request of a type is held, every later request of that type is held behind it, whatever else holds, even an ACK.
Each request of a type follows on from the one before: on delta, its subscribe and unsubscribe lists change the last
set; on SotW, its nonce must be the latest. Handling them out of order would get the subscriptions wrong.

### 4.5 A held NACK freezes at once

A NACK is held like any other request of its type. But it freezes the stream (§5.1) as soon as it arrives. Only the
rest of it, such as its subscription changes, waits its turn.

### 4.6 Every subscription is answered

A port answers every subscription, even with nothing. Envoy waits for an answer to its first request of each type, as
for CDS when there are no clusters yet, and it needs to hear that names it asked for don't exist. On delta, those
names are in `removed_resources`; on SotW, they are left out of the set.

A delta request that subscribes to names Envoy is already subscribed to is answered again, with those names.

Example: [change-goes-out-in-add-order-then-remove-order](vectors/change-goes-out-in-add-order-then-remove-order.json).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries): `startsEmpty`,
`onDemandClusters`, `onDemandScopes`.

### 4.7 Unsubscribing gets no answer

A request that only unsubscribes is not answered: Envoy drops what it unsubscribed from itself, and doesn't wait. An
empty answer would only hold up changes until Envoy ACKed it. On SotW, a request whose list only drops names is not
answered, and later responses of the type leave those names out.

A SotW request whose list is the same as the last one changes nothing, so is not answered either.

### 4.8 Stale nonces

Once a type has sent a response on SotW, a request of that type whose `response_nonce` isn't the latest response's is
ignored. It answers nothing, and its subscription changes are dropped: a later request lists every name anyway. Before
a type has sent anything, any nonce is accepted.

On delta, a request with an older nonce is handled: its subscription changes apply. It just doesn't answer the latest
response.

Either way, a request with `error_detail` still freezes the stream (§5.1).

Example: [sotw-ack-of-an-earlier-response-is-ignored](vectors/sotw-ack-of-an-earlier-response-is-ignored.json).

Example: [delta-ack-of-an-earlier-response-still-subscribes](vectors/delta-ack-of-an-earlier-response-still-subscribes.json).

Example: [nack-of-an-earlier-response-still-freezes](vectors/nack-of-an-earlier-response-still-freezes.json).

### 4.9 Wildcards

On SotW, each request lists every name Envoy wants:

- For a wildcard type, a list with `*` in it is a wildcard subscription. So is an empty list, until Envoy has listed a
  name for that type on this stream. After that, an empty list means no names.
- Names listed alongside `*` are kept, and apply once Envoy leaves the wildcard.
- Leaving the wildcard drops what Envoy no longer wants. It is answered only if the request adds names.
- For other types, an empty list means no names.

On delta, each request subscribes to and unsubscribes from names:

- For a wildcard type, the stream's first request of that type, if it names nothing at all, is a wildcard
  subscription.
- After that, `*` in the subscribe list starts a wildcard subscription. Its answer has everything Envoy wasn't already
  subscribed to by name, and any names the request itself subscribes to.
- `*` in the unsubscribe list ends it. Envoy stays subscribed to the names it subscribed to explicitly. This is
  answered only if the request also subscribes to names.
- Names subscribed to during a wildcard subscription are answered, like any subscription.
- For other types, `*` is a name like any other.

Example: [sotw-first-wildcard-request-keeps-what-its-remove-round-removes](vectors/sotw-first-wildcard-request-keeps-what-its-remove-round-removes.json).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries):
`onDemandVirtualHosts`.

### 4.10 Types not served, unknown types and bad requests

- A request for a type that isn't served is answered like any other, with nothing.
- A request for a type URL that isn't one of the eight types is logged and left unanswered. Ending the stream would
  take every other type down with it, and Envoy would only reconnect and ask again.
- A delta request that subscribes to and unsubscribes from the same name, other than `*` on a wildcard type, fails the
  stream with `INVALID_ARGUMENT`. The xDS spec doesn't say which should win, and either could be wrong.

Example: [unknown-type-url-is-left-unanswered](vectors/unknown-type-url-is-left-unanswered.json).

Example: [delta-subscribing-to-and-unsubscribing-from-one-name-fails-the-stream](vectors/delta-subscribing-to-and-unsubscribing-from-one-name-fails-the-stream.json).

A stream that fails ends with a gRPC status that says whose fault it was:

- `INVALID_ARGUMENT` for a bad request, as above.
- `INTERNAL`, with the message `xds: internal error`, for the port's own failures, such as a user's config builder
  throwing, even if what it throws is itself a gRPC status. The error is logged, not sent to Envoy.
- When the user's lookup of an Envoy's options (§3.2, §6.2) fails with a gRPC status, that status, as it is. Any
  other failure there is `INTERNAL`, as above.

## 5. NACKs and freezing

### 5.1 A NACK freezes the stream

Any request with `error_detail` freezes the stream, for good. That is so even if it is held, or answers an older
response. A frozen stream gets no more changes:

- what is left of the change under way is never sent;
- waiting updates, and every later update, are dropped;
- stale removals still waiting ([§6.1](#61-stale-removals)) are never sent.

The port stops taking updates for a frozen stream at once, not when the stream ends: it unsubscribes the stream from
the config source.

Envoy keeps serving its last good config, which goes stale until someone acts. A rejected change won't fix itself:
resending it, or a later change that includes it, would be rejected again. Envoy may have taken part of it, so a port
no longer knows what Envoy has, and sending changes from a guess could make things worse. Rolling back would undo
whatever part Envoy took. Closing the stream would have Envoy reconnect straight away, get the same config, and loop.

Example: [nack-freezes-the-stream](vectors/nack-freezes-the-stream.json).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries):
`containsRejectedConfig`.

### 5.2 A frozen stream still answers subscriptions

A frozen stream answers subscriptions from its state at the NACK. A type whose remove round hadn't gone when the change
stopped answers from the change's mid-way state (§4.2) for good: its removals never went out, so Envoy may still use
what they would remove. Each answer covers only the names it adds, so no more of the change goes out.

On SotW each answer is the whole set, so an answer of the type Envoy rejected repeats what it rejected. Envoy rejects
it again, and keeps what it has.

Example: [nack-freezes-the-stream](vectors/nack-freezes-the-stream.json).

### 5.3 Resync

A frozen stream stays frozen until it ends. Each port gives the user a way to close a stream cleanly. Envoy then
reconnects, and its new stream syncs from scratch with the config as it is now. Requests on the old stream after that
are ignored. Each port also offers a helper that does this a while after a NACK, waiting longer each time the same
Envoy NACKs again.

Example: [resync-ends-the-stream-and-envoy-syncs-afresh](vectors/resync-ends-the-stream-and-envoy-syncs-afresh.json).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries):
`containsRejectedConfig`.

## 6. Reconnects

Envoy keeps its config while it is disconnected. When it reconnects, it subscribes to everything at once. On delta it
also sends `initial_resource_versions`: the names, and versions, of what it kept.

### 6.1 Stale removals

On delta, a **stale** name is one Envoy kept, is subscribed to, and that the stream's current state doesn't have.
Envoy must hear that it's gone, but not before what may still refer to it. Ports compare kept names only, never
versions: a resource Envoy kept that still exists is sent again when Envoy subscribes to it.

When a subscription finds stale names of a type, its answer removes them at once if every type before it in remove
order has been answered at least once and isn't awaiting an ACK. Types that aren't served, and types `subscribesTo`
leaves out (§6.2), are skipped. Otherwise the stale names wait.

Waiting stale removals go when a port looks for a change to start (§3.1): only when nothing awaits an ACK and no
request is held. They go one type at a time, in remove order. A type's go only once they may, by the rule above, and
never before an earlier type's in remove order. Each response waits for its ACK. When they go, a type removes only
names that are still subscribed to and still missing. A change waits for the ACK of a stale removal that has gone, but
not for stale removals that may not go yet.

A waiting stale name that is sent since, as an answer or by a change, is no longer stale: Envoy has been told about it.
It is never removed as stale, even if a later change removes it, since that change sends the removal itself.

A newly subscribed name that Envoy didn't keep, and that doesn't exist, is answered as removed at once (§4.6): Envoy
has nothing to remove.

Example: [delta-reconnect-removes-what-envoy-kept-once-the-routes-are-acked](vectors/delta-reconnect-removes-what-envoy-kept-once-the-routes-are-acked.json).

Example: [delta-stale-removals-go-in-remove-order](vectors/delta-stale-removals-go-in-remove-order.json).

Example: [delta-stale-name-answered-since-is-removed-once](vectors/delta-stale-name-answered-since-is-removed-once.json).

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries): `reconnects`,
`changesWhileDisconnected`, `controlPlaneRestarts`.

### 6.2 `subscribesTo`

A per-Envoy option: the types that Envoy gets from the port. By default it is every served type. An empty list means
no type at all, not the default. Stale removals don't wait for types it leaves out, and `delayUpdatesUntilAckOf`
naming such a type is ignored (§3.2).

This matters for types only some Envoys use, such as SRDS or VHDS, or listeners for an Envoy with static ones. A port
can't tell an Envoy that won't ask for a type from one that hasn't asked yet: Envoy asks only once the listeners and
routes naming them arrive. `subscribesTo` changes only what is waited for. An Envoy that asks for a type left out still
gets it.

Example: [delta-stale-removals-go-in-remove-order](vectors/delta-stale-removals-go-in-remove-order.json).

Example: [change-waits-for-held-requests](vectors/change-waits-for-held-requests.json).

Example: [delta-empty-subscribes-to-waits-for-no-type](vectors/delta-empty-subscribes-to-waits-for-no-type.json).

### 6.3 The 30 second give-up

Stale removals, and `delayUpdatesUntilAckOf`, wait for Envoy to ask for and ACK a type it may never ask for. So they
give up after 30 seconds, counted from the stream's first request for one of the eight types.

There is no timer, so they wait at least 30 seconds, then until the next thing that happens on the stream: a request
or an update. The wait is checked only then. Once stale removals have waited too long, the port logs a warning, and
from then on no stale removal on that stream waits.
Once `delayUpdatesUntilAckOf` has waited too long, the port logs a warning, and changes go.

Example: [delta-stale-removals-give-up-waiting-after-30-seconds](vectors/delta-stale-removals-give-up-waiting-after-30-seconds.json).

Example: [delay-updates-until-ack-of-gives-up-after-30-seconds](vectors/delay-updates-until-ack-of-gives-up-after-30-seconds.json).

### 6.4 SotW

SotW has no `initial_resource_versions`, so a port can't know what a reconnecting Envoy kept. Each SotW answer is the
whole set, and replaces what Envoy has. So a cluster Envoy kept that the config no longer has goes with the first CDS
answer, even while a route Envoy kept still uses it. Prefer delta.

Seen with Envoy, in the [conformance suite](conformance/README.md#conformance-suite-for-xds-libraries): `reconnects`,
`controlPlaneRestarts`.

## 7. Versions and nonces

### 7.1 What is required

- Each response has a nonce that differs from every earlier response's nonce of its type on the stream. Nonces needn't
  differ across streams: Envoy starts each stream with no nonce, so a request never names a response from an earlier
  one. Every port's conformance run checks this, on each Envoy release it runs.
- A request answers the latest response of its type if its `response_nonce` is that response's nonce.
- On SotW, each response has a `version_info`. A request answering the latest response is an ACK only if its
  `version_info` equals that response's. With `error_detail`, it is a NACK. With neither, it answers nothing, and the
  type still awaits an ACK. The port logs it as an error.
- On delta, a request answering the latest response is an ACK, or a NACK with `error_detail`. A request with any other
  nonce, or none, answers nothing (§4.8).
- On delta, each resource in a response carries a version.

### 7.2 What is left to each port

The format of versions and nonces, and where any counter starts. Vectors refer to responses by label, never by nonce
or version, so formats don't matter to them. [§8](#8-differences) lists how the ports differ.

## 8. Differences

These are the deliberate differences between ports. Each is checked against every port's code.

### 8.1 Differences an Envoy can see

| Port | What it does | Why |
|---|---|---|
| go | Each type's version counts up from 1, on both protocols, and the nonce is the version. | xDS needs only that each nonce is new for its type (§7.1); a count does that, and is the same on every run. |
| java | SotW versions count up from 2, delta versions from 1, each type on its own; nonces are random UUIDs. | Nothing depends on the format (§7.2), so it is left as it was. |
| go, java | SotW sorts names (§2.2) by their UTF-8 bytes in go, and by UTF-16 code units in java. | Each is its language's string order. They differ only for a name with characters above U+FFFF, which Envoy config doesn't use in practice. |

### 8.2 Differences only the user can see

| Port | What it does | Why |
|---|---|---|
| go | Each stream does its work on its own goroutine; the snapshot source delivers updates into a per-stream mailbox that never blocks. | Goroutines are cheap. java delivers updates on a shared pool of threads, so thousands of Envoys don't need thousands of threads, and handles requests on gRPC's threads. |

## Not yet covered by a vector

These rules have no vector yet. This is the to-do list for new ones. Rules marked "needs the format" need something
[docs/VECTORS.md](docs/VECTORS.md) can't yet express.

- §3.1: a change waits while an answer awaits its ACK.
- §3.1: a change waits for the ACK of a stale removal that has gone, but not for stale removals that may not go yet.
- §3.2: `delayUpdatesUntilAckOf` releases changes on the first ACK of an answer of its type, doesn't hold stale
  removals, and is ignored when `subscribesTo` leaves its type out.
- §3.3: updates arriving during a change merge into one change.
- §3.3: updates never merge across phases.
- §4.1: an update still waiting isn't in answers.
- §4.4: a request never overtakes a held one of its type, even an ACK.
- §4.5: a held NACK freezes the stream at once.
- §4.6: a delta request re-subscribing to names is answered again.
- §4.7: a request that only unsubscribes gets no answer; a SotW request with an unchanged list gets none.
- §4.9: SotW `*`, an empty list after names, and leaving the wildcard; delta `*` in the subscribe and unsubscribe
  lists, and names subscribed to during a wildcard.
- §4.10: a type that isn't served is answered with nothing.
- §5.1: a NACK mid-change stops stale removals still waiting.
- §5.2: a frozen stream answers a type the change left mid-way from the mid-way state, keeping what its remove round
  would have removed.
- §6.1: a waiting stale removal skips names no longer subscribed to.
- §6.1: a resource Envoy kept that still exists is sent again.
- §6.4: a SotW Envoy loses a cluster it kept with the first CDS answer.
- §7.1: a SotW request with the latest nonce and another version, and no `error_detail`, isn't an ACK.
