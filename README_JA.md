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
