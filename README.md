# LizzieYzy Next-FoxUID - 围棋引擎界面(中文,[English](#en),[日本語](#ja),[한국어](#ko))
## 先看这一句
**原来的 LizzieYzy 里，“同步腾讯野狐棋谱”这个功能已经不好用了。这个维护版把它补回来了，现在可以直接输入野狐ID，获取最新公开棋谱。**

## 维护版重点更新
**这个项目会继续维护，当前最重要的改动如下：**

* **野狐棋谱同步已修复**：原项目这块已经失效，现在改为直连 Fox H5 API 抓取棋谱。
* **改成输入野狐ID获取**：不再走用户名检索，直接输入野狐ID即可同步最新可见棋谱。
* **多平台发布包已恢复**：提供 Windows32/Windows64/Mac+Linux/Other 四类 `without.engine` 包。
* **Mac 双芯片启动已标注**：发布包内新增 `start-mac-arm64.sh`、`start-mac-amd64.sh` 和自动识别启动脚本。

## 3步快速开始
1. 到 [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) 下载适合自己系统的包。
2. `with-katago` 整合包下载后直接解压或安装即可用；`without.engine` 版本需要自己配置引擎。
3. 打开程序后，使用“野狐棋谱（输入野狐ID获取）”功能同步最新公开棋谱。

![screenshot](/screenshot.png?raw=true)

LizzieYzy Next-FoxUID 是 LizzieYzy 的维护分支，专门接手原项目里已经失效或没人继续维护的功能。当前已经修复腾讯野狐棋谱同步，并改成更直接的“输入野狐ID获取”。

LizzieYzy 是一个引擎界面,修改自[Lizzie](https://github.com/featurecat/lizzie),可加载围棋引擎:[Katago](https://github.com/lightvector/KataGo)、[LeelaZero](https://github.com/leela-zero/leela-zero)、[Leela](https://github.com/gcp/Leela)、[ZenGTP](https://github.com/yzyray/ZenGTP)、[SAI](http://sai.unich.it)、[Pachi](https://github.com/pasky/pachi)以及其他标准GTP引擎。

在Lizzie的基础上增加了一些新功能:**鹰眼分析,闪电分析,批量分析,形势判断,棋盘同步,引擎对局,死活题分析,双引擎模式,可视化KataGo分布式训练**,以及一些细节修改,可完美支持高分辨率,不会因为系统缩放而显示模糊
#
* 新功能

  * **鹰眼分析**: 根据AI的选点胜率、计算量、目差,与棋谱中实际落子做比较,得出吻合度,胜率波动,目差波动,失误手等信息并以图表化的形式展示

  * **闪电分析**: 使用Katago的analysis模式,并行分析整个棋谱,快速得出胜率图,选点等信息,支持批量分析

  * **批量分析**: 支持打包棋谱按顺序使用GTP引擎分析,或使用Katago的analysis模式分析

  * **形式判断**: 使用Katago(默认)的`kata-raw-nn`命令或ZenGTP的`territory`命令获取粗略的领地判断,支持每一步自动形势判断

  * **棋盘同步(C#)**: [相关仓库](https://github.com/yzyray/readboard)前台(不可移动,遮挡)/后台(不占用鼠标,可遮挡)两种模式,特别优化了野狐、弈城、新浪平台可一键同步,其他平台或图片动画等需框选棋盘(将棋盘选在内即可,比棋盘大很多也没关系),支持双向同步、自动落子(溜狗),采用C#语言,因此只支持Windows

  * **棋盘同步(Java)**: [相关仓库](https://github.com/yzyray/readboard_Boofcv)仅前台,需框选棋盘(选择比棋盘大一些的区域),支持双向同步、自动落子(溜狗)

  * **引擎对局**: 两个引擎之间的单盘/多盘对局,可加载多个SGF作为开局,支持使用不同命令获取引擎选点:`lz-analyze`、`kata-analyze`、`genmove`,多盘对局将会自动计算elo、标准差区间等信息

  * **死活题分析**: 支持抓取局部棋盘上的死活题,并自动生成死活题框架以便AI在正确的范围内思考,详见[菜单]-[分析]-[死活题]以及[抓取死活题],或工具栏的最右侧[死活]按钮

  * **双引擎模式**: 支持同时加载两个引擎并同步分析对比

  * **可视化KataGo分布式训练**: 将KataGo官方的分布式训练可视化,可以看到每一局正在进行和已经训练完成的对局
#
 * [使用简介](https://github.com/yzyray/lizzieyzy/blob/main/readme_cn.pdf)
 * 其他用到的jar代码链接: [foxRequestQ.jar](https://github.com/yzyray/FoxRequest) [InVisibleFrame.jar](https://github.com/yzyray/testbuffer) [CaptureTsumeGo.jar](https://github.com/yzyray/captureTsumeGo/blob/main/README.md)

#
<span id="en"></span>
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

#
<span id="ja"></span>
# LizzieYzy Next-FoxUID - 囲碁エンジンGUI
## まず最初に
**元の LizzieYzy では、野狐棋譜の同期機能がすでに正常に使えなくなっていました。このメンテナンス版ではその機能を復旧し、野狐IDを入力するだけで最新の公開棋譜を取得できます。**

## このメンテ版の重要ポイント
**現在も継続してメンテされているフォークで、特に大きい変更は次のとおりです。**

* **野狐棋譜同期を修復**: 壊れていた元の流れをやめ、Fox H5 API へ直接アクセスする方式に変更しました。
* **野狐IDで取得**: ユーザー名検索は廃止し、野狐IDを入力して最新の公開棋譜を取得する形にしました。
* **マルチプラットフォーム配布を再整備**: Windows / macOS / Linux 向けの配布パッケージを整理しました。
* **Mac の両CPU対応を明確化**: `start-mac-arm64.sh`、`start-mac-amd64.sh`、自動判定起動スクリプトを含めています。

## 3ステップですぐ使えます
1. [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) から自分の環境に合うパッケージをダウンロードします。
2. `with-katago` パッケージは解凍またはインストール後すぐ使えます。`without.engine` は別途エンジン設定が必要です。
3. アプリを開き、野狐IDを入力して最新の公開棋譜を取得します。

![screenshot_ja](/screenshot_en.png?raw=true)

LizzieYzy Next-FoxUID は、元の LizzieYzy で壊れてしまった機能を復旧し、今後も使える状態を保つためのメンテナンスフォークです。現在は野狐棋譜同期を修復し、より分かりやすい「野狐ID入力」方式に変更しています。

LizzieYzy は [Lizzie](https://github.com/featurecat/lizzie) をベースにした囲碁エンジンGUIで、[Katago](https://github.com/lightvector/KataGo)、[LeelaZero](https://github.com/leela-zero/leela-zero)、[Leela](https://github.com/gcp/Leela)、[ZenGTP](https://github.com/yzyray/ZenGTP)、[SAI](http://sai.unich.it)、[Pachi](https://github.com/pasky/pachi) などの標準 GTP エンジンを読み込めます。

Lizzie をもとに、**Hawk Eye、Flash Analyze、Batch Analyze、Estimate、Board Synchronization、Engine Game、Tsumego Analysis、Double Engine Mode、Visualized KataGo Distributed Training** などの機能を追加し、高解像度環境でもぼやけにくいよう細部も調整しています。
#
* 主な機能

  * **Hawk Eye**: AI の候補手と実戦の着手を比較し、一致度、勝率変動、目差変動、悪手などをグラフで表示します。

  * **Flash Analyze**: KataGo の analysis モードを使い、棋譜全体を並列解析して勝率グラフや候補手をすばやく得られます。まとめて解析することもできます。

  * **Batch Analyze**: GTP エンジン、または KataGo の analysis モードを使った複数棋譜の一括解析に対応しています。

  * **Estimate**: KataGo の `kata-raw-nn` または ZenGTP の `territory` コマンドを使って大まかな地合い判断を行い、各手ごとの自動形勢判断にも対応します。

  * **Board Synchronization(C#)**: [関連リポジトリ](https://github.com/yzyray/readboard) 前景 / 背景の2モードに対応。野狐、弈城、新浪向けの最適化があり、ワンクリック同期が可能です。その他のサイトや画像 / GIF では盤面を含む範囲を選択します。双方向同期と自動着手に対応します。C# 実装のため Windows 専用です。

  * **Board Synchronization(Java)**: [関連リポジトリ](https://github.com/yzyray/readboard_Boofcv) 前景モードのみ。盤面を含む範囲を選択して、双方向同期と自動着手に対応します。

  * **Engine Game**: 2つのエンジン同士で単発対局 / 複数対局を実行できます。複数の SGF を定石ファイルとして読み込み、`lz-analyze`、`kata-analyze`、`genmove` などの方式で着手を取得できます。複数対局では ELO や標準偏差区間も自動計算します。

  * **Tsumego Analysis**: 局所盤面から詰碁を切り出し、エンジンが正しい範囲で読めるよう補助石を自動生成できます。`[Analyze]-[Tsumego frame]`、`[Capture tsumego]`、またはツールバーの `[Tsumego]` ボタンを参照してください。

  * **Double Engine Mode**: 2つのエンジンを同時に読み込み、並べて比較しながら解析できます。

  * **Visualized KataGo Distributed Training**: KataGo 公式の分散学習を可視化し、進行中または完了した対局を確認できます。

#
 * [使い方ガイド(英語)](https://github.com/yzyray/lizzieyzy/blob/main/readme_en.pdf)
 * その他の jar ソースコード: [foxRequestQ.jar](https://github.com/yzyray/FoxRequest) [InVisibleFrame.jar](https://github.com/yzyray/testbuffer) [CaptureTsumeGo.jar](https://github.com/yzyray/captureTsumeGo/blob/main/README.md)

#
<span id="ko"></span>
# LizzieYzy Next-FoxUID - 바둑 엔진용 GUI
## 먼저 읽어주세요
**원래 LizzieYzy에서는 텐센트 Fox 기보 동기화 기능이 제대로 작동하지 않았습니다. 이 유지보수 포크에서 그 기능을 복구했고, 이제 Fox ID를 입력해 최신 공개 기보를 가져올 수 있습니다.**

## 유지보수 핵심 변경
**이 포크에서 가장 중요한 변경점은 아래와 같습니다.**

* **Fox 기보 동기화 복구**: 원래 흐름이 이미 깨져 있어서, 이제 Fox H5 API를 직접 호출하도록 수정했습니다.
* **Fox ID로 바로 가져오기**: 사용자명 검색은 제거했고, Fox ID를 입력해 최신 공개 기보를 가져오도록 바꿨습니다.
* **멀티 플랫폼 배포 복구**: Windows / macOS / Linux 배포 파일을 다시 정리했습니다.

## 3단계 빠른 시작
1. [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) 에서 내 시스템에 맞는 패키지를 다운로드합니다.
2. `with-katago` 패키지는 압축 해제 또는 설치 후 바로 사용할 수 있습니다. `without.engine` 패키지는 엔진을 직접 설정해야 합니다.
3. 앱을 열고 Fox ID를 입력해 최신 공개 기보를 가져옵니다.

![screenshot_ko](/screenshot_ko.png?raw=true)

LizzieYzy Next-FoxUID는 더 이상 유지되지 않는 원본 LizzieYzy에서 망가진 기능을 복구하고 계속 사용할 수 있게 유지하는 포크입니다. 현재 이 포크는 Fox 기보 동기화를 복구했고, 더 이해하기 쉬운 Fox ID 입력 방식으로 바꿨습니다.

LizzieYzy는 [Lizzie](https://github.com/featurecat/lizzie)를 기반으로 [Katago](https://github.com/lightvector/KataGo)、[LeelaZero](https://github.com/leela-zero/leela-zero)、[Leela](https://github.com/gcp/Leela)、[ZenGTP](https://github.com/yzyray/ZenGTP)、[SAI](http://sai.unich.it)、[Pachi](https://github.com/pasky/pachi) 등의 다른 GTP engine들을 로드할 수 있도록 수정된 그래픽 인터페이스입니다.

Lizzie 기반에 몇 가지 새로운 기능 추가: **Hawk Eye, Flash Analyze, Batch Analyze, Estimate, Board Synchronization(only windows), Engine Game, Tsumego Analysis, Double Engine Mode, Visualized KataGo Distributed Training** 및 몇몇 세부 사항 조정, 고해상도를 완벽하게 지원하여 시스템 스케일링으로 인해 흐릿하게 표시되지 않습니다.
#
* 새로운 기능

  * **Hawk Eye**: 정확도, 승률 차이, 점수 차이, 엔진 후보 수와 실제 착수의 차이를 기반으로 한 실착수를 찾아서 차트에 표시.

  * **Flash Analyze**: Katago의 분석 모드를 사용함. 모든 기보를 병렬로 분석. 승률 그래프 후보를 빠르게 획득. 일괄 분석 지원.

  * **Batch Analyze**: GTP engine 또는 Katago의 분석 모드를 사용한 다수 기보 일괄 분석 지원.

  * **Estimate**: Katago(기본값)의 `kata-raw-nn` 명령 또는 ZenGTP의 `territory` 명령을 사용하여 현재 집 수 계산. 각 착수 후의 집 수 자동 예측을 지원.

  * **Board Synchronization(C#)**: [Repository](https://github.com/yzyray/readboard) 두 가지 모드: 전경(바둑판을 이동하거나 덮을 수 없음)/배경. FoxWQ, TYGEM, SINA 플랫폼에 최적화. 버튼을 클릭하여 동기화를 허용. 다른 플랫폼이나 사진 또는 착수를 통한 동기화 시 바둑판이 포함된 영역을 선택해야 함. 양방향 자동 착수 전달 지원. C#으로 개발되어서 Windows만 지원.

  * **Board Synchronization(Java)**: [Repository](https://github.com/yzyray/readboard_Boofcv) 전경 전용. 바둑판이 포함된 영역을 선택해야 함. 양방향 자동 착수 전달 지원.

  * **Engine Game**: 두 엔진 간의 1회(또는 다회) 대국을 수행. 대국 시작점으로 일부 sgf를 사용할 수 있습니다. 착수를 위한 다양한 명령 지원: `lz-analyze`, `kata-analyze`, `genmove`. 다회 대국시에 몇 가지 통계를 수집합니다: elo, stdev 간격 등.

  * **Tsumego Analysis**: Support capture tsumego in part of goban, and automatically generate other part of stones help engine analyze in right area, refer to [Analyze]-[Tsumego frame] or [Capture tsumego] or [Tsumego] button in toolbar.

  * **Double Engine Mode**: 2개 엔진을 로드하여 동시 분석을 지원. 비교에 편리합니다.

  * **Visualized KataGo Distributed Training**: 시각화된 공식 KataGo 훈련. 모든 대국(진행중이거나 완료된)을 시청할 수 있습니다.

#
 * [사용 지침(영문)](https://github.com/yzyray/lizzieyzy/blob/main/readme_en.pdf)
 * 기타 jar 소스 코드 링크: [foxRequestQ.jar](https://github.com/yzyray/FoxRequest) [InVisibleFrame.jar](https://github.com/yzyray/testbuffer) [CaptureTsumeGo.jar](https://github.com/yzyray/captureTsumeGo/blob/main/README.md)
