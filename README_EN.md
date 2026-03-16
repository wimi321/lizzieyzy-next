# LizzieYzy Next-FoxUID - GUI for Game of Go
## Read This First
**In the original LizzieYzy, Fox kifu sync no longer worked reliably. This maintained fork restores that feature, and now lets you fetch the latest public Fox games by entering a Fox ID directly.**

## Maintenance Highlights
**This is the actively maintained LizzieYzy fork. The most important updates are:**

* **Fox kifu sync fixed**: the original flow was broken, so this fork switched to direct Fox H5 API fetching.
* **Fetch by Fox ID**: username lookup was removed on purpose. Enter a Fox ID to get the latest public games.
* **Multi-platform release packages restored**: Windows32/Windows64/Mac+Linux/Other `without.engine` bundles.
* **Mac dual-chip startup clarified**: includes `start-mac-arm64.sh`, `start-mac-amd64.sh`, plus auto-detect launcher.

## Quick Start In 3 Steps
1. Download the package for your system from [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases).
2. `with-katago` bundles can run right after unzip or install. `without.engine` bundles need your own engine setup.
3. Open the app and use the Fox sync entry to fetch the latest public game by Fox ID.

![screenshot_en](/screenshot_en.png?raw=true)

LizzieYzy Next-FoxUID is a maintained fork of LizzieYzy focused on reviving broken features and keeping the project usable. This branch restores Fox kifu sync and changes it to a simpler Fox ID based flow.

LizzieYzy is a graphical interface modified from [Lizzie](https://github.com/featurecat/lizzie), allows loading various engines like: [Katago](https://github.com/lightvector/KataGo)、[LeelaZero](https://github.com/leela-zero/leela-zero)、[Leela](https://github.com/gcp/Leela)、[ZenGTP](https://github.com/yzyray/ZenGTP)、[SAI](http://sai.unich.it)、[Pachi](https://github.com/pasky/pachi) or other GTP engines.

We have added some new features on Lizzie's basis: **Hawk Eye, Flash Analyze, Batch Analyze, Estimate, Board Synchronization(only windows), Engine Game, Tsumego Frame, Double Engine Mode, Visualized KataGo Distributed Training** and adjusted some details, supported retina monitor, avoided getting fuzzy by scaled.
#
* New features

  * **Hawk Eye**: Get accuracy, winrate difference, score difference and blunder moves based on the differences between engine candidates and actual moves and display in chart.

  * **Flash Analyze**: Depend on Katago's analysis mode, analyze all kifus in parallel and get winrate graph candidates rapidly, support batch analyze.

  * **Batch Analyze**: Support batch analyze kifus by GTP engine or Katago's analysis mode.

  * **Estimate**: Use Katago(default)'s command:`kata-raw-nn` or ZenGTP's command `territory` to get raw territory, support automatically estimate after each move.

  * **Board Synchronization(C#)**: [Repository](https://github.com/yzyray/readboard) Two mode: foreground(board can't be moved or covered)/backgorund. Special optimizations have been made for FoxWQ、TYGEM、SINA platforms, allowing one-click synchronization, while synchronizing from other platforms or from a picture or gif you need to select the region of the board. Support automatically carrying moves for both sides(developed by C#, only support Windows).

  * **Board Synchronization(Java)**: [Repository](https://github.com/yzyray/readboard_Boofcv) Foreground only, need to select the region contains the board. Support automatically carrying moves for both sides.

  * **Engine Game**: Allow a game or multiple games bettween two engines. Support loading some SGF files as opening books. Support various commands:`lz-analyze`, `kata-analyze`, `genmove` to get moves. For multiple games it will automatically calculate some statistics: elo, stdev interval and etc.

  * **Tsumego Analysis**: Support capture tsumego in part of goban, and automatically generate other part of stones help engine analyze in right area, refer to [Analyze]-[Tsumego frame] or [Capture tsumego] or [Tsumego] button in toolbar.

  * **Double Engine Mode**: Support loading two engines and analyze synchronously, which is convenient for comparison.

  * **Visualized KataGo Distributed Training**: Visualized official KataGo training, all games(playing or completed) can be watched.

#
 * Instruction for use: https://github.com/yzyray/lizzieyzy/blob/main/readme_en.pdf (If you are reading under translate to Japanese or Korean, please go to original link, translated link will not work)
 * Other jar source code links: [foxRequestQ.jar](https://github.com/yzyray/FoxRequest) [InVisibleFrame.jar](https://github.com/yzyray/testbuffer) [CaptureTsumeGo.jar](https://github.com/yzyray/captureTsumeGo/blob/main/README.md)
