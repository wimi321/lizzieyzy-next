# Sync Diagnostics Export Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add recent diagnostics ring buffers and a one-click zip export for the existing read-only sync diagnostics panel.

**Architecture:** Keep diagnostics as a read-only side channel. `ReadBoard` and `OnlineDialog` only publish sanitized events into `SyncDiagnosticsRecorder`; `SyncDiagnosticsExporter` freezes recorder state, sanitizes export-only views, and writes a fixed zip layout. UI reads/export diagnostics but never calls sync, rebuild, session-clear, or readboard protocol behavior.

**Tech Stack:** Java 17, Swing, JDK `java.util.zip`, JUnit 5, existing Maven build.

---

## Context And Contracts

Checked before planning:

- `docs/SNAPSHOT_NODE_KIND.md`
  - Do not change `SNAPSHOT` / `MOVE` / `PASS` semantics.
  - Do not derive real `PASS` from diagnostics fields.
  - Do not alter `NO_CHANGE` / `SINGLE_MOVE_RECOVERY` / `HOLD` / `FORCE_REBUILD`.
- `docs/specs/2026-05-30-sync-diagnostics-panel-design.md`
  - MVP diagnostics are read-only and only publish immutable snapshots.
- `docs/specs/2026-05-31-sync-diagnostics-export-design.md`
  - Phase 2 adds ring buffers and zip export.
  - Export must use a sanitizer; raw Yike session keys, raw room ids, raw URLs, SGF payloads, window titles, and user path names must not enter the zip.

## Subagent Execution Model

Use subagents during implementation, but do not start all workers at once. The tasks have real dependencies, so execute in waves:

- **Wave 1 - Worker A: recorder/events**
  - Owns `SyncDiagnosticsEventBuffer`, `SyncProtocolDiagnosticEvent`, `SyncDiagnosticsExportSnapshot`, `SyncDiagnosticsEnvironment`, recorder buffer APIs, and recorder/model tests.
  - This worker must finish before exporter/UI workers start, because every later task depends on the export snapshot and recorder APIs.
- **Wave 2 - Worker B: export/sanitizer**
  - Owns `SyncDiagnosticsJson`, `SyncDiagnosticsExportSanitizer`, `SyncDiagnosticsExporter`, default output directory tests, deny-by-default path tests, and exporter/json tests.
  - Starts only after Worker A has landed model/recorder APIs.
- **Wave 3 - Worker C: integration/UI**
  - Owns `ReadBoard` protocol event publishing, `SyncDiagnosticsDialog`, l10n keys, and resource/UI tests.
  - Starts only after Worker A has recorder APIs. The UI export button portion starts only after Worker B has landed `SyncDiagnosticsExporter`.
- **Reviewer subagent**
  - Reviews the integrated diff for read-only boundary, privacy, and regression risk after workers return.

Main agent integrates, resolves overlap in `SyncDiagnosticsRecorder`, runs final test/build commands, and keeps commits scoped.

## File Structure

### New Files

| File | Responsibility |
| --- | --- |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsEventBuffer.java` | Small synchronized fixed-capacity buffer with immutable snapshots. |
| `src/main/java/featurecat/lizzie/analysis/SyncProtocolDiagnosticEvent.java` | Immutable sanitized readboard protocol event. |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExportSnapshot.java` | Frozen recorder state for export. |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsEnvironment.java` | Environment summary plus path redaction helpers. |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsJson.java` | Fixed-field JSON string writer and JSONL helpers. |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExportSanitizer.java` | Export-only sanitizer for session aliases and paths. |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExporter.java` | Writes the fixed zip package. |
| `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsEventBufferTest.java` | Buffer capacity and snapshot isolation tests. |
| `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsJsonTest.java` | JSON escaping tests. |
| `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsExporterTest.java` | Zip layout and privacy tests. |

### Modified Files

| File | Responsibility |
| --- | --- |
| `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorder.java` | Store recent protocol, decision, and Yike event buffers; expose `exportSnapshot()`. |
| `src/main/java/featurecat/lizzie/analysis/ReadBoard.java` | Publish sanitized protocol events from the existing `recordProtocolLine(...)`. |
| `src/main/java/featurecat/lizzie/gui/SyncDiagnosticsDialog.java` | Add export button and success/failure status. |
| `src/main/resources/l10n/DisplayStrings.properties` | English export UI strings. |
| `src/main/resources/l10n/DisplayStrings_zh_CN.properties` | Chinese export UI strings. |
| `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorderTest.java` | Recorder export snapshot and recent buffer tests. |
| `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java` | Protocol ring buffer redaction regression. |
| `src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java` | Update default recorder reset helper so Yike event buffers do not leak between tests. |
| `src/test/java/featurecat/lizzie/gui/SyncDiagnosticsResourceTest.java` | Export button/resource string coverage. |

Do not modify `SyncRemoteContext` unless implementation reveals a compile-time need. `remoteContextFingerprint` must remain the current safe enum/boolean/move-number summary; do not replace it with raw token, raw room id, or low-cost hash.

## Task 1: Add Event Buffer And Export Snapshot Models

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsEventBuffer.java`
- Create: `src/main/java/featurecat/lizzie/analysis/SyncProtocolDiagnosticEvent.java`
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExportSnapshot.java`
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsEnvironment.java`
- Test: `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsEventBufferTest.java`

- [ ] **Step 1: Write failing buffer tests**

Add tests:

```java
@Test
void keepsOnlyNewestValuesWhenCapacityIsExceeded() {
  SyncDiagnosticsEventBuffer<String> buffer = new SyncDiagnosticsEventBuffer<>(3);
  buffer.add("one");
  buffer.add("two");
  buffer.add("three");
  buffer.add("four");

  assertEquals(List.of("two", "three", "four"), buffer.snapshot());
}

@Test
void snapshotCannotMutateInternalState() {
  SyncDiagnosticsEventBuffer<String> buffer = new SyncDiagnosticsEventBuffer<>(2);
  buffer.add("one");

  List<String> snapshot = buffer.snapshot();
  assertThrows(UnsupportedOperationException.class, () -> snapshot.add("two"));
  assertEquals(List.of("one"), buffer.snapshot());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -Dtest=SyncDiagnosticsEventBufferTest test
```

Expected: FAIL because `SyncDiagnosticsEventBuffer` does not exist.

- [ ] **Step 3: Implement minimal buffer**

Create `SyncDiagnosticsEventBuffer<T>`:

```java
final class SyncDiagnosticsEventBuffer<T> {
  private final int capacity;
  private final Deque<T> values = new ArrayDeque<>();

  SyncDiagnosticsEventBuffer(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
  }

  synchronized void add(T value) {
    if (value == null) {
      return;
    }
    if (values.size() == capacity) {
      values.removeFirst();
    }
    values.addLast(value);
  }

  synchronized List<T> snapshot() {
    return Collections.unmodifiableList(new ArrayList<>(values));
  }
}
```

Keep it package-private unless tests require public access.

- [ ] **Step 4: Add immutable event/model values**

Implement:

- `SyncProtocolDiagnosticEvent`
  - fields: `timestampMillis`, `summary`, `source`
  - static `of(long timestampMillis, String summary, String source)`
  - normalize blank `summary` to `none`, blank `source` to `unknown`
- `SyncDiagnosticsEnvironment`
  - fields from spec
  - static `capture()` using `System.getProperty(...)`
  - static path sanitizer used by `capture()` and tests
  - path sanitizer must cover known user-path forms and deny unknown absolute paths
- `SyncDiagnosticsExportSnapshot`
  - fields: `capturedAtMillis`, `SyncDiagnosticsReport report`, immutable lists for protocol/decision/yike events, `SyncDiagnosticsEnvironment environment`

- [ ] **Step 5: Run focused model tests**

Run:

```bash
mvn -Dtest=SyncDiagnosticsEventBufferTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 1**

```bash
git add src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsEventBuffer.java \
  src/main/java/featurecat/lizzie/analysis/SyncProtocolDiagnosticEvent.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExportSnapshot.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsEnvironment.java \
  src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsEventBufferTest.java
git commit -m "feat(sync): add diagnostics export snapshot models"
```

## Task 2: Extend Recorder With Recent Buffers

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorder.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorderTest.java`
- Later integration modifies reset helpers in:
  - `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
  - `src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java`

- [ ] **Step 1: Write failing recorder export tests**

Extend `SyncDiagnosticsRecorderTest`:

```java
@Test
void exportSnapshotIncludesRecentProtocolDecisionAndYikeEvents() {
  SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
  SyncDecisionTrace trace =
      SyncDecisionTrace.builder("HOLD", "conflict_hold").timestampMillis(100L).build();
  YikeSessionDiagnosticsSnapshot yike =
      YikeSessionDiagnosticsSnapshot.builder()
          .currentSessionKey("live-room:186538")
          .summary("session event")
          .build();

  recorder.recordProtocolEvent(SyncProtocolDiagnosticEvent.of(10L, "syncPlatform yike", "test"));
  recorder.updateLatestDecision(trace);
  recorder.updateYikeSession(yike);

  SyncDiagnosticsExportSnapshot snapshot = recorder.exportSnapshot();

  assertEquals(1, snapshot.getRecentProtocolEvents().size());
  assertSame(trace, snapshot.getRecentDecisionTraces().get(0));
  assertSame(yike, snapshot.getRecentYikeEvents().get(0));
}
```

Add a capacity test by recording more than the declared decision buffer capacity and asserting only newest values remain. Keep capacities package-visible constants in recorder so the test does not duplicate magic numbers.

Add a test-isolation test:

```java
@Test
void clearForTestsClearsLatestAndRecentBuffers() {
  SyncDiagnosticsRecorder recorder = new SyncDiagnosticsRecorder();
  recorder.recordProtocolEvent(SyncProtocolDiagnosticEvent.of(10L, "sync", "test"));
  recorder.updateLatestDecision(SyncDecisionTrace.builder("HOLD", "conflict_hold").build());
  recorder.updateYikeSession(YikeSessionDiagnosticsSnapshot.builder().currentSessionKey("live-room:1").build());

  recorder.clearForTests();

  SyncDiagnosticsExportSnapshot export = recorder.exportSnapshot();
  assertTrue(export.getRecentProtocolEvents().isEmpty());
  assertTrue(export.getRecentDecisionTraces().isEmpty());
  assertTrue(export.getRecentYikeEvents().isEmpty());
  assertTrue(recorder.snapshot().getLatestDecisionTrace().isEmpty());
}
```

Add a singleton reset test:

```java
@Test
void clearDefaultForTestsClearsSingletonBuffersAcrossPackages() {
  SyncDiagnosticsRecorder recorder = SyncDiagnosticsRecorder.getDefault();
  recorder.recordProtocolEvent(SyncProtocolDiagnosticEvent.of(10L, "sync", "test"));

  SyncDiagnosticsRecorder.clearDefaultForTests();

  assertTrue(SyncDiagnosticsRecorder.getDefault().exportSnapshot().getRecentProtocolEvents().isEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -Dtest=SyncDiagnosticsRecorderTest test
```

Expected: FAIL because recorder has no recent buffer/export APIs.

- [ ] **Step 3: Implement recorder buffers**

Modify `SyncDiagnosticsRecorder`:

```java
static final int PROTOCOL_EVENT_CAPACITY = 100;
static final int DECISION_TRACE_CAPACITY = 50;
static final int YIKE_EVENT_CAPACITY = 50;

private final SyncDiagnosticsEventBuffer<SyncProtocolDiagnosticEvent> protocolEvents =
    new SyncDiagnosticsEventBuffer<>(PROTOCOL_EVENT_CAPACITY);
private final SyncDiagnosticsEventBuffer<SyncDecisionTrace> decisionTraces =
    new SyncDiagnosticsEventBuffer<>(DECISION_TRACE_CAPACITY);
private final SyncDiagnosticsEventBuffer<YikeSessionDiagnosticsSnapshot> yikeEvents =
    new SyncDiagnosticsEventBuffer<>(YIKE_EVENT_CAPACITY);
```

Add:

```java
public void recordProtocolEvent(SyncProtocolDiagnosticEvent event) { ... }

public SyncDiagnosticsExportSnapshot exportSnapshot() { ... }

public void clearForTests() { ... }

public static void clearDefaultForTests() { ... }
```

Update:

- `updateLatestDecision(...)` writes `latestDecision` and adds non-empty trace to `decisionTraces`.
- `updateYikeSession(...)` writes `yike` and adds non-empty snapshot to `yikeEvents`.

Do not let recorder write files or call sync behavior.

`clearForTests()` and `clearDefaultForTests()` are test-only APIs. They must be public because existing tests live in both `featurecat.lizzie.analysis` and `featurecat.lizzie.gui` packages. They reset `sync`, `yike`, `latestDecision`, and all recent buffers.

- [ ] **Step 4: Run recorder tests**

Run:

```bash
mvn -Dtest=SyncDiagnosticsRecorderTest test
```

Expected: PASS.

- [ ] **Step 5: Update existing singleton recorder reset helpers**

Update existing test reset helpers:

- In `ReadBoardSyncDecisionTest.resetDefaultDiagnosticsRecorder()`, call `SyncDiagnosticsRecorder.clearDefaultForTests()` instead of only null-updating latest snapshots.
- In `OnlineDialogYikeSessionStateTest` setup/teardown, call `SyncDiagnosticsRecorder.clearDefaultForTests()` so Yike ring buffer state cannot leak between tests across package boundaries.

Run:

```bash
mvn -Dtest=SyncDiagnosticsRecorderTest,ReadBoardSyncDecisionTest,OnlineDialogYikeSessionStateTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorder.java \
  src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsRecorderTest.java \
  src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java \
  src/test/java/featurecat/lizzie/gui/OnlineDialogYikeSessionStateTest.java
git commit -m "feat(sync): retain recent diagnostics events"
```

## Task 3: Add JSON Writer, Sanitizer, And Exporter

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsJson.java`
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExportSanitizer.java`
- Create: `src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExporter.java`
- Test: `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsJsonTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsExporterTest.java`

- [ ] **Step 1: Write failing JSON escaping tests**

Add:

```java
@Test
void escapesJsonStrings() {
  assertEquals("\"a\\\\b\\\"c\\n\"", SyncDiagnosticsJson.quote("a\\b\"c\n"));
}
```

Also test control character escaping for a low ASCII character.

- [ ] **Step 2: Write failing exporter zip layout test**

Use a temp directory:

```java
@TempDir Path tempDir;

@Test
void writesFixedZipEntries() throws Exception {
  SyncDiagnosticsExportSnapshot snapshot = minimalExportSnapshotWithSensitiveValues();
  Path zip = new SyncDiagnosticsExporter(tempDir).export(snapshot);

  assertZipEntries(
      zip,
      "summary.txt",
      "sync-context.json",
      "yike-session.json",
      "recent-decisions.jsonl",
      "readboard-protocol.log",
      "yike-events.jsonl",
      "environment.txt");
}
```

- [ ] **Step 3: Write failing whole-zip privacy test**

Construct values containing:

- SGF: `(;GM[1]SZ[19])`
- token: `secret-room-token`
- Yike session: `live-room:186538`
- raw URL: `https://www.yikeweiqi.com/live/186538`
- window title: `User Secret Room Title`
- paths:
  - `C:\Users\alice\Lizzie`
  - `/mnt/c/Users/alice/Lizzie`
  - `\\wsl.localhost\Ubuntu\home\alice\dev`
  - `/Users/alice/Lizzie`
  - unknown absolute Windows path: `C:\secret\file.zip`
  - unknown absolute POSIX path: `/var/tmp/alice/lizzie`
  - relative parent path: `relative/parent/file.sgf`

Unzip every text entry and assert none of these raw strings appears. Because JSON escaping can hide raw path leakage, also assert all text entries do not contain:

- `alice`
- `Users\\\\alice`
- `Users\\\\\\\\alice`
- `home\\\\alice`
- `home\\\\\\\\alice`
- `/Users/alice`
- `/home/alice`
- `/mnt/c/Users/alice`

Assert aliases like `live-room#1` can appear. Also assert known user paths contain `<user>`, unknown absolute paths produce `<redacted-path>`, and only `file.sgf` can appear for the relative path.

- [ ] **Step 4: Write failing default output directory test**

Temporarily set `user.dir` and `user.home`, then restore both in `finally`:

```java
String originalHome = System.getProperty("user.home");
String originalUserDir = System.getProperty("user.dir");
Path appDir = tempDir.resolve("lizzieyzy-next");
try {
  Files.createDirectories(appDir);
  Files.createFile(appDir.resolve("pom.xml"));
  System.setProperty("user.home", tempDir.resolve("home").toString());
  System.setProperty("user.dir", appDir.toString());
  assertEquals(
      appDir.resolve("sync-diagnostics"),
      SyncDiagnosticsExporter.defaultOutputDirectory());
} finally {
  if (originalHome == null) {
    System.clearProperty("user.home");
  } else {
    System.setProperty("user.home", originalHome);
  }
  if (originalUserDir == null) {
    System.clearProperty("user.dir");
  } else {
    System.setProperty("user.dir", originalUserDir);
  }
}
```

This test locks that the default uses the application directory, not `target/` or the user's home directory.

- [ ] **Step 5: Run tests to verify they fail**

Run:

```bash
mvn -Dtest=SyncDiagnosticsJsonTest,SyncDiagnosticsExporterTest test
```

Expected: FAIL because writer/exporter/sanitizer do not exist.

- [ ] **Step 6: Implement `SyncDiagnosticsJson`**

Keep it fixed-purpose:

- `quote(String value)`
- `field(String name, String value)`
- `field(String name, boolean value)`
- `field(String name, int value)`
- `field(String name, long value)`
- small object builders for known diagnostics values if useful

Do not add reflection serialization.

- [ ] **Step 7: Implement `SyncDiagnosticsExportSanitizer`**

Core behavior:

- Maintains `Map<String, String> sessionAliases`.
- Converts raw session keys to alias:
  - route from prefix before `:`
  - unknown/blank -> `none`
  - first `live-room:186538` -> `live-room#1`
  - second distinct live-room -> `live-room#2`
- Sanitizes path strings with the deny-by-default rules in the spec.
  - known user paths keep only `<user>`
  - unknown absolute paths become `<redacted-path>`
  - relative paths become basename only
- Produces export text/JSON from safe values only.

Do not mutate original recorder snapshots.

- [ ] **Step 8: Implement `SyncDiagnosticsExporter`**

Constructor:

```java
public SyncDiagnosticsExporter(Path outputDirectory)
```

Static default:

```java
public static Path defaultOutputDirectory()
```

Use:

```java
defaultApplicationDirectory().resolve("sync-diagnostics")
```

Resolve the application directory from `user.dir` first, then the code source. Treat a directory named `lizzieyzy-next` or a repo root with `pom.xml` and `src/main/java/featurecat/lizzie` as the application directory. If neither path resolves, fall back to the process working directory.

`export(SyncDiagnosticsExportSnapshot snapshot)`:

- freeze timestamp from snapshot
- create output directory
- create `sync-diagnostics-YYYYMMDD-HHMMSS.zip`
- write all fixed entries
- use UTF-8
- use `ZipOutputStream`

- [ ] **Step 9: Run exporter tests**

Run:

```bash
mvn -Dtest=SyncDiagnosticsJsonTest,SyncDiagnosticsExporterTest test
```

Expected: PASS.

- [ ] **Step 10: Commit Task 3**

```bash
git add src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsJson.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExportSanitizer.java \
  src/main/java/featurecat/lizzie/analysis/SyncDiagnosticsExporter.java \
  src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsJsonTest.java \
  src/test/java/featurecat/lizzie/analysis/SyncDiagnosticsExporterTest.java
git commit -m "feat(sync): export sanitized diagnostics packages"
```

## Task 4: Publish Recent Protocol Events From ReadBoard

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Write failing protocol buffer regression**

Extend an existing protocol redaction test:

```java
harness.readBoard.parseLine("roomToken secret-room-token");
harness.readBoard.parseLine("syncPlatform yike");

SyncDiagnosticsExportSnapshot export =
    SyncDiagnosticsRecorder.getDefault().exportSnapshot();

List<SyncProtocolDiagnosticEvent> events = export.getRecentProtocolEvents();
assertTrue(events.stream().anyMatch(event -> "roomToken <redacted>".equals(event.getSummary())));
assertTrue(events.stream().anyMatch(event -> "syncPlatform yike".equals(event.getSummary())));
assertTrue(events.stream().noneMatch(event -> event.getSummary().contains("secret-room-token")));
```

Do not rely on global buffer indices unless the test reset helper has just called `clearForTests()`. Prefer `anyMatch` for protocol buffer assertions.

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -Dtest=ReadBoardSyncDecisionTest#protocolLineSummaryDoesNotExposeSensitivePayloads test
```

Expected: FAIL because protocol events are not recorded yet.

- [ ] **Step 3: Record protocol events**

In `ReadBoard.recordProtocolLine(String line)`:

```java
lastProtocolLineSummary = summarizeProtocolLine(line);
lastProtocolTimestampMillis = System.currentTimeMillis();
SyncDiagnosticsRecorder recorder = SyncDiagnosticsRecorder.getDefault();
recorder.recordProtocolEvent(
    SyncProtocolDiagnosticEvent.of(
        lastProtocolTimestampMillis, lastProtocolLineSummary, "ReadBoard"));
publishReadBoardDiagnosticsSnapshot(recorder.snapshot().getLatestDecisionTrace());
```

Avoid calling recorder for raw line. Do not store raw protocol payload anywhere.

- [ ] **Step 4: Run focused ReadBoard diagnostics tests**

Run:

```bash
mvn -Dtest=ReadBoardSyncDecisionTest#protocolLineSummaryDoesNotExposeSensitivePayloads,ReadBoardSyncDecisionTest#protocolLineSummaryRedactsSgfPayloadsAndPathLikeUpdateMetadata test
```

Expected: PASS.

- [ ] **Step 5: Commit Task 4**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoard.java \
  src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java
git commit -m "feat(sync): record recent readboard protocol diagnostics"
```

## Task 5: Add Export Button To Diagnostics Dialog

**Files:**
- Modify: `src/main/java/featurecat/lizzie/gui/SyncDiagnosticsDialog.java`
- Modify: `src/main/resources/l10n/DisplayStrings.properties`
- Modify: `src/main/resources/l10n/DisplayStrings_zh_CN.properties`
- Modify: `src/test/java/featurecat/lizzie/gui/SyncDiagnosticsResourceTest.java`

- [ ] **Step 1: Write failing resource tests**

Add expected keys:

```java
assertEquals("Export package", bundle.getString("SyncDiagnostics.export"));
assertEquals("Exported to:", bundle.getString("SyncDiagnostics.exportSuccess"));
assertEquals("Export failed:", bundle.getString("SyncDiagnostics.exportFailure"));
```

Chinese:

```java
assertEquals("导出诊断包", bundle.getString("SyncDiagnostics.export"));
assertEquals("已导出到：", bundle.getString("SyncDiagnostics.exportSuccess"));
assertEquals("导出失败：", bundle.getString("SyncDiagnostics.exportFailure"));
```

- [ ] **Step 2: Run resource test to verify it fails**

Run:

```bash
mvn -Dtest=SyncDiagnosticsResourceTest test
```

Expected: FAIL because keys are missing.

- [ ] **Step 3: Add l10n keys**

Modify `DisplayStrings.properties`:

```properties
SyncDiagnostics.export=Export package
SyncDiagnostics.exportSuccess=Exported to:
SyncDiagnostics.exportFailure=Export failed:
```

Modify `DisplayStrings_zh_CN.properties`:

```properties
SyncDiagnostics.export=导出诊断包
SyncDiagnostics.exportSuccess=已导出到：
SyncDiagnostics.exportFailure=导出失败：
```

- [ ] **Step 4: Update dialog**

In `SyncDiagnosticsDialog`:

- add `JButton exportButton`
- add it beside Refresh / Copy / Close
- add a small non-editable status area or append status text to `summaryTextArea`
- on click:

```java
try {
  Path zip =
      new SyncDiagnosticsExporter(SyncDiagnosticsExporter.defaultOutputDirectory())
          .export(SyncDiagnosticsRecorder.getDefault().exportSnapshot());
  refreshSummary();
  summaryTextArea.append("\n\n" + text("SyncDiagnostics.exportSuccess", "Exported to:") + " " + zip);
} catch (IOException | RuntimeException ex) {
  summaryTextArea.append(
      "\n\n" + text("SyncDiagnostics.exportFailure", "Export failed:") + " "
          + SyncDiagnosticsEnvironment.sanitizePath(ex.getMessage()));
}
```

Success may show the local full path to the local user. Failure text must sanitize paths before display.

- [ ] **Step 5: Run resource and exporter tests**

Run:

```bash
mvn -Dtest=SyncDiagnosticsResourceTest,SyncDiagnosticsExporterTest test
```

Expected: PASS.

- [ ] **Step 6: Commit Task 5**

```bash
git add src/main/java/featurecat/lizzie/gui/SyncDiagnosticsDialog.java \
  src/main/resources/l10n/DisplayStrings.properties \
  src/main/resources/l10n/DisplayStrings_zh_CN.properties \
  src/test/java/featurecat/lizzie/gui/SyncDiagnosticsResourceTest.java
git commit -m "feat(sync): add diagnostics package export action"
```

## Task 6: Combined Verification And Privacy Review

**Files:**
- No planned source edits unless tests fail.

- [ ] **Step 1: Run focused diagnostics unit tests**

Run:

```bash
mvn -Dtest=SyncDiagnosticsEventBufferTest,SyncDiagnosticsRecorderTest,SyncDiagnosticsJsonTest,SyncDiagnosticsExporterTest test
```

Expected: PASS.

- [ ] **Step 2: Run sync diagnostics regression tests**

Run:

```bash
mvn -Dtest=SyncDecisionTraceTest,YikeSessionDiagnosticsSnapshotTest,ReadBoardSyncDecisionTest,OnlineDialogYikeSessionStateTest,SyncDiagnosticsResourceTest test
```

Expected: PASS.

- [ ] **Step 3: Run full package build in WSL**

Run:

```bash
mvn -DskipTests package
```

Expected: BUILD SUCCESS.

After this command, check and clean Maven formatter side effects if they appear:

```bash
git diff --check
git status --short
```

Do not commit `target/` or `dependency-reduced-pom.xml`.

- [ ] **Step 4: Run Windows package build**

Use a real Windows path, not WSL UNC as Maven working directory.

Use a Windows temp build directory copied from the WSL worktree. Do not build from WSL UNC with Windows Maven, because `maven-jar-plugin` can fail on `\\wsl.localhost\...` paths. Do not build `D:\dev\weiqi\lizzieyzy-next` unless that clone has first been fast-forwarded to this exact branch.

Recommended command shape:

```powershell
robocopy "\\wsl.localhost\Ubuntu\home\dev\.config\superpowers\worktrees\lizzieyzy-next\sync-diagnostics-panel-20260530" "D:\dev\weiqi\lizzieyzy-next-sync-diagnostics-export-build-20260531" /E /XD .git target /XF dependency-reduced-pom.xml
D:\dev\weiqi\lizzieyzy-next\.tools\apache-maven-3.9.10\bin\mvn.cmd -f D:\dev\weiqi\<temp-build-dir>\pom.xml -DskipTests package
```

Expected: BUILD SUCCESS and jar under `<temp-build-dir>\target\`.

- [ ] **Step 5: Dispatch reviewer subagent**

Ask reviewer to inspect the final integrated diff for:

- read-only diagnostics boundary
- no raw SGF/token/session/path leakage in export
- no sync behavior changes
- tests lock critical privacy and ring-buffer behavior

- [ ] **Step 6: Fix reviewer findings if needed**

If reviewer finds issues, fix them with focused patches and rerun relevant tests.

- [ ] **Step 7: Final status**

Run:

```bash
git status --short --branch
git log --oneline -5
```

Report:

- commits created
- tests/builds run
- Windows jar path if built
- any manual GUI check still needed
