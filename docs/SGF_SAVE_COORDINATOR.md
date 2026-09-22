# SGF save coordinator

The four Save As modes use the same chooser, owned by the main window. Existing
extension normalization and overwrite cancellation are retained. The filename
field uses the normal chooser behavior; no hidden frame or delayed focus thread
is created. Saving over the current original file uses the same write pipeline.

## Snapshot and publication

The event thread captures a detached tree, including the selected branch and
complete analysis payloads. Each node is copied under the short lock also used
by primary, secondary and whole-game payload commits. Engine publication can
continue after each node copy. No engine lock is held while serializing the
whole tree or writing to disk. The resulting immutable SGF string is the only
board content given to the background writer.

Current-branch export follows the selected node's ancestors, then its main
continuation. It retains setup nodes, passes and game metadata without clearing,
replaying, navigating or pruning the live tree. Other export modes retain every
variation. Temporary raw/comment flags are restored even when serialization
fails.

A single worker writes accepted requests in order (at most 16 waiting writes).
It flushes a temporary UTF-8 file in the destination directory, then atomically
replaces the destination. There is deliberately no destructive fallback on a
filesystem that cannot perform atomic replacement. A failed save reports the
existing save error and retains the original file.

Successful completion updates the current filename and recent directory only
if the original board, history and root still own the application. Continued
editing does not alter an already captured save. An export does not rename the
current document. Engine pause/resume is confined to the chooser/capture phase
and never resumes an engine that was replaced while the dialog was open.

Shutdown defers through completion futures, including event-thread completion,
before persisting configuration and exiting. It never waits synchronously on
the event thread.

## Validation

- `AtomicSgfFileWriterTest`: complete UTF-8 replacement, Chinese paths, uppercase
  extensions, locked/rejected replacement, unsupported atomic moves, missing
  parent directories and temporary-file cleanup.
- `SgfSaveQueueTest`: blocked disk I/O leaves the event thread responsive,
  snapshot-only worker input, retired-board completion, ordered repeated saves,
  write/rejection failure and shutdown completion.
- `SGFSaveSnapshotTest`: selected branches, setup/pass round trips, mode/flag
  restoration, continued edits and a deliberately interrupted analysis commit.
- `OfflineBoardAcceptanceTest`: existing cancellation, overwrite protection and
  engine replacement checks, plus all four successful chooser flows, actual
  main-window ownership, Chinese filenames and preservation of the live tree.

The final focused headless run passed 173 tests, with no failures, errors or
skips, including the detached-payload tests and analysis-engine regressions.
Windows/Linux desktop acceptance and native filename typing are recorded
separately by the integration task; programmatic chooser approval is not evidence
of real keyboard input.

No throughput improvement is claimed. Disk I/O has moved off the event thread;
tree capture and serialization remain on it to preserve the existing serializer's
board, rules and formatting context.
