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

## Verification

Windows, JDK 21.0.12+8, Maven 3.9.10, headless focused suite:

```text
mvn -Djava.awt.headless=true \
  -Dtest=WebBoardUpdateQueueTest,WebBoardNotificationTest,WebBoardDataCollectorTest,WebBoardManagerTest,WebBoardServerTest test
Tests run: 58, Failures: 0, Errors: 0, Skipped: 0
```

Ten new tests exercise 50,000-notification bursts while the executor or broadcaster is blocked;
one pending notification instead of one per immediate request; latest-value delivery; full-state
promotion of a 60-second delayed timer; removal of cancelled timers; 100 control tasks in order;
100 ms minimum analysis spacing; navigation during analysis and full-state serialization;
display-node changes before their notification; executor rejection and shutdown.

This establishes notification queue bounds and delivery behavior, not an engine throughput gain.
The existing Java-WebSocket 1.6.0 per-connection outgoing buffer is a separate network queue;
its slow-client buffering policy is not changed by this collector optimization.
