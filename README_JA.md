<p align="center">
  <img src="assets/hero-japanese.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next/total?label=Downloads&color=666666" alt="Downloads"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">中文</a> · <a href="README_EN.md">English</a> · 日本語 · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>LizzieYzy Next は、現在も保守されている lizzieyzy 系の実用版であり、普通の利用者向けの KataGo 復盤 GUI です。</strong><br/>
  配布物の選びやすさ、初回起動の負担、野狐棋譜取得の使いやすさ、そして全局を見渡しやすい解析体験の4点を、いまの利用者目線で整え直しています。<br/>
  <strong>ダウンロードして、野狐のニックネームを入力し、公開棋譜を取得して、全局を素早く解析し、新しい勝率グラフと下部のクイック概要で重要な手へすぐ移動できます。</strong>
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><strong>Releases</strong></a>
  ·
  <a href="https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w"><strong>Baidu ダウンロード</strong></a>
  ·
  <a href="docs/INSTALL_JA.md"><strong>インストールガイド</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>トラブル対応</strong></a>
</p>

> [!NOTE]
> 中国本土から利用する場合は、公開されている Baidu Netdisk ダウンロードも使えます:
> [https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w](https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w)
> 取り出しコード: `3i8w`

> [!TIP]
> 中国語 QQ グループ: `299419120`
>
> 日常の利用相談、バグ報告、機能要望のやり取りが一番速い場所です。

> [!IMPORTANT]
> まずはこの 6 点だけ押さえれば大丈夫です:
> - Windows 利用者の多くは [Releases](https://github.com/wimi321/lizzieyzy-next/releases) で `*windows64.opencl.portable.zip` を選べば始めやすいです
> - NVIDIA GPU があり、より速く解析したい場合は `*windows64.nvidia.portable.zip` を選べます
> - OpenCL の相性が悪い場合は `*windows64.with-katago.portable.zip` に切り替えられます
> - 野狐棋譜取得はニックネーム入力に対応しており、多くの利用者はアカウント番号を先に知らなくても大丈夫です
> - 主な統合パッケージには KataGo `v1.16.4` と公式推奨の `zhizi` 重み `kata1-zhizi-b28c512nbt-muonfd2.bin.gz` が含まれています
> - 主な release パッケージには `readboard_java` も同梱されており、多くの利用者は別の readboard リポジトリを用意する必要がありません

## なぜ多くの利用者がここから始めるのか

`LizzieYzy Next` は次のように考えればわかりやすいです。

- 普通の利用者向けに使いやすく整えた `KataGo 復盤ソフト`
- `野狐棋譜取得 + 全局を素早く見る解析 + 複数 OS 向け配布` を一つにした実用ワークフロー
- 古い `lizzieyzy` 環境から移りやすい現行の保守ブランチ

次のようなものを探しているなら、まずこのプロジェクトを見る価値があります。

- `KataGo 復盤ソフト`
- `KataGo GUI`
- `lizzieyzy 保守版`
- `野狐棋譜取得 + KataGo 復盤`
- `Windows 非インストール 囲碁 AI ツール`

## 起動したあとすぐできること

| やりたいこと | いまのプロジェクトでどうできるか |
| --- | --- |
| 最近の公開野狐棋譜を取りたい | 野狐のニックネームを入力するとアプリが自動で対応アカウントを探します |
| 全局の流れを早く見たい | 一手ずつ手動で進めなくても、全局を素早く見る解析を使えます |
| 問題手を早く見たい | 新しい主勝率グラフと下部のヒート概要で大きな損失を見つけやすくなっています |
| 設定をあまり触りたくない | 推奨パッケージに KataGo、既定の重み、初回自動設定が入っています |
| インストールしたくない | Windows では `portable.zip` を優先して選べます |
| 棋盤同期も使いたい | 主な配布物に `readboard_java` が同梱されています |

## まずどれをダウンロードするか

すべての配布物は [Releases](https://github.com/wimi321/lizzieyzy-next/releases) にあります。下の表は、最新 release ページで探すときのキーワードです。

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next package guide" width="100%" />
</p>

| あなたの環境 | Releases でこのキーワードを含むファイルを探す |
| --- | --- |
| Windows 利用者の多く、推奨、非インストール | `*windows64.opencl.portable.zip` |
| Windows、OpenCL 版、インストーラあり | `*windows64.opencl.installer.exe` |
| Windows、OpenCL が不安定、CPU フォールバック、非インストール | `*windows64.with-katago.portable.zip` |
| Windows、CPU フォールバック、インストーラあり | `*windows64.with-katago.installer.exe` |
| Windows、NVIDIA GPU、より速い解析、非インストール | `*windows64.nvidia.portable.zip` |
| Windows、NVIDIA GPU、インストーラあり | `*windows64.nvidia.installer.exe` |
| Windows、自分のエンジンを使う、非インストール | `*windows64.without.engine.portable.zip` |
| Windows、自分のエンジンを使う、インストーラあり | `*windows64.without.engine.installer.exe` |
| macOS Apple Silicon | `*mac-arm64.with-katago.dmg` |
| macOS Intel | `*mac-amd64.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

迷ったときの目安:

- Windows: まず `*windows64.opencl.portable.zip`
- Windows + NVIDIA GPU: まず `*windows64.nvidia.portable.zip`
- OpenCL が合わない: `*windows64.with-katago.portable.zip`
- Mac: Apple Silicon か Intel かを先に確認
- Linux: `*linux64.with-katago.zip`

## なぜ今この版が使いやすいのか

- `野狐棋譜取得が再び使える`
  利用者が覚えているニックネームから始められます。
- `全局を素早く見る解析が主導線になった`
  一手ずつ積み上げなくても、全体像を早く作れます。
- `主勝率グラフ + 下部クイック概要`
  大きな損失が出た場所を先に探しやすくなりました。
- `Windows は非インストール版を先に案内`
  OpenCL / NVIDIA / CPU フォールバックの違いがわかりやすくなっています。
- `readboard_java を主 release に同梱`
  多くの利用者は別リポジトリを組み合わせなくて済みます。
- `実際の release とスモークテスト`
  単にソースを更新するだけでなく、複数 OS の配布と確認も続けています。

## 3 ステップで開始

1. [Releases](https://github.com/wimi321/lizzieyzy-next/releases) から自分の環境に合うものをダウンロードします。
2. `野狐棋譜` を開いて野狐のニックネームを入力します。
3. 棋譜を取得し、全局を素早く解析して、グラフと概要から重要な手へ移動します。

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next Fox nickname demo" width="100%" />
  </a>
</p>

<p align="center">
  GitHub 上で GIF の再生が遅い場合は、上の画像をクリックすると全体を開けます。
</p>

## 実際の画面

以下は現在のメンテ版そのものの実画面です。

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next actual interface" width="100%" />
</p>

グラフ部分は次のように読むとわかりやすいです。

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next winrate graph and quick overview" width="46%" />
</p>

- 青線 / 紫線: 双方の勝率の流れ
- 緑線: 目差の変化
- 下部のヒート帯: 全局の中で大きな問題が集まっている場所
- 縦のガイド線: 現在の手やホバー中の手の位置

## 元の lizzieyzy との違い

| 比較項目 | 元の `lizzieyzy` | `LizzieYzy Next` |
| --- | --- | --- |
| 現在の状態 | 多くの人が覚えている元プロジェクトだが、実用面の継続保守は弱い | 使用感と配布体験を継続保守する現行ブランチ |
| 野狐棋譜取得 | 古い取得フローは壊れた場面が多い | よく使う取得フローを復旧し、ニックネーム入力にも対応 |
| 入力方法 | 数字のアカウント番号を先に知っている前提が強い | 野狐のニックネームを入れるとアプリが自動で対応付け |
| KataGo 利用の敷居 | 自分で環境や不足リソースを補う場面が多い | 推奨パッケージに KataGo と既定の重みを同梱 |
| Windows での選びやすさ | 利用者が自分で判断する余地が大きい | `portable.zip` を先に勧める構成でわかりやすい |
| 同期ツール | 利用者が自分で組み合わせる場面が多い | 主な release に `readboard_java` を同梱 |

## よくある質問

### readboard 用に別リポジトリは必要ですか？

多くの利用者には不要です。`LizzieYzy Next` は `readboard_java` を主な release パッケージに含めています。

### 野狐のアカウント番号を先に知っておく必要はありますか？

多くの場合は不要です。野狐のニックネームを入力すれば、アプリが対応するアカウントを探します。

### 全局の流れを見るのに、まだ一手ずつ進める必要がありますか？

通常はそこまで必要ありません。全局を素早く見る解析があるため、主勝率グラフと概要をかなり早く作れます。

### macOS で初回起動時にブロックされたらどうすればよいですか？

現在の macOS 配布物は未署名・未公証です。初回にブロックされた場合は [インストールガイド](docs/INSTALL_JA.md) の手順を見てください。

## 利用者向けドキュメント

- [サポートガイド](SUPPORT.md)
- [インストールガイド](docs/INSTALL_JA.md)
- [配布パッケージ一覧 (English)](docs/PACKAGES_EN.md)
- [トラブル対応 (English)](docs/TROUBLESHOOTING_EN.md)
- [検証済みプラットフォーム (English)](docs/TESTED_PLATFORMS.md)
- [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)
- GitHub Discussions: <https://github.com/wimi321/lizzieyzy-next/discussions>
- 中国語 QQ グループ: `299419120`

## プロジェクトリンク
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)
- [Support](SUPPORT.md)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
Historical Fox sync references:
- [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
- [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
