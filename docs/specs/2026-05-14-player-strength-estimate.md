# Player Strength Estimate

## Context

The backlog asks for a player strength evaluation feature. There are no open issues or
open PRs for it as of 2026-05-14, and recent merged PRs cover Yike sync, trial analysis,
JCEF bundling, and release packaging instead.

## Scope

- Add a user-facing entry under `Analyze`.
- Use cached main-line analysis only; do not start new engine work from this command.
- Report black, white, and overall practical-performance bands.
- Show enough raw signals to keep the estimate explainable: analyzed move count, average
  winrate loss, average and median score loss, first-choice rate, good-move rate,
  weighted score loss, position difficulty, and mistake rate.
- Treat the result as a review aid, not an official rank.

## Data Model

For each main-line action node, compare the played move with the cached candidate list
from the position before the move. When the played move is present in the candidate list,
loss is measured against the top candidate from the same root analysis. This keeps score
loss, winrate loss, first-choice rate, and good-move rate on one consistent search.

If the played move is not present in the candidate list, the estimator falls back to the
before/after node values:
`currentWinrate - expectedOpponentWinrate`, with the same sign convention for KataGo
`scoreMean` when available. Negative losses are counted as zero in aggregate so
over-performing a local engine preference does not hide later mistakes.

Move categories follow public OGS-style score-loss thresholds: excellent, great, good,
inaccuracy, mistake, and blunder. The report treats excellent, great, and good moves as
the good-move rate.

## Strength Bands

The estimator keeps a KaTrain-style weighted point loss as an explainable raw signal, but
does not map that value directly to rank. A direct `100 * 0.75 ^ weightedPointLoss`
accuracy curve is too punitive for professional games where a few sharp positions can
raise weighted loss while first-choice rate, good-move rate, median loss, and mistake
rate remain excellent.

The displayed 0-100 score is a composite review score:

- robust point loss: weighted point loss, average point loss, and median point loss;
- move quality: first-choice rate and OGS-style good-move rate;
- stability: mistake rate;
- context: a small bonus for maintaining quality in difficult positions.

Per-move difficulty is the policy-weighted expected loss of the analyzed candidate list,
capped to the 0-100 display range. The same difficulty value weights each move's point
loss, so mistakes in sharp positions count more than routine moves, while every move
still has a minimum weight.

The rank label also applies per-metric guardrails. First-choice rate, good-move rate,
mistake rate, median point loss, and weighted point loss each limit impossible claims, but
they are deliberately weaker than the composite score. This avoids the failure mode where
equivalent choices, low-difficulty positions, or one conservative metric collapse an
otherwise strong high-dan review into kyu.

The final label applies only narrow evidence caps:

- plainly weak move-quality profiles are capped below dan;
- professional labels need elite first-choice, good-move, mistake-rate, and weighted-loss
  agreement;
- low-difficulty games can be limited near high-dan when both first-choice and good-move
  evidence are also weak, but they are no longer sent back to kyu.

The score maps into broad bands from `Beginner` through `12d AI`, including
`10d professional` and `11d top professional`.

Confidence is low under 16 samples, medium at 16 or more samples, and high at 40 or more
samples with score coverage on at least 70% of analyzed moves.

## Release Checks

- Unit test the side split, candidate-based loss calculation, first-choice and good-move
  rates, high-level low-loss games, and header-only winrate fallback.
- Run focused Maven tests for the estimator.
- Run a compile-level Maven test pass before release.

## Empirical Calibration

Three helper scripts support local calibration without committing dynamic external data:

- `scripts/fetch_fox_sgf_samples.py` downloads public Fox SGF samples into `target/`.
- `scripts/evaluate_strength_samples.py` runs local KataGo JSON analysis over those SGFs
  and prints the same review metrics used by the Java estimator.
- `scripts/run_strength_calibration.py` fetches a user-diverse Fox sample set, limits how
  many sampled games each uid can contribute, runs the evaluator, and writes summary
  JSON/CSV/Markdown reports.

The final 2026-05-14 large calibration pass used 240 public Fox games, 347 unique sampled
user ids, at most two sampled games per uid, 24 visits per move, and up to 220 moves per
game:

- exact broad rank-group match: 129/480 sides;
- within one broad rank group: 347/480 sides;
- visible kyu ranks estimated as dan: 37/480 sides;
- noisy-game candidates: 130/480 sides;
- rank-mismatch candidates: 25/480 sides;
- after excluding noisy and rank-mismatch rows, visible kyu ranks estimated as dan:
  11/325 normal rows;
- after excluding noisy and rank-mismatch rows, 258/325 sides were within one broad rank
  group.

The remaining high overestimates are concentrated in visible kyu accounts whose analyzed
moves look dan-level or professional-strength in that game. The calibration report flags
the clearest cases as `rank-mismatch` and keeps them visible for review, but the model is
not tuned around obvious smurf, sandbag, AI-assisted, or otherwise rank-mismatched
samples. A one-game estimate reports demonstrated play quality from the cached analysis;
it is not an official server-rank predictor, and the report keeps raw metrics visible so
users can judge whether the game was noisy, one-sided, or atypical.
