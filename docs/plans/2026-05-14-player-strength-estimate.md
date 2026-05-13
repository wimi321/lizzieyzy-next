# Player Strength Estimate Plan

## Preflight

- Pulled `main` with `git pull --ff-only`.
- Checked GitHub API: open issues = 0, open PRs = 0.
- Reviewed recent merged PRs to avoid overlap; none implement player strength estimates.

## Implementation

1. Add `PlayerStrengthEstimator` as a pure analysis helper.
2. Add an `Analyze > Strength estimate` menu item.
3. Render a compact HTML report in the existing message dialog.
4. Add English and Simplified Chinese UI strings.
5. Add focused unit tests.

## Validation

- `mvn -Dtest=PlayerStrengthEstimatorTest test`
- `mvn test`
- `python -m py_compile scripts\fetch_fox_sgf_samples.py scripts\evaluate_strength_samples.py scripts\run_strength_calibration.py`
- `python scripts\fetch_fox_sgf_samples.py 533170311 --balanced-by-rank --samples-per-rank 2 --max-users 20 --min-moves 80 --out target\fox-rank-samples-v2 --sleep 0.2`
- `python scripts\evaluate_strength_samples.py <12 selected public SGFs> --max-moves 140 --max-visits 48 --jsonl target\strength-eval-public-12-48v.jsonl`
- `python scripts\run_strength_calibration.py --out target\strength-calibration-diverse-20260514 --target-games 16 --max-users 160 --max-games-per-user 1 --target-sides-per-group 5 --max-moves 120 --max-visits 24 --sleep 0.1`
- `python scripts\run_strength_calibration.py --out target\strength-calibration-diverse-20260514 --summary-only`
- `python scripts\run_strength_calibration.py --out target\strength-calibration-large-20260514-240x220 --target-games 240 --max-users 3500 --max-games-per-user 2 --target-sides-per-group 75 --max-moves 220 --max-visits 24 --sleep 0.02`
- `python scripts\run_strength_calibration.py --out target\strength-calibration-large-20260514-240x220 --summary-only`

The large calibration run covered 240 public Fox games, 347 unique sampled users, and
480 evaluated sides. The first anti-overestimate pass overfit the kyu samples and could
collapse normal high-dan profiles into kyu bands. The corrected model keeps the composite
review score as the primary signal, uses first-choice/good-move/loss metrics as soft
guardrails, and only keeps narrow hard caps for plainly weak evidence. After recalculating
the summary, normal calibration rows had 258/325 sides within one broad rank group and
11/325 visible kyu sides estimated as dan, mostly low-dan single-game performances.

The public SGF calibration is intentionally local-only. The scripts are committed, but
downloaded SGFs and JSONL results stay under `target/` because Fox game records are
external, mutable data.

## Release Notes Draft

- Added a cached-analysis player strength estimate that summarizes black, white, and
  overall performance from main-line candidate quality, winrate loss, and score loss.
- The report includes sample count, confidence, review score, first-choice rate,
  good-move rate, difficulty, weighted score loss, average and median score loss, and
  mistake rate so users can judge how much to trust the estimate.
- The rank score uses a composite of robust point loss, first-choice rate, good-move rate,
  mistake rate, and difficulty so high-level games are not underrated by one punitive
  weighted-loss curve.
- The rank label also applies calibrated per-metric guardrails rather than cascading
  evidence gates: weak profiles cannot jump into dan, but a single first-choice or
  low-difficulty signal no longer collapses otherwise strong play into kyu.
- Added local Fox SGF sampling and KataGo batch-evaluation scripts for future empirical
  calibration without committing dynamic third-party game data.
