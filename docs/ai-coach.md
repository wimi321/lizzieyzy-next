# AI 陪练 (AI Coach)

AI 陪练 is a game against an AI that plays like a human player of a chosen rank, with feedback on
the player's own moves. This page describes how it works and where the code is.

## Setup

`NewHumanSlGameDialog` hosts `CoachSetupForm`, one sheet drawn by `CoachSetupWidgets`:

- **对手棋力**: 31 levels (`CoachLevel`), named by Fox rank from 野狐 17-18K to 10D. Beside the
  Fox rank are the KataGo level (10 级 to 11 段, with the 强 half steps from 强1段 to 强10段) and
  the real-world grade, which also tell apart the levels that share a Fox rank. 等级对照表 opens
  the whole community table (`CoachRankTable`); a click on a row, or Enter, picks that level.
- **训练方式**: 边下边提醒 (default), 实战后复盘 or 边下边分析 (`TrainingMode.CHOICES`).
- **执棋**: 执黑, 执白 or 随机. With a handicap the player always takes Black and receives the
  stones, and komi is 0.5.
- **AI 思考时间**: 自然, 快 or 慢 (`HumanThinkTime.Pace`). It only paces the AI's replies. The
  move is chosen the same way, with the same search budget, at every pace: 10 seconds a move, and
  20 for 强10段 and 11段.
- **更多设置**: 让子, 贴目 and 从当前局面开始.

The last game's choices are saved (`HumanSlTrainingPreferences`). The dialog checks the HumanSL
model off the event thread (`HumanSlModelCheck`) and downloads it into a resumable `.part` file
(`HumanSlModelDownload`).

## The opponent

`HumanSlAnalysisRunner.chooseMove` picks the AI's move with KataGo's analysis engine and the
HumanSL model. Each level plays with the settings of the `gtp_human*_example.cfg` config the rank
table was measured with (`HumanSlPlaySettings`):

- **10 级 to 1 级** (野狐 17-18K to 1K): the move is sampled straight from the HumanSL policy of a
  pre-AlphaZero profile, `preaz_20k` to `preaz_7k`, from two root symmetries
  (`HumanPolicySampler`), as `gtp_human5k_example.cfg` plays. Repeated openings get different
  replies, and the AI makes the mistakes players of the rank make.
- **1 段 to 11 段** (野狐 1D to 10D): one search with the level's config: a `rank_` profile from
  `rank_5k` to `rank_9d`, 60 to 400 visits, its PiKL lambda, human exploration and temperatures.
  The analysis engine never chooses a move, so `HumanSlChosenMove` works out the move genmove
  would choose, after KataGo's `getPlaySelectionValues`: every legal move's human odds times
  exp((utility − best utility) / lambda), with moves the search did not reach at the average
  utility; the pass keeps the share the search gives it; then the temperature sharpens the
  result. A lower lambda lets KataGo, not the human model, decide. The search keeps genmove's
  `wideRootNoise` of 0, which the analysis engine would otherwise raise. A search stops at its
  visits or at the move budget; 强10段 and 11段, with 200 and 400 visits, get twice the budget
  (`moveTimeFactor`). On an Apple Silicon Mac with the B11 network, 11段's 400 visits take about
  11 seconds and 强10段's 200 between 5 and 9.
- **Passing:** from 40% of the board on, or after the player passed. A kyu level asks a 40-visit
  search; a dan level passes when its own search prefers to.
- **Resigning:** as in the configs: a kyu level after 20 turns below 0.5% and 40 points behind, a
  dan level after 10 turns below 1% and 20 points behind (`AiResignation`).
- **Ending:** two passes end the game, and KataGo counts it (`GameResult`).

`chooseHumanMove` still plays a bare profile for older callers: raw policy up to 5 段, search
verification of human candidates above (`HumanLikeMoveSelector`).

`HumanThinkTime` paces each reply by how obvious it is for players of the rank.

The engine runs with one analysis thread and eight search threads (`HumanSlLaunchProfile`). On
Apple Silicon every GPU batch is padded to four (`metalPadBatch`), so Metal compiles one GPU
program during 准备中 instead of one for each new batch size during the game.

## During the game

`CoachPanel` sits at the right of the board. While the game runs, `CoachFocusLayout` hides the
side panels and restores them exactly afterwards. The panel shows:

- the opponent and its state;
- both players' captures and time, and the komi;
- the player's win chance (`CoachSummaryView`);
- the last move or a question for the player (`CoachMoveCard`);
- in 边下边提醒, a count of each rating and the latest rated moves (`CoachHistoryView`).

Its buttons never take keyboard focus. Space is blocked, browsing the history snaps back to the
game, and 悔棋 takes back the player's move with the AI's reply.

## 边下边提醒: rating moves

`MoveReviewer` reviews each position where the player is to move on the coach engine while the
player thinks: 400 visits, capped at 1.5 s. A move is usually judged from that search at once. A
move it hardly looked at (fewer than max(16, best ÷ 20) visits) gets a 200-visit, 0.8-second
review of the position after it. Reviews run one at a time on their own thread.

Moves are judged by the player's **win chance at the opponent's level** (`RankAwareWinrate`):

```
win chance = Φ(KataGo score lead / HumanSL score standard deviation)
```

KataGo's own winrate assumes two superhuman players carry on. It sits near 0% or 100% once someone
leads by a few points, and it calls a two-point slip in a close endgame a disaster at every level.
The HumanSL model's score spread for the rank says how much games between such players still
swing: about 18 points in the opening and a few points in the late endgame at 3 段.

`MoveVerdict` rates by the loss, rounded to the whole percentage the panel shows:

| Rating | Win chance lost | Or, once decided (below 5% or above 95%) |
|---|---|---|
| 缓手 | 5% | — |
| 问题手 | 10% | 10 points |
| 恶手 | 20% | 20 points |

Once the game is decided, the win chance hardly moves, so points lost count too, but only big
losses. Far ahead, giving a few points to play safe is sound; far behind, a few points change
nothing.

**妙手** is KataGo's choice where fewer than 10% of players of the rank play it and their usual
move loses at least 5%.

**恶手.** On a 恶手 the AI holds its reply. The panel asks the player to choose 重下这手 (take it
back), 看 AI 推荐 (circle KataGo's choice) or 不重下，继续对局.

**Hints.** 提示 or H shades the area of KataGo's choice, then circles the point
(`CoachAssist`, `CoachBoardMarks`).

### How often each rating comes up

`scripts/measure_coach_ratings.py` plays games between two stand-ins of a rank, each move drawn
from the rank's HumanSL policy, and judges Black's moves as the app does. Re-run it after changing
the rules, the review budgets or the default network.

Ratings per game, three games of 220 moves per rank (110 judged moves each), with the rules above,
mean and range:

| Rank | 缓手 | 问题手 | 恶手 |
|---|---|---|---|
| 10 级 | 16 (11–24) | 14 (10–21) | 1 (1–1) |
| 3 级 | 15 (14–16) | 10 (6–14) | 4 (2–7) |
| 1 段 | 12 (9–15) | 5 (4–5) | 3 (0–4) |
| 3 段 | 12 (9–16) | 6 (4–8) | 3 (1–5) |
| 5 段 | 9 (4–12) | 6 (5–8) | 2 (1–3) |
| 9 段 | 5 (2–7) | 4 (3–5) | 4 (3–5) |

This is the app's budget on an M4 Pro with the bundled Metal KataGo and network: the turn's review
reached about 45–55 root visits in 1.5 s, and 23 at 9 段, where human moves take more of the
playouts. Another 18 games with 400 visits and no time limit gave about a third fewer 问题手 for
kyu ranks (10 级 9, 3 级 5) and the same range of 恶手.

What it shows:

- **恶手**, the only rating that holds the game, comes up one to four times a game at every rank.
  The rank-aware win chance puts the ranks on the same footing for big mistakes, so one set of
  thresholds serves every rank.
- **缓手 and 问题手** come up more often for weaker ranks and with shorter searches. A move the
  turn's search hardly looked at is judged from the short review after it, and such moves are
  flagged three to seven times as often as the rest. Longer reviews would make these two steadier.
  So the summary's key mistakes count only the 恶手.
- **Decided games.** Stand-ins never resign, so a fifth to a third of their moves come after the
  game is decided. The earlier rule there (缓手 from 4 points, 问题手 from 8, 恶手 from 15) added
  two to four 缓手 a game, mostly safe moves by the side far ahead.
- **妙手** is rare: 3 in 36 games. Stand-ins play the rank's usual moves; a player who finds better
  ones sees it more often.

## After the game

The move card becomes a short summary (`CoachPanelState.Summary`). It shows:

- 你赢了, 你输了 or 和棋, with the result;
- one line of numbers: moves, key mistakes (恶手) and hints in 边下边提醒, and take-backs;
- two buttons. 开始复盘 leaves AI 陪练 and the game is analysed in the usual view. 再来一盘
  ends the session and opens the setup sheet again with the same choices.

In 边下边提醒 the history then lists the game's three biggest mistakes
(`MoveReviewer.worstMistakes`). Clicking one leaves AI 陪练 with the board on the position before
it (`HumanSlGameController.reviewFrom`). The game's own buttons step aside. There is no separate
report.

## Checking changes by hand

The coach's tests run with fake runners. Before a release, play a short game in each mode in the
real app:

- a deliberate 1-1 move must show the 恶手 card;
- 提示 must mark an area, then a point;
- two passes must end the game with a counted result;
- after 认输, a key mistake in the summary must open the review at the position before it.
