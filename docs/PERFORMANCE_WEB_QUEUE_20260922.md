# Web board notification coalescing

The collector previously scheduled every immediate analysis notification independently. If its
executor was busy, notifications could accumulate before the first broadcast advanced the rate
limit timestamp. Full-state notifications used a separate pending flag, and serializing an old
position could finish after navigation.

`WebBoardUpdateQueue` now keeps at most one scheduled notification, or one running notification
with dirty flags for a follow-up. A full state promotes a delayed analysis timer, includes the
latest analysis, and consumes both pending flags. Analysis broadcasts remain at most 10 per
second using monotonic time. Cancelled timers are removed from the executor queue. The executor's
ordinary and delayed control tasks retain their existing behavior and ordering.

Each board notification advances a generation. The collector builds the existing JSON messages
and checks that generation and the displayed node before sending them. Navigation detected before
its notification schedules a full-state refresh. Closing invalidates pending and in-flight
notifications before stopping the executor. No engine analysis frequency or WebSocket JSON fields
change.

## Slow clients

Java-WebSocket 1.6.0 uses an unbounded outgoing buffer for each connection. Coalescing the
collector's tasks alone does not bound that buffer. `WebBoardClientUpdates` therefore keeps
three latest-message slots per client: full board, history, and analysis. A full board clears
unsent analysis and history for the previous board. A buffered socket receives no additional
state frames until it drains; one 25 ms retry task services all pending clients so the final
state is delivered even when no new notification arrives. Writable clients continue normally.

New connections receive the cached full board followed by the latest history and analysis.
Disconnect and server stop release pending messages and cancel the retry. Generic broadcasts
and targeted replies, including trial/control events, still use their existing delivery path;
they are not coalesced or dropped. The queue bound applies to board/analysis/history state,
not an arbitrary stream of control traffic. No protocol fields or client changes are needed.

## Verification

Windows, JDK 21.0.12+8, Maven 3.9.10, headless focused suite:

```text
mvn -Djava.awt.headless=true -Dtest=Web*Test test
Tests run: 81, Failures: 0, Errors: 0, Skipped: 0
```

Twenty new tests exercise 50,000-notification bursts while the executor or broadcaster is blocked;
one pending notification instead of one per immediate request; latest-value delivery; full-state
promotion of a 60-second delayed timer; removal of cancelled timers; 100 control tasks in order;
100 ms minimum analysis spacing; navigation during analysis and full-state serialization;
display-node changes before their notification; executor rejection and shutdown. Client sink
tests repeatedly replace 10,000 states while a second writable client receives updates, then
verify delivery of only the latest pending board, history, and analysis. They also cover
disconnect, close, and cache invalidation when the board changes.

A real loopback WebSocket test pauses its TCP reader and reduces that test connection's send
buffer. It sends 128 full-state updates carrying 64 KiB payloads, verifies network backpressure
actually occurred, and checks Java-WebSocket's outgoing queue never contains more than one
state frame. After the reader resumes, the final board arrives before its final analysis without
another notification. Other loopback tests verify replay on connect and all 100 trial/control
messages arriving in order during state updates. Existing Web trial single-stream tests pass.

This establishes bounded state queues and delivery behavior, not an engine throughput gain.
