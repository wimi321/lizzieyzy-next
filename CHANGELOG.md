# Changelog

All notable maintenance updates to this fork are documented here.

## Unreleased

- Windows installation and release guidance now distinguishes the NVIDIA full package's bundled
  runtime from optional downloads, and explains reinstalling the full package when runtime files are missing.

- KataGo Auto Setup recovers when it is closed and reopened before a download or local model import finishes. Late results no longer leave the reopened window permanently busy, and stale import errors do not open dialogs after closing.

- Top menus keep shortcut hints clear of help buttons, preserve user-defined Quick Links names,
  and expose custom-layout shortcuts to screen readers after reopening the menu.

- KataGo Auto Setup keeps its existing interface while handling background work more reliably.
  - Runtime inspection and cache sizing run off the UI thread. Progress and cancellation remain available, and late results from a closed window are discarded.
  - Saving engine settings preserves concurrent profile changes and the selected default engine.
  - Automatic background optimization no longer opens progress or error popups; results remain available in the performance page.

- KataGo Auto Setup (一键设置) downloads are safer.
  - A weight listed on the KataGo networks page without a size or digest is no longer installed from a stopped download. It counts as whole only when the gzip file reads to the end.
  - Links on that page are followed only over HTTPS to katagotraining.org, and only to plain weight file names. A name with a folder in it can no longer write outside the weights folder.
  - Reopening the dialog during a download no longer hides its progress and leaves the dialog stuck as busy.
  - The official weight list shows the latest answer, and says it is loading instead of 暂时无法获取 while it loads.
  - The CUDA and cuDNN packages that TensorRT acceleration downloads are pinned by version and SHA-256, like NVRTC and TensorRT already were.

- KataGo Auto Setup (一键设置) fixes for NVIDIA and the experimental backends.
  - 切回 CUDA really switches back. After TensorRT was enabled, it used to rewrite KataGo Auto Setup to run TensorRT and keep it as the default.
  - Upgrading the engine of the TensorRT full package keeps the NVIDIA runtime beside it. The upgrade used to delete it, and a 3.66 GB download followed.
  - After updating only the program, an NVIDIA engine from the earlier full package starts as before. A changed engine catalog used to make it ask for a zlib DLL and suggest reinstalling.
  - The lightweight quick-curve model passes the DirectML and OpenVINO settings, as the main engine does.

- The players' names under the board are drawn the way LizzieYzy always drew them again, straight on the wood.
  - Black's name is in black, White's is in white with a dark edge, and the two stones sit between them as before.
  - The light plate added in #658 is gone. Users found it glaring, and it filled the bottom of the board under the first-line stones.
  - Names too long for the space now end with an ellipsis instead of being cut mid-word.

- AI 陪练's 边下边提醒 no longer calls playing safe a 缓手 once the game is decided.
  - When the game is all but decided for players of the rank, a move is pointed out only when it gives away 10 points or more (问题手) or 20 or more (恶手). Before, 4 points already made a 缓手, so the side far ahead was told off for safe moves.
  - Measured with HumanSL stand-ins from 10 级 to 9 段, this removes two to four 缓手 a game. 恶手 come up one to four times a game at every rank, so the win-chance thresholds stay the same for all ranks.
  - `scripts/measure_coach_ratings.py` repeats the measurement; `docs/ai-coach.md` has the numbers.

- AI 陪练's end of game is a short summary in the coach panel.
  - It shows 你赢了 or 你输了 with the result, the number of moves, and in 边下边提醒 the key mistakes (恶手) and hints, plus the take-backs.
  - 开始复盘 opens the usual analysis. 再来一盘 opens the setup again with the same choices.
  - In 边下边提醒 the panel lists the game's biggest mistakes. Clicking one opens the review at the position before it.
  - The game's own buttons step aside once it is over.

- AI 陪练 now starts with a KataGo of your own that was set up for play only.
  - When its GTP config has no analysis.cfg beside it, one is made from the bundled template, as the flash analysis command already does.
  - When something is really missing, the error now says what, the analysis config or the weights, with a pointer to KataGo Auto Setup. Before, every missing piece showed 未选择引擎。.

- AI 陪练 cleanup.
  - Removed what was left of the training report and live correction that #338 retired: the report and move-assessment classes, the unused move evaluation in the HumanSL runner, the 复盘中 and 训练报告 states that nothing ever set, and their 29 strings in all 8 bundles.
  - `docs/ai-coach.md` replaces the outdated design QA page. It describes how the coach works now: the opponent, the ratings and their thresholds, hints, the panel and the setup.

- AI 陪练's first moves no longer stall on Apple Silicon.
  - The coach engine pads every GPU batch to four, so Metal compiles one GPU program, during 准备中, instead of up to eight during the game.
  - In a test game the AI's first two replies came after 1.5 and 1.8 s instead of 3.8 and 3.5 s.
  - Padding costs about 12% of the search speed, which the time limits of the move ratings absorb.
- The AI 陪练 setup dialog opens without freezing.
  - It checks the 99 MB human-style model in the background, showing 正在检查人类棋风模型…, instead of hashing it on the UI thread twice each time it opened.
- Pausing or cancelling the human-style model download now keeps what was downloaded, and the next download resumes from there. Before, 暂停 said progress was kept but deleted it. A file that fails its checksum is still discarded.

- The AI 陪练 setup is a new sheet.
  - **对手棋力** is one scale from 20 级 to 9 段. Pick it with a slider, − and +, or the arrow keys. It reads "3段 · 按3段棋手的习惯落子".
  - **The three training modes are cards**, each with a description; 边下边提醒 is marked 默认.
  - **执棋** is 执黑 / 执白 / 随机.
  - **AI 思考时间** (自然 / 快 / 慢) replaces 用时.
    - It only paces the AI's replies: 快 is about a second a move, 慢 a few seconds more.
    - The AI's move is chosen the same way, with the same 10-second search budget, whatever the pace.
    - A saved 1 minute or 不限时 becomes 慢, anything shorter 自然.
  - **职业棋风 and 网络 9 段 are gone.** A saved choice of either becomes 9 段.
  - **更多设置**: 让子, 贴目 and 从当前局面开始.
    - With a handicap you always take Black and receive the stones. Before, the stones went to whichever colour came up.
    - Komi is then 0.5.
  - The sheet follows the light or dark theme, opens with the focus on 开始对局, and scrolls instead of growing past a small screen.

- 边下边提醒 now stops on a 恶手.
  - The AI holds its reply, and the coach panel shows what the move cost, with three choices:
    - 重下这手 takes the move back;
    - 看 AI 推荐 circles the AI's choice on the board and says how often players at that level find it;
    - 不重下，继续对局 lets the AI play its reply.
  - The win chance bar marks the chance before the 恶手 in red.
- 边下边提醒 has a two-step hint: the 提示 button or H.
  - The first press shades the area of the board where the AI's choice lies, such as 左上角.
  - The second press circles the point and says how often players at that level play it.
  - The marks stay until you move.
- The coach panel's buttons keep to two rows; a hidden button no longer leaves a gap.

- AI 陪练 has a new mode, 边下边提醒, and it is now the default for new users; a saved choice of another mode is kept.
  - Analysis stays hidden during the game. Each of your moves is rated as soon as you play it: 妙手, 好棋, 缓手, 问题手 or 恶手.
  - Moves are judged by your win chance at the opponent's level, not by KataGo's own winrate and not by points.
    - The chance comes from KataGo's score lead, measured against how much games at that rank still swing from the position (the HumanSL model's score spread).
    - KataGo's own winrate sits near 0% or 100% once someone leads by a few points, so it missed big mistakes made after that. It also called a small endgame slip a disaster at every level.
    - A drop of 5% is a 缓手, 10% a 问题手 and 20% a 恶手.
    - Once the game is decided (below 5% or above 95%), the points given away decide instead: 4, 8 and 15 points. A 10-point mistake in a lost game is therefore not called a good move.
    - 妙手 is KataGo's choice where fewer than 10% of players at that level find it and their usual move loses ground.
  - The coach engine reviews each position while you think, so most moves are rated at once. The search is capped at 400 visits and 1.5 seconds, so a slow computer rates with less search instead of slowing the AI down.
  - The coach panel shows:
    - your win chance at that level, with a curve that grows with the game;
    - your last move's rating, its change in win chance, the points lost on a mistake, and how often players at that level play it;
    - a count of each rating and your latest rated moves.
  - Taking back a move removes its rating.
- During an AI 陪练 game that hides analysis, H no longer shows the policy heatmap, which gave away the answer. It says that analysis is hidden, as Space does.

- An AI 陪练 game now runs in a focused layout: the board plus a coach panel at the right, which replaces the bar under the board.
  - The panel shows:
    - the opponent and what it is doing: your turn, thinking, counting or game over;
    - each side's captures and time, and the komi;
    - your last move.
  - Its actions are 悔棋, 停一手, 认输 and 结束对局. Its buttons never take keyboard focus.
  - In 边下边分析 it shows your win chance from KataGo's live analysis as a number, a bar and a curve that grows with the game.
  - When the game ends, it shows the result in place of a pop-up over the board, and 开始复盘 returns to the usual analysis.
  - While the game runs, the comment, variation tree, candidate list, sub-board, captures and status step aside so the board stands alone. They come back exactly as they were, and the saved layout is never touched.
  - The board area's layout moved out of `LizzieFrame` into `MainContentLayout`.

- AI 陪练 games now end the way real games do:
  - The AI passes when the game is over. KataGo's own search decides, as in KataGo's human-imitation configs. It is asked once 40% of the board has been played, or right after you pass. Before, the AI never passed before move 200, on any board size, so small boards could not end.
  - After you pass, the AI passes too when playing on would gain at most a point. Under area scoring it still fills the dame first. Without this a losing AI kept playing inside its own area, because every move scored the same. When players of its rank would pass but KataGo still sees a point worth taking, it plays KataGo's move rather than a near-random human one.
  - Two passes in a row end the game. KataGo counts it; the result, such as 黑胜 3.5 目, goes into the game record and the training bar.
  - The AI resigns once KataGo has seen it lost for long enough, later for weaker ranks. Kyu ranks and 1 dan wait for 20 turns in a row below 0.5% and 40 points behind; 9 dan for 10 turns below 1% and 20 points behind. Neither resigns before 40% of the board has been played.
  - The new 认输 button resigns for you. The game stays on the board with its result, and 结束并复盘 leaves AI 陪练 as before.
- An AI 陪练 game can no longer be ended or broken by accident:
  - Space no longer ends the game. Keyboard focus used to start on 结束并复盘, so Space pressed it; the training bar's buttons now never take focus. In 实战后复盘 mode, Space no longer turns on the analysis the mode hides; a note says analysis appears after the game.
  - Browsing earlier moves (arrow keys, the mouse wheel, the move list) no longer ends the game or leaves the board refusing clicks. The AI's reply, or your next click or pass, brings the board back to the game's position, with a short note; that click only brings you back and does not play. If moves of the game were deleted, AI 陪练 ends and says why instead of stopping silently.
  - Delete and Backspace no longer delete moves during the game. A note points to the new 悔棋 button, which takes back your last move and the AI's reply, or only your move while the AI is still thinking. A reply that was already on its way is dropped.
- AI 陪练 opponents up to 5 dan now play like real players of their rank. Each move is drawn straight from KataGo's human-imitation model at full temperature, as KataGo's own `gtp_human5k_example.cfg` does, and only moves rarer than 1% are damped. Before, every move went through a KataGo search that discounted or dropped the weaker human moves. Measured on 84 positions from 3-dan games, the "3 dan" opponent lost 1.42 points per move, less than the model's 9 dan (1.51), and made blunders of 10 points or more on 0.7% of moves against 3.1% for the 3-dan model. Its most common move still matched human play (about 55% of the time), so games varied as before, but it rarely made a 3-dan player's mistakes. These opponents now need one model evaluation per move instead of up to 4,096 search visits. 6 to 9 dan, which the model cannot reach without search, keep the search until they are calibrated. Near the end of a game, KataGo's own 40-visit search still decides when to pass.
- The AI's pace now follows the position: under a second for an obvious reply, a little quicker in the opening, and several seconds when players of its rank would disagree about the move. It used to wait a random 0.8–4 s whatever the position. The time setting still caps the wait.
- Polish the top menus (文件, 显示, 棋局, …) without moving anything. Every item keeps its place, name and order, and its own text for tooltips and accessibility:
  - Shortcuts written into labels, such as 保存(Ctrl+S) or 打开棋谱(O), and real accelerators show as one right-aligned column in a quieter colour, so labels line up and read cleanly. Only an exact key, optionally with modifiers, moves. Explanations such as 浮动主棋盘(保留原棋盘), mouse gestures, misspelled shortcuts, and engine or recent-file names stay as written.
  - Check marks are thin line ticks, shown only when an option is on, instead of a box beside every option. Submenus show a thin chevron. A menu reserves the column for marks and icons only when one of its items needs it, so 编辑 no longer indents its labels twice.
  - The highlight is a rounded fill inset from the menu's edges, in the theme's selection colour. Separators are the board menus' faint hairlines. Submenus get the same rounded popup as their parent menu instead of a square blue frame.
  - 显示 > 自定义布局 1/2 line up with the other labels, with the shortcut in the hint column and 设置 at the right.
  - Items, separators and popups are painted the same way under every look and feel (Windows, macOS Java looks and the native-looking mode), in all three styles. Rows are no taller than before, so the longest menu still fits small screens.
- Whole-game analysis with the experimental DirectML engine, or with OpenVINO on an Intel NPU, no longer pads every batch to the 64 positions the bundled `analysis.cfg` sets. These backends pad each batch to `nnMaxBatchSize`, and quick analysis evaluates one or two positions at a time, so each of those batches ran 64 rows. The analysis engine now starts with the batch size KataGo itself picks for a padding backend: about half the concurrent evaluations, at least 2 (2 for quick analysis). A size set in the engine command, or a smaller one in the config, is kept. This has not been measured on DirectML hardware; with Metal forced to pad the same way on an M4 Pro, quick analysis went from 2.45 s to 0.09 s per position. Engine acceptance times DirectML with the batch size the app now uses.
- Engine acceptance now times the app's whole-game quick analysis, the path the first Metal patch slowed down about 50 times without any check noticing. `probe_katago_focus.py` takes the shipped analysis config (`--analysis-config`) and runs the app's quick-analysis launch on 40 positions twice: with `nnMaxBatchSize=2` as a reference, then with the shipped config. It fails when the shipped config is more than 3 times slower and costs more than 2 s over the run. Release staging requires this timing for every hardware `PASS`, checks the config's SHA-256 and recomputes the verdict from the recorded times. The CPU and ONNX CPU smoke probes in the engine source workflows pass the pinned config.
- Release tooling can replace some engine archives and keep the rest: `stage_katago_source_release.py --targets` re-seals only the given targets against the current catalog, `transfer_pinned_source_assets.py --upload-local` uploads those archives to the Draft after checking them against the release commit's catalog, and the transfer workflow then copies only the archives the Draft still lacks. This is how the macOS engines with the Metal patch reach the next pre-release.
- macOS engine builds apply a reviewed KataGo patch (`scripts/katago_patches/metal-fixed-batch.patch`). Upstream's Metal backend compiled a new GPU program the first time each batch size appeared, freezing analysis for about 3 s several times early in each session, which also made 「评估此点」 look dead for up to 6 s. When the engine sizes its own batches, as with the bundled `gtp.cfg`, GPU batches now keep one padded size of about half the search threads: no freezes after startup, first visits after engine start in about 3.1 s instead of 5.7 s, and the same speed at the bundled 6-thread setting. A configured `nnMaxBatchSize`, such as the 64 in the bundled `analysis.cfg` that whole-game analysis uses, keeps upstream's dynamic batches: padding to it made quick analysis about 30 times slower. The patch is pinned by SHA-256, and builds, packaging, release staging and the installed-engine audit require it and the `-dirty` revision it produces. It ships with the next engine release; `metalPadBatch = false` restores the old behavior everywhere, and `true` also pads a configured size.
- When "Evaluate this point" (评估此点) is unavailable, the item keeps its name and says why: 「评估此点（需新版引擎）」 when the engine has no move focus, 「评估此点（当前不可用）」 for remote, SSH, dual-engine and game modes or while the engine starts. It used to read 「选点评估需要升级引擎」 or 「选点重点研究不可用」, so users did not recognise it as the same item. The tooltip for older engines now suggests 「只分析此点」 as a substitute as well as the full package.
- Polish the board's right-click menus without moving anything. Every item keeps its place, name and order:
  - The icons are one line-drawn set (`BoardMenuGlyph`). Each keeps the shape of the old picture, takes the menu's text colour and fades when its item is disabled. In the dark style, the bins, arrows and magnifier no longer disappear into the background. Blue and red stay only for the analysed and avoided points, as on the board.
  - Both menus use the app's popup style and thin separators. The stone menu no longer uses toolbar separators, which drew boxes around 回看, and its items have icons too.
  - 持续分析/不分析 shows its state with its icon. Its check box added a second column that pushed every icon to the right.
- Fix "Evaluate this point" (评估此点) dropping or ignoring points:
  - With a focus-capable engine, every evaluated point disappeared as soon as the automatic whole-game analysis finished. Handing the board back to the main engine resent the unchanged position, and the engine treated that as a new position. The hand-back now skips the resend while the engine is still searching the position the points belong to.
  - While analysis was paused, the item added a ring that never got any search. It now reads "Evaluate this point (analysis paused)" and asks to press Space to resume.
- Make the players' names under the board readable and darken the candidate table. The names sit on a light plate with the two small stones, in dark type, instead of black and white-outlined text straight on the wood grain (`BoardNamePlate`). The main window's candidate table takes the dark of the sidebar beside it, with a header row and alternating rows; the played move's rank carries a small "实战" tag instead of "1(实战)", each move's share of the visits has a thin bar under its number instead of a grey block behind it, and the played move's row keeps its colour by how far it falls short of the best move (`CandidateTableStyle`, which also replaces two copies of that colour ladder).
- Polish the main window's left column and the empty board. The game panel names its figures ("吻合度 54.4", "提子 4", "贴目 7.5") instead of "label:value" and a bare komi number, names the side to move after the engine status dot ("● 白走"; the dot is still green while the engine analyses and red otherwise), and no longer draws a faint line down its middle (`InfoPanelText`). The win-rate graph's legend sits in a band above the plot instead of over the curves, and the 100, 50% and 0 lines are labelled at the plot's left edge without changing its width, which move hit-testing depends on (`WinrateGraphFrame`); the current move keeps its red marker. The problem list puts the point and the points lost on one line in larger type when they fit, and still wraps in narrow sidebars. An empty board says how to start: open a game, drop an SGF file on the window, or start a new game (`EmptyBoardHint`).
- Redraw the top toolbar's icons as one set (`ToolbarGlyph`). They had mixed flat, filled, 3D and coloured styles at different sizes, and in the Apple style the brightening filter turned the selected stones and the blue and red analysis-area marks into blank white squares. Every icon keeps its place and its shape and is now a line drawing with one stroke weight, in the toolbar's text colour on light and dark toolbars, drawn rather than scaled so it stays sharp at every display scale. Colour remains where it means something on the board: the black and white stones, the blue and red area marks and the green move-rating dot. A selected tool shows a tinted backing instead of a glow picture. The selected stones and the alternate-stones icon were 24-pixel pictures in 20- and 22-pixel buttons, so their edges were cut off; every icon now has the toolbar's icon size. The pass button's tooltip now names its shortcut, P.
- Tidy the main window's toolbars. The top toolbar no longer grows a second row a few seconds after startup: the aggressiveness and breadth fields keep their place, disabled, while the engine starts, and are enabled once KataGo is ready (other engines still hide them). Its text buttons are as wide as their labels, the komi group lost its empty tail and the separators are narrower, so at 1600 pixels the whole toolbar now fits in one row (it needed about 1,740). The engine name in the window menu ends with a chevron, like the menu it opens. On the bottom toolbar, back one and forward one move sit next to each other, with the move field and Go to after the navigation buttons. The bottom Sync button is off by default because the window menu has a Sync menu with the same commands; Bottom tool bar > Custom items > Sync turns it back on, and its one command that was missing from the Sync menu, Force end trial mode, is now under Sync > Web Viewer as well. English labels read as words (StartAnalyze → Analyze, Goto → Go to, RawNet → Policy, WRN → Breadth and others), and Thai names breadth instead of WRN.
- Fix display errors in the main window without moving anything:
  - **Title:** during automatic analysis it no longer runs the app and engine names together ("next-devKataGo - 搜索/秒"), it leaves out the speed when there is none, and player names are spaced.
  - **Status line under the win-rate bar:** it names the side that leads ("白领先 7.3 目" instead of "黑领先:-7.3"). The previous move appears on the same line only when both positions are analysed, without the empty "(#  )" or the "-0.0%" at the start.
  - **Win-rate bar:** warm white with an outline and a 50% mark, so Black's share stays visible. Its ends read "黑 0.8%" and "白 99.2%".
  - **Engine status at the bottom left:** one small line (engine, state, then the Space and X hints or the retry notice) instead of three large lines that covered the panel above, which now gets that space back. While a loaded game is analysed it says "自动分析中" instead of "分析 暂停".
  - **Rules names:** KataGo's Japanese and Korean rules are named again instead of "其他规则". The presets had `friendlyPassOk` true where KataGo reports false, and territory rules now keep their name whatever that field says.
  - **Candidate tables:** for the played move, the winrate and score read "99.2 ↓0.1" instead of being cut to "↓-0.1(99...".
- Fix the comment area on macOS losing Latin characters. The "%" after every winrate was always missing ("胜率: 5.1"), and digits or colons often were too ("不确定度: 1  .0" instead of "不确定度: 13.0"). Swing drew its HTML text through the JDK path that, on macOS, draws the Latin glyphs of a run blank when the run also contains Chinese, Japanese or Korean, and keeps them cached blank for that font size, the bug fixed for the status line in #646. HTML text panes now resolve their fonts with kerning, which makes Swing lay the text out with TextLayout (`KerningHtmlEditorKit`). This also covers the message dialogs, the help texts in the settings dialogs and the AI commentary dialog.
- Make the automatic analysis of an opened game start every time, and draw the winrate graph while whole-game analysis runs. When the analysis engine in the settings named a program that is gone, typically the KataGo of a copy of the app that was since moved, updated or deleted, automatic analysis failed silently on every game and the main engine went back to pondering, so some games were analysed and others not. Such a setting was kept unchanged once it had been marked as customised. At startup a command for a bundled KataGo that is gone now points at the running app's own KataGo, keeping its options, and automatic analysis uses the main engine while the configured program still cannot be found (logged once in `logs/app.log`). Flash analysis (整盘速览) now fills in the winrate and score graph as results arrive, like automatic analysis, instead of only when it has finished. Relocating bundled commands moved out of `Config` into `BundledCommandRelocation`.
- Add game phases and tick marks to the strength estimate. The rank scale is now one unbroken track, filled in one colour up to the rank, that marks 10k, 5k, 1d to 9d, pro, top pro and AI, and gives the amateur dan ranks the most room, so 1 kyu and 2 dan no longer sit almost on top of each other. A new Phases tab splits the game into opening (moves 1–60), middle game (61–150) and endgame. Each phase has a card with both sides’ estimated ranks on one ruler, each side’s best and weakest phase marked, and which side played it better; a line below names the phase each side may want to review. The two boundaries can be dragged or moved with the arrow keys and are kept for later games, and Detailed data lists every figure of every phase. Centred texts in the dialog are measured once per width, which makes its layout about ten times faster.
- Make the strength estimate dialog usable without knowing its jargon. A game without analysis shows "This game has not been analyzed yet" with one "Analyze the whole game" button and its progress, and the figures appear and keep updating while the analysis runs; a partly analyzed game says how many moves are analyzed and offers to analyze the rest, and a game too short to estimate says so. The models are called Standard and Human style, each with a sentence on what it does, in a menu that also holds Import model. Choosing the human-style model before its one-time calculation explains it and shows one "Start calculation" button with progress, the switch that prepares it while analyzing (with what that costs) and the size of the first download; otherwise its status and switch appear only under the human-style figures. Every figure explains itself under the pointer (info marks on the comparison rows and detail rows, the match ring and the confidence chip), and the subtitles, notes and HumanSL statuses are reworded in plain language in all eight languages. The dialog's main-window glue moved out of `LizzieFrame` into `StrengthDialogHost`.
- Redesign the strength estimate dialog as a match report: Black on the left and White on the right, told apart by stones instead of red and blue. The assessment shows each side's rank in large type on a scale from amateur to AI with a confidence chip, over paired bars for the AI top-choice rate, good-move rate, score losses and moves. The match view shows each side's overall match as a ring and the move hit map as lanes of marks that highlight under the pointer. The performance view mirrors both sides' move-quality tiers around the tier names, with a strip per side for the whole mix. The detail data is one table for both sides, and the match details chart uses the same look. The dialog has a dark variant for the Apple style, uses one segmented control instead of three framed tabs, updates in place when the model changes or HumanSL is run instead of closing and reopening, and scrolls instead of clipping on small screens or with large fonts. Ranks no longer lose their digits on macOS ("业余9 0段"). Fix the rank ranges "4-5 那么" and "11-15 九" in Chinese, the Thai placeholder "__ระดับ LZP0__", Korean using one word for both mistakes and blunders, and the machine-translated dialog texts in Japanese and Korean. The dialog moved out of `LizzieFrame` (3,200 lines) into `Strength*` classes, and its 1.9 MB of background and stone images are no longer bundled.
- Redesign the problem-move panel. The Black/White switch now shows how many problem moves each side has and sits beside a sort chip that opens a menu, replacing the white drop-down box. Each row shows a stone with the move number, the point and the points lost, with the winrate loss on the right above a bar scaled to the worst move. The repeated colour, "winrate loss" caption and visit count left the row but stay in its tooltip and screen-reader name. The evaluation status reads as a status line instead of a button, the scroll bar is slim, and narrow sidebars and large fonts still wrap instead of clipping.
- Fix the status line under the win-rate bar on macOS losing its digits and punctuation ("黑领先 4 最后一手 19 8" instead of "黑领先:0.4 最后一手: +19.8%"). When the label was too large and its size was capped, the font was rebuilt without its kerning attribute. That sent the text through a JDK drawing path that, on macOS, draws Latin glyphs blank in a string that also needs a fallback font for Chinese, Japanese or Korean, and keeps the blank glyphs cached for that size. The fitted font now keeps the attribute (`FittedFont`), which also fixes the same labels in the move-list window.
- Add an architecture budget to CI: the largest classes, references to the global `Lizzie.*` fields and engine-to-UI package references may no longer grow (`scripts/check_architecture_budget.py`, ADR 0004). Add `AGENTS.md` with the build, threading, security, test and localization rules for coding agents, and design notes for the engine-session refactor, on-demand browser/readboard components and slimmer release assets.
- Fix the KataGo rules editor sometimes staying open after the engine confirmed the new rules: the status watcher could read the confirmed result a moment before the rules operation completed, then never look again.
- Format all Java sources once with google-java-format and check formatting in CI (`fmt:check` in the java-linux job). The formatting commit is listed in `.git-blame-ignore-revs`.
- Restore the toolbar breadth checkbox and value field after ordinary KataGo startup, restart and engine switching, without opening the Fox AI dialog. Breadth no longer requires unrelated flash-analysis commands; exclusive-task, failed-restoration and genmove restrictions remain in force.
- Move KataGo heat-map (`kata-raw-nn`) parsing out of `Leelaz` into `analysis.gtp.KataRawNnResponse`, the first step of the engine-session refactor, with tests that lock the existing heat-map behaviour; the pass probability is no longer read as a policy row on 2-wide boards.
- Drop the unmaintained SwingX 1.6.4 library (2013); its only remaining use was an operating-system check, now `OsPlatform`. This also removes the dead `maven.java.net` repository its parent POM declared, which made every CI build log a PKIX error while resolving dependencies.
- Make the test suite faster and more deterministic: silent-engine snapshot tests use a 1.5 s instead of 5 s no-response timeout (the exact-snapshot contract tests drop from 46 s to 19 s), JDK classes that cache `os.name` are initialized before tests simulate another platform, and five races that failed on loaded CI runners now wait for the state they assert. An opt-in parallel mode (`-Dsurefire.forkCount=0.5C -Dsurefire.reuseForks=false`) runs one JVM per test class for local use.
- Translate the Diagnostics and Logs dialog, the flash-analysis engine reuse messages and the ReadBoard WebSocket notice into Japanese, Korean and Thai, and the flash-analysis and ReadBoard texts into Traditional Chinese (Taiwan and Hong Kong); 320 strings in total were still shown in English. `scripts/list_untranslated_strings.py` lists strings that are still English in a translated bundle, and CI now fails when a change adds new ones.
- Record everything the app prints to standard error in `logs/app.log` as well. Hundreds of older error paths only call `printStackTrace()`, which a packaged app without a console used to lose, so those failures were missing from exported diagnostic bundles.
- Analyze pasted or imported setup positions with engines that cannot read local snapshot files, such as KataGo started from a `.bat`/script launcher or over SSH (#627): when the engine advertises KataGo's `set_position`, the position is restored in-band and real moves are replayed, without writing a host file. White-to-play setups without later moves use this path only for engines that are told the side to play in each analysis command; other engines still stop with the existing explanation.
- Run frame refreshes requested by engine output threads on the Swing event thread, coalescing bursts into one pass, so appending engine winrates to comments and other UI updates no longer race the event thread.
- Update the embedded browser (JCEF) from Chromium 127 (July 2024) to Chromium 152.0.7977.83 (jcefmaven 152.0.6), and accept Yike sync messages only from the main frame of a Yike window on yikeweiqi.com, so other pages or embedded frames cannot inject games. Every JCEF version and checksum pin in the packaging scripts and release workflows is now checked against the bundle helper by a script test.
- Remove the retired lizzieyzy.cn kifu-sharing features (account login, SGF upload and editing, private and public kifu search). The server no longer resolves, yet function search could still send the saved account to it over raw TCP with a key published in the source; the domain is due to expire on 2026-12-24. Existing configurations drop the plaintext share account and share labels; Ctrl+E and Alt+B stay unbound.
- Verify SSH host keys for remote engines: the first key a server presents is recorded in `ssh/known_hosts` under the work directory, and a changed key or a switched key type is refused before any password is sent. Remote engines now use Apache MINA SSHD instead of ganymed-ssh2, so modern OpenSSH servers and OpenSSH-format key files work, key-file login honours the configured port, and the user's own `~/.ssh` keys and config are never used. Saved SSH passwords move from the configuration file into macOS Keychain, Windows DPAPI or the Linux Secret Service (kept as before when none is available).
- Stop retrying permanent snapshot-file preparation failures after SGF imports, keep the imported position available, and explain the failure. Continue retries the full acknowledged synchronization so a direct local engine or repaired directory permissions can recover; script/SSH launchers still cannot read host snapshot paths.
- Hint in the KataGo rules dialog when the current game's komi differs from KataGo's default for the edited rules (6.5 for territory scoring, 7 for New Zealand and button rules, otherwise 7.5). The hint updates as rules are edited and when the dialog is reactivated, never changes komi, and is not shown for handicap games or when composing engine-game match rules.
- Keep ordinary candidate markers visible while the first asynchronous variation preview is pending, including reentry after leaving a candidate and floating-board editing. Replace them only when the complete preview is ready; preserve existing previews during direct candidate switches and discard cancelled results.
- Play the stone and capture sounds for engine-vs-engine game moves and passes again, in both genmove and analysis modes, controlled by the existing global "Play sound" setting. Only committed moves sound; rejected or stale engine responses stay silent, and an audio failure never affects the game. Stepping forward through history now always chooses the sound of the move being entered.
- Accept common SGF rule declarations such as `RU[cn]`, `RU[jp]`, `RU[kr]`, `RU[GOE]`, `RU[NZ]` and KataGo's own preset spellings instead of rejecting them as invalid; unknown declarations still stop with the existing rules prompt. Add the Ing (GOE) and ancient-territory presets with KataGo's fields, an Ing (GOE) button and engine-game option, and a "pass before removing dead stones" row in both rules editors (disabled for territory scoring while preserving imported values). The Tromp-Taylor button now sets KataGo's no handicap bonus. Rule names in the status bar and saved SGF comments are shown only for exact presets, so Tromp-Taylor, AGA and New Zealand rules are no longer labelled Chinese.
- Clear the local board when starting contribution mode without forwarding to the stopped foreground engine, preventing a delayed self-conflict warning while preserving real exclusive-task admission and SGF synchronization.
- Report contribution startup failures under their original launch identity, distinguish missing distributed-training support from server-rejected builds, and stop retrying either permanent error. Require parsed game data or a valid game-start log for readiness, drain both output streams within bounded deadlines, limit each user start to four launches including one evidence-based certificate fallback, and discard stopped or replaced UI callbacks.
- Classify contribution errors independently of UI dispatch so a busy event thread cannot turn a permanent failure into another launch. Preserve bounded stream cleanup, genuine readiness, and attempt-fenced game display and failure presentation.
- Keep contribution output notifications outside the lifecycle monitor so errors and EOF finish while a modal dialog remains open; fence dismissal against the original owner and launch. Restrict automatic certificate-path recovery to local commands, and require contribution startup and local-clear regressions in the Linux and Windows engine-process CI gates.
- Add "Configured Engines..." to KataGo distributed training settings: choose a saved local engine to fill in only its executable path (normalized and quoted as one argument). Analysis arguments, models and configs are not copied; selection does not save, launch or switch engines. The saved path is a snapshot that later engine-list edits do not change. Remote, launcher, script, empty and missing-file entries are shown with a reason and cannot be filled; listing an engine does not mean it supports contribution.
- Use cancellable, visits-bounded searches for eligible built-in Zhizi automatic game analysis after checking capabilities. Accept complete natural results without per-position stop commands, and restore borrowed search parameters, rules and the latest board before cancellation or manual handoff releases the engine. Other engines and unsupported sessions retain their existing analysis path.
- Set eligible Zhizi finite searches to a 10000-centisecond output interval so natural completion exposes the completed root budget. Keep continuous-analysis intervals, visit budgets, terminal validation and recovery unchanged.
- Complete failed Zhizi search-parameter restoration outside engine arbitration so concurrent snapshot navigation can return and the borrowed engine is marked unavailable exactly once.
- Align automatic-analysis regression fixtures with explicit request ownership and reader-bound foreground leases, preserving manual Yike completion coverage.
- Prevent repeated bundled KataGo profile creation across restarts when a manual default engine and a custom analysis command use different weights. Keep Auto Setup ownership and selected weights, including explicitly selected legacy weights, across renaming and restarts, while preserving user-edited commands and historical entries with unconfirmed ownership.
- Keep pending game imports recoverable when comparison mode is exited during a local engine restart, including exit before the queued restart notification is delivered: retain the admitted restart token, then reconfirm the remaining primary engine before releasing the import gate and resuming analysis.
- Show foreground recovery guidance on the first Space or analysis-button operation even while automatic quick analysis is retrying after a failed handback; preserve pause intent and the task, while keeping normal pause/cancel behavior for healthy engines.
- Resume pending game synchronization after an explicit local engine restart, so changing games after a quick-analysis handback failure no longer leaves Space blocked. Reconfirm rules and position on the new reader, preserve pause and failure gates, and reject stale game or engine callbacks.
- Explain when automatic quick analysis cannot hand the current engine back: show "engine state not restored" with the failed stop/restore cause and the existing "Restart current engine" menu action instead of "Engine is loading...", and answer Space or the analysis button with one recovery prompt while keeping analysis paused, the engine unchanged and replaced engines unaffected.
- Prevent false initial-stop timeouts when quick analysis borrows the foreground engine: keep unrelated numbered replies out of the move-focus probe, and let the lease consume its own stop acknowledgement and terminal boundary on the current reader. Preserve pause, stale-reader isolation, and existing failure handling.
- Let the Beta channel compare signed GitHub stable and test releases, select the highest valid version without downgrading or falling back to an older package, and retain a non-blocking warning when one candidate cannot be checked; preserve Stable source selection (#578).
- Show complete suggestion rows within the existing panel, tightening safe cell padding only when it fits one more row; align settled scrolling to row boundaries and preserve browsing position through resizing without changing fonts, candidates, or board sizes (#579).
- Keep live suggestion values and ordering current while retaining row density and browsing position; clamp the scroll range when candidates shrink or clear, including refreshes during resizing and panel collapse (#579).
- Serialize candidate-preview retirement with final publication and renderer consumption; run variation replay steps on the event thread and prevent cancelled replays from restoring stale lengths (#347).
- Preserve independent-board candidate actions when opening their context menu, step backward within the captured preview limit, route ordinary arrow keys to the selected second-engine variation, and retire all candidate previews when entering score mode (#347).
- Keep asynchronous candidate-preview navigation at its first move without changing game history; preserve frozen PVs and advance independent-board and second-engine Page Down requests through the selected variation (#347, #577).
- Keep source-board floating candidates visible until the first complete preview is ready, preserving the transparent overlay's hover target during background generation (#347).
- Keep the complete visible variation while stepping or switching candidates, then replace it atomically; comma and middle-click apply the visible PV and prefix while its replacement is pending (#347).
- Keep candidate markers hidden while a selected preview is being replaced after stepping or switching points, preventing a flash of idle suggestions before the next complete image (#347).
- Generate complete candidate previews immediately on one shared background worker for main, independent and floating boards; coalesce analysis updates fairly, publish matching stones/shadows/annotations together, and reject stale results after input, geometry or window-lifecycle changes. Remove the fixed hover delay (#347).
- Capture candidate variations before their preview is drawn so stepping, comma and middle-click use the selected PV; share isolated stone, shadow and annotation generation across main, independent and floating boards, preserving frozen PV visits and explicit refresh (#347).
- Capture isolated variation inputs for main, independent, floating, history-hint and secondary-board previews; simulate long variations with private working arrays while preserving PASS, snapshot metadata and existing display scheduling (#347).
- Wait for the current engine startup/switch to settle before restoring an imported SGF, so games opened with the Windows EXE also start their automatic quick curve; preserve pause and retire continuations after replacement, failure or shutdown.
- Decode professional ranks in Fox game lists as P1–P9 instead of 83–91 dan, while preserving online dan and kyu ranks for both players.
- Keep simplified variation-tree images bound to the current history, selected node and viewport; discard late drawings and repaint accepted images on the event thread without requiring another input. Preserve preview selection, navigation and viewport clipping.
- Scan diagnostic secret lines once during export, preserving complete GTP analysis lines, credential and path redaction, session aliases, and original line endings.
- Restore Chinese ancient rules as a standard engine-game choice using the existing `stone-scoring` preset; recognize equivalent saved rules without rewriting them and preserve non-equivalent custom parameters (#549).
- Show concrete engine startup and synchronization failures in the diagnostic window even without a retained startup snapshot; preserve the failure reason and command in copy/export actions and keep local Windows command-line diagnostics available (#555).
- Restore opt-in search-thread changes for the current KataGo process, including explicit remote requests, with confirmed readback and stale-process isolation; preserve saved thread sources and PDA/WRN auto-loading, and make advanced-parameter help match the dialog theme (#558).
- Keep the advanced-parameter thread checkbox reachable by mouse at all three font sizes (#558).
- Keep confirmed thread overrides checked for the same KataGo process; clearing a local override confirms a safe restart from the current saved source, while remote connections explain unsupported source reset and unknown reconnect provenance (#558).
- Show the current process's last confirmed thread count immediately while refreshing in the background; indicate loading or refresh failure and preserve in-progress edits (#558).
- Preserve imported SGF dates, including Fox downloads, partial dates and multi-day expressions, through editing and repeated/background saves; leave missing dates unknown while new games retain their creation date.
- Restore the textured default workspace background consistently for fresh and existing configurations, while retaining explicit background colors and custom wallpaper.
- Update the bundled default B11 Transformer to the official 2026-09-12 `kata1-tf3-b11c768-s11750M-d6216M.bin.gz`, with verified size and SHA-256 across Auto Setup and all release package checks; keep the pinned KataGo engine, optional B10/HumanSL models, and existing users' weights unchanged by core updates.
- Capture SGF save snapshots on the event thread and write them safely in the background; unify save dialogs under the main window and preserve the full live variation tree when exporting the current branch.
- Preserve all installed weight candidates after switching models, so bundled weights remain available without downloading again.
- Keep ownership-display menus and SGF save dialogs usable without an engine; restore save modes and the original analysis state when saving is cancelled.
- Resolve SGF extensions before overwrite confirmation in all save dialogs, preventing silent overwrites for names containing `sgf` and duplicate uppercase extensions.
- Keep diagnostics actions visible after asynchronous size estimates, constrain long metadata to the available width, and fit initial dialog bounds to the current monitor work area (#536).
- Explain engine startup failures with Windows status codes, explicit DLL errors, existing KataGo runtime checks, and bounded process output; preserve the error and command layout with separate details, copy, and diagnostic export actions (#535).
- Record startup errors and late process output in ordinary WARN logs under the original engine and launch identity, and attach the displayed failure to diagnostic bundles (#535).
- Review measured live/whole-game KataGo recommendations separately, bind them to engine/model/config/GPU identity, and apply or restore scene-specific settings only after explicit confirmation.
- Simplify engine-game rule selection to Chinese, Japanese/Korean, AGA/BGA, New Zealand, Tromp-Taylor, and custom rules; use matching rule-family names during games and preserve saved preset parameters until explicitly changed (#516).
- Keep komi controls, game-info editing, and score-graph rendering usable after engine startup failure; cover the no-engine desktop paths in required regression tests.
- Apply current-game komi changes through both engine participants, wait for acknowledgements before committing, preserve pause intent, and end the batch safely if synchronization fails (#518).
- Fix ordinary batch analysis rejecting its own startup; preserve batch ownership through engine handoff and file continuation, and clear failed or cancelled batches so analysis can be retried (#523).
- Preserve renamed bundled KataGo profiles across restarts, Auto Setup, and portable package moves using persistent ownership independent of display names; protect user-edited commands (#521).
- Read KataGo model names from bounded native weight headers so replacing `default.bin.gz` updates engine labels, Auto Setup catalog matching, and compatibility hints; retain distinct names for unlisted Transformer versions (#520).
- Replace isolated point evaluation with equal-weight, multi-point focus in the current supported local KataGo search tree; adopt and save ordinary analysis, retain attention outlines after targets complete, and preserve focus across identical ReadBoard frames (#414).
- Use KataGo root visits for ordinary analysis totals and budgets, preserve same-stream evaluation updates and cache depth protection, and retain exact root counts, candidate order, and edge allocation in SGF (#414).
- Allow fresh point evaluation after returning to an accepted ReadBoard position without another helper frame; validate position semantics and engine synchronization, and reject malformed or stale frame publication (#444).
- Add isolated local Xvfb acceptance for real search input, settings persistence across JVM restarts, and real-engine quick-analysis completion and pause, with bounded execution and retained evidence.
- Require seven real engine-process lifecycle and recovery scenarios on Linux/Xvfb and native Windows, with isolated evidence and fail-closed CI aggregation.
- Check each release shell script individually, discover ordinary integration tests automatically, and reject missing or skipped critical logging-smoke execution in local and hosted CI.
- Restore exact engine snapshots through safe per-target absolute or working-directory-relative SGF addresses, rejecting isolated local transports before board mutation.
- Show engine startup diagnostics for primary and secondary engines outside first-launch onboarding, including missing executable and model/config path errors.
- Ignore delayed primary-engine startup diagnostics after engine replacement so they cannot cancel a newer engine game.
- Preserve GTP command-list response boundaries during startup so automatic analysis and foreground quick-analysis capability discovery complete correctly.
- Resume current-position analysis after automatic SGF quick analysis returns a shared foreground engine, preserving user pause and avoiding duplicate position replay or analysis restarts.
- Prevent engine startup retry from deadlocking with a queued synchronization-failure presentation.
- Add offline function search from the toolbar and `Ctrl+K`/`Command+K`, covering menus, toolbars, board actions, and settings with multilingual queries, categorized browsing, current availability, and navigation to original controls and confirmations.
- Require the Linux/Xvfb desktop CI lane to execute both localized real-input function-search chains, rejecting missing or skipped evidence while retaining bounded probe artifacts and result counts.
- Require the Linux/Xvfb desktop CI lane to execute the production engine startup, snapshot restore, analysis, stop, and clean-quit process smoke, rejecting missing or skipped lifecycle evidence.
- Bind searchable settings to stable IDs when their rows are created, preserving navigation across row reordering and checking for missing or duplicate targets.
- Refresh function-search availability outside result painting while keeping visible states live and rechecking actions before activation.
- Preserve Windows CI stall evidence with per-test JUnit events, paired process/thread snapshots, bounded execution, and owned-process cleanup before artifact upload.
- Split CI into independent repository, script, and Java jobs with grouped local entrypoints and a unified `ci-required` gate; retain the existing required-check names during migration.
- Save KataGo rule preferences on confirmation and close the settings dialog after successful application.
- Keep SGF rule-failure navigation local until explicit confirmed restore, preserve trial-return rules and positions, and resume both comparison engines after synchronization (#448).
- Keep thread-source controls visible and locked to CFG for empty or unrecognized local targets, without explanatory warnings (#437).
- Keep remote connection labels with their fields after deferred Swing layout, align thread-source controls with the adjacent benchmark action, and show only current thread policy status (#437).
- Keep thread-source labels aligned across local and remote entries, use concise benchmark button labels, and clear stale tooltips when switching targets (#437).
- Avoid save prompts and accidental engine creation for untouched empty rows in engine settings, while preserving edits to new and existing entries (#437).
- Start KataGo performance checks from any saved local engine entry, preserve its complete GTP inputs and independent results, and support GTP-only configurations and first-run OpenCL tuning (#437).
- Store KataGo thread sources, recommendations, and Apple tuning profiles per saved engine; preserve entry ownership across edits and benchmarks, and safely reload CFG settings (#437).
- Keep remote KataGo search threads server-managed across initialization, local benchmark results, and legacy thread settings; show remote ownership in engine details and reject nonlocal benchmark targets (#437).
- Preserve local moves after stopping ReadBoard synchronization and retire obsolete placement confirmations and queued output (#432).
- Preserved diagnostic log record boundaries and stack-trace whitespace during export without weakening privacy redaction (#431).
- Made diagnostic export estimates describe approximate uncompressed content, pruned proven irrelevant archives before reading using native Windows file identities where needed, and displayed publication success independently of folder opening (#430).
- Wait for confirmed engine positions before starting ordinary analysis after moves and history navigation; resume ReadBoard analysis once at the final synchronized position while preserving valid node caches (#429).
- Preserved complete custom match rules, distinguished official rule presets, clarified unverified participation, and enabled match-rule inspection from independent-board shortcuts (#422).
- Run custom local KataGo `benchmark` commands as slot-owned, cancellable tasks with streamed output and exit-code results (#423).
- Prevented KataGo rules dialogs from interrupting engine games while their participants remain occupied (#421).
- Added CI to build the shaded jar on push and pull request.
- Added Dependabot configuration for Maven dependencies and GitHub Actions.
- Added installation, troubleshooting, maintenance, and release-process docs.
- Added Japanese and Korean install guides.
- Unified the project wording around Fox nickname search and novice-friendly account wording in docs and UI strings.
- Polished the repository landing page, community health files, and multilingual README structure.
- Added internal release metadata generation and a validator that keeps public releases limited to the main novice-friendly assets.

## 2026-04-17 - Board Sync Entry Recovery

- Restored the Windows board sync entry so users can still open the feature even when the legacy native `readboard` folder is not bundled.
- Added a guided download prompt that opens the maintained `readboard` releases page when the legacy helper is missing.
- Added regression tests for the missing-helper prompt flow and the new localization keys.
- Historical tag at that time: `2.5.3-next-2026-04-17.1`

## 2026-03-16 - First Maintained Release

- Restored Fox kifu sync for the maintained fork.
- Switched the user-facing flow to Fox ID input instead of username lookup.
- Published practical multi-platform release assets, including Windows, macOS, and Linux packages.
- Added macOS Intel dmg packaging alongside Apple Silicon packaging.
- Bundled KataGo and default weights in the main all-in-one packages.
- Historical tag at that time: `2.5.3-next-foxuid-2026-03-16.2`
