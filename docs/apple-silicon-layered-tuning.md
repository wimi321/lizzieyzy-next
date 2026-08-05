# KataGo performance tuning on Apple Silicon

LizzieYzy Next provides two levels of KataGo performance tuning. The recommended default delegates
the strength-versus-throughput decision to KataGo's official benchmark. A longer hardware search is
available only as an explicit experiment on supported Apple Silicon Macs.

Both levels use the model that is currently selected. Tuning never replaces a medium or large model
with a smaller model merely to produce a higher visits-per-second number.

## Official quick optimization (recommended)

The primary button runs KataGo's official automatic benchmark with the current model, GTP config,
and current safe hardware topology. It applies the `numSearchThreads` value recommended by KataGo.
LizzieYzy Next does not substitute its own Elo formula or rank thread counts by raw throughput.

This is the default for Apple Silicon and all other supported platforms because it is:

- upstream-defined and easier to compare with KataGo's own documentation;
- quick enough for ordinary users, typically about 2-10 minutes;
- less sensitive to one unusually fast GPU/ANE initialization;
- straightforward to repeat after changing the model, engine, config, or expected time per move.

The official benchmark optimizes search threads for the topology it was given. It does not search
different Metal GPU/ANE lane arrangements or batch sizes. That limitation is intentional in the
recommended path: a stable and reproducible result is more useful than a short-lived peak in raw
visits per second.

## Deep hardware tuning (experimental)

On Apple Silicon builds where the runtime declares this feature available, a secondary **Deep
hardware tuning (Experimental)** button compares a bounded set of Metal GPU/ANE topologies and batch
sizes, then uses KataGo's official benchmark to tune search threads for viable candidates. It is an
advanced opt-in mode, not an automatic first-run task.

Before it starts, the dialog asks the user to connect the Mac to power and leave it idle. The search
may take 20-60 minutes or longer, especially with larger models or passively cooled Macs. It can be
cancelled at any time.

GPU/ANE and multi-lane results can vary with model compilation, process state, memory pressure, and
thermal conditions. The experimental path therefore treats a raw visits-per-second peak as a
candidate, not proof of a stronger permanent configuration. A failed, unsupported, unstable, or
inconclusive run does not replace the previous working profile.

The experimental safety gate is deliberately conservative:

1. Every bounded candidate gets a common-thread smoke test in shuffled order. The single-GPU,
   batch-one baseline is mandatory and cannot be eliminated by this ranking.
2. Only the baseline and at most two valid challengers continue to KataGo's official thread search.
3. Each surviving candidate then runs exactly three fresh fixed-thread verification processes, with
   the candidate order reshuffled on every round.
4. A challenger's verification spread must be no more than 15%, and its median visits per second
   must beat the stable baseline median by at least 15%. Otherwise the stable baseline wins. If the
   baseline itself cannot be verified, the experiment is inconclusive and the previous profile is
   kept.

This visits-per-second comparison is used only to decide between hardware layouts for the same
engine, model, and official thread recommendation. It is not presented as an Elo or playing-strength
formula.

## Profile scope and invalidation

A stored result is used only while the relevant environment still matches. Its fingerprint covers:

- Mac hardware, architecture, logical CPU count, unified memory, and macOS build;
- native versus translated execution;
- KataGo executable, active model, and GTP config content;
- the tuning schema and planner version;
- hardware topology and batch semantics where the experimental level manages them.

Paths are not identities: moving byte-identical files does not invalidate a result. Serial numbers
and platform UUIDs are not collected. File hashes are cached by canonical path, size, and
modification time so that a large model does not need to be read on every launch.

The profile is injected only into the main GTP engine. Whole-game analysis, score estimation,
HumanSL, and contribution workloads have different concurrency requirements and keep their existing
settings. Explicit command-line topology, batch, or thread overrides take precedence over managed
values.

## Isolation, cancellation, and rollback

Tuning pauses the active analysis engine before starting an isolated KataGo benchmark process. The
startup gate and process registry prevent local auxiliary analysis engines from silently competing
for the same GPU during measurement. A watchdog aborts tuning if that isolation is lost.

Cancellation, process failure, missing completed metrics, unsupported Metal/CoreML lanes, or an
inconclusive verification leaves the previous profile intact. After either success or failure,
LizzieYzy Next restores the prior analysis state. Startup-only topology or batch changes are applied
by restarting the engine rather than pretending that a dynamic thread update changed the full
hardware configuration.
