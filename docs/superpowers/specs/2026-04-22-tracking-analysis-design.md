# Tracking Analysis Design

## Problem

Users want to monitor the analysis of a specific board point that KataGo doesn't include in its default top candidates — while keeping the main analysis running normally. The existing `allow`/`avoid` mechanism replaces the entire candidate set, which is not the desired behavior.

## Solution

Add a "Track Analysis" feature: a lightweight auxiliary KataGo process (JSON analysis mode) that analyzes user-specified points independently, while the main GTP engine continues unrestricted analysis. Results from both engines merge in the UI.

## Architecture

```
Main Engine (GTP, Leelaz.java)          Tracking Engine (JSON, TrackingEngine.java)
  kata-analyze (unrestricted)              { allowMoves: ["E3","F5"], ... }
         |                                           |
         v                                           v
  BoardData.bestMoves                    TrackingEngine.currentTrackedMoves
         |                                           |
         +-------------------------------------------+
                          |
                  BoardRenderer merges
                  (tracked points get special marker)
```

Two independent data channels. The main engine is never modified.

## Components

### TrackingEngine.java (new)

A new class in `featurecat.lizzie.analysis` that manages an auxiliary KataGo process in JSON analysis mode (`katago analysis`).

**Why a new class instead of extending AnalysisEngine:** `AnalysisEngine` is designed for batch analysis — it maps request IDs to `BoardHistoryNode` via `analyzeMap`, tracks batch completion via `resultCount`, shows progress dialogs, and exits after analysis finishes. `TrackingEngine` has a fundamentally different lifecycle: persistent (stays alive), streaming (continuous updates via `reportDuringSearchEvery`), and position-following (re-sends on navigation). Reuse `AnalysisEngine`'s JSON response parsing utilities (`Utils.getBestMovesFromJsonArray()`) and reference its SNAPSHOT-aware request construction (`collectHistoryActions()`), but the class itself has different state management and lifecycle.

**Responsibilities:**
- Launch/manage a KataGo analysis-mode process
- Maintain the set of tracked coordinates (`trackedCoords`)
- Send JSON analysis requests with `allowMoves` restriction on position changes
- Parse streaming JSON responses and update the transient tracked results
- Trigger UI refresh

**JSON request format:**
```json
{
  "id": "track-1",
  "moves": [["B","Q16"],["W","D4"]],
  "initialStones": [],
  "rules": "chinese",
  "komi": 7.5,
  "boardXSize": 19,
  "boardYSize": 19,
  "maxVisits": 500,
  "reportDuringSearchEvery": 0.1,
  "allowMoves": [
    {"player": "B", "moves": ["E3","F5"]},
    {"player": "W", "moves": ["E3","F5"]}
  ]
}
```

Key parameters:
- `reportDuringSearchEvery: 0.1` — stream intermediate results every 100ms
- `allowMoves` — KataGo JSON protocol's whitelist (different from GTP `allow`)
- `maxVisits` — configurable, default 500, controls GPU usage
- Same `id` on a new request overrides the previous one (no explicit `stop` needed)

**Command line construction:** Reuse `Lizzie.config.analysisEngineCommand` (the existing `ui.analysis-engine-command` config key), which already points to a properly configured `katago analysis` command. If the user has not configured a separate analysis engine command, fall back to constructing one from the main engine's config: extract the KataGo executable path from `Config.leelazConfig` and the model path, then build: `<katago-exe> analysis -model <model-path> -config <analysis-cfg> -quit-without-waiting`. This avoids fragile command-string parsing while supporting users who have customized their analysis engine setup.

### Lifecycle

| Event | Action |
|---|---|
| User adds first tracked point (preload=false) | Launch TrackingEngine, show status bar "Tracking engine starting..." |
| App startup (preload=true) | Launch TrackingEngine alongside main engine |
| All tracked points cleared | TrackingEngine stays alive (idle), no requests sent |
| User adds tracked point again | Immediately send request (no startup delay) |
| Main engine switched | Destroy and recreate TrackingEngine (new binary/model); preserve `trackedCoords` if new engine is also KataGo |
| Engine switched to non-KataGo | Destroy TrackingEngine, clear all tracked points |
| App exit | Destroy TrackingEngine process |

### Data Model Changes

**Tracked results are transient display-only data**, not persisted per-node. Unlike `bestMoves` (which is cached per `BoardHistoryNode` for later review), tracked results are only meaningful for the current live position and are cleared on every navigation.

**TrackingEngine** holds the current tracked results internally:
```java
private List<MoveData> currentTrackedMoves;  // latest streaming results
```

**BoardRenderer** reads this directly from `TrackingEngine` (via `Lizzie.frame.trackingEngine.getCurrentTrackedMoves()`), not from `BoardData`. This avoids polluting `BoardData` with transient data and sidesteps `copyDataFrom()` / SGF save/load concerns.

**LizzieFrame.java** or new tracking state holder — add:
```java
public Set<String> trackedCoords;       // e.g. {"E3", "F5"}
public boolean isKeepTracking = false;  // persist tracked coords across moves
public TrackingEngine trackingEngine;   // auxiliary engine instance
```

### Merge & Rendering (BoardRenderer.java)

1. Render `bestMoves` (main engine candidates) normally
2. Read `TrackingEngine.getCurrentTrackedMoves()` for tracked results
3. For each tracked move:
   - If the coordinate already appears in `bestMoves` → skip (main engine data is more accurate)
   - Otherwise → render with a special visual marker (dashed circle outline or distinct border color)
3. Info panel shows merged results sorted by winrate

**Tracked point visual marker:** Same circle as normal candidates, but with a dashed outer ring or a distinct border color (e.g., light purple) to distinguish user-added points from engine-chosen candidates.

**No PV display** on tracked points — only winrate, visits, and score values.

### Position Sync

When the user navigates the game tree (forward, backward, branch switch):
1. Main engine `ponder()` fires as usual
2. If `trackedCoords` is non-empty, TrackingEngine sends a new JSON request for the current position
3. Position construction follows the same SNAPSHOT-aware approach as `AnalysisEngine.collectHistoryActions()`: find the nearest SNAPSHOT anchor, use its stones as `initialStones`, then replay only subsequent MOVE/PASS actions as the `moves` array. This ensures correctness when SNAPSHOT nodes exist in the game tree.
4. Old request is overridden by the new one (same `id`)

### "Keep Tracking" (Persistent Mode)

- `isKeepTracking = false` (default): navigating to the next move automatically clears `trackedCoords`, restoring pure main-engine analysis
- `isKeepTracking = true`: `trackedCoords` persists across moves; TrackingEngine re-analyzes tracked points at each new position

### Right-Click Menu (RightClickMenu.java)

New menu items, visible only when the main engine is KataGo and not in game mode:

| Menu Item | Visible When | Behavior |
|---|---|---|
| "Track this point" / "追踪分析此点" | Engine is KataGo, point not in trackedCoords | Add point to trackedCoords, trigger TrackingEngine |
| "Untrack this point" / "取消追踪此点" | Point is in trackedCoords | Remove point from trackedCoords |
| "Clear all tracked" / "清除所有追踪点" | trackedCoords non-empty | Clear all tracked points |
| "Keep tracking" / "持续追踪" (checkbox) | trackedCoords non-empty | Toggle isKeepTracking |

These are **separate from** existing allow/avoid menu items. The existing "只分析此点", "增加分析此点", "不分析此点" etc. remain unchanged and operate on the main engine only.

### Configuration (Config.java / config.json)

New options under the `ui` section:

| Key | Type | Default | Description |
|---|---|---|---|
| `tracking-engine-preload` | boolean | false | Launch tracking engine at app startup (recommended for TRT/slow-loading engines) |
| `tracking-engine-max-visits` | int | 500 | Max visits per tracking analysis request |

### i18n (DisplayStrings*.properties)

New keys for all supported locales (zh_CN, zh_TW, zh_HK, en_US, ja_JP, ko):
- `RightClickMenu.trackPoint`
- `RightClickMenu.untrackPoint`
- `RightClickMenu.clearAllTracked`
- `RightClickMenu.keepTracking`
- `LizzieFrame.trackingEngineStarting`

## Boundary Conditions

1. **Non-KataGo engine**: tracking menu items hidden, TrackingEngine not started
2. **Tracked point already in main candidates**: tracking engine still analyzes it, but renderer uses main engine data (more visits = more accurate)
3. **Game mode (playing against AI)**: tracking menu items hidden
4. **TrackingEngine startup failure**: show error toast, main engine unaffected
5. **Simultaneous allow/avoid + tracking**: both work independently — allow/avoid restricts the main engine, tracking uses the auxiliary engine
6. **Engine switch to non-KataGo**: auto-clear tracked points, destroy TrackingEngine

## What This Design Does NOT Do

- No support for non-KataGo engines (Leela Zero, Sai)
- No per-point computation priority adjustment
- No PV (principal variation) display on tracked points
- No separate configuration for tracking engine command (reuses main engine binary/model)
- No SSH remote engine support for tracking (local process only; SSH support is out of scope for initial implementation)
