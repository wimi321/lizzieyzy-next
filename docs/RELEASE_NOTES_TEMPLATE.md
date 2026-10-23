# Release Notes Template

下面这份模板可以直接作为 GitHub Release 文案的基础版本。建议每次发版时只替换日期、版本号和资产名，不要临时重写结构。

---

# 中文

**这是当前仍在维护的 `lizzieyzy` 维护版，也是一个面向普通用户的 `KataGo 围棋复盘 GUI`。这一版继续把最常用的链路做实：野狐抓谱重新能抓、Windows 免安装包更好选、KataGo 更容易开箱即用。现在下载安装后，直接输入“野狐昵称”就能继续抓最近公开棋谱、分析和复盘。**

![LizzieYzy Next 下载选择图](https://raw.githubusercontent.com/wimi321/lizzieyzy-next/main/assets/package-guide-zh.svg)

## 下载前先看这几句

- RTX 20/30/40/50 NVIDIA 显卡统一下载 `<date>-windows64.nvidia.portable.zip`，内置 CUDA 12.8 + cuDNN 9.8
- AMD、Intel 或较老 NVIDIA 显卡下载 `<date>-windows64.opencl.portable.zip`
- 没有合适 GPU，或 CUDA/OpenCL 无法正常启动时，使用 `<date>-windows64.with-katago.portable.zip` CPU 兼容版
- RTX 40/50 默认推荐 CUDA；TensorRT 是 RTX 30 系及以下 NVIDIA 显卡的软件内可选方案
- 推荐完整包默认内置官方 B11 旗舰 Transformer：单次判断更强、复杂局面效果更好，但搜索可能比 B10 慢；追求速度可在一键设置中切换 B10
- 抓野狐棋谱时直接输入“野狐昵称”，程序会自动找到账号并抓最近公开棋谱
- 主推荐整合包已内置 KataGo，第一次启动会优先自动完成配置
- Windows 普通整合包也支持“智能优化”，测速后会自动保存更合适的线程设置
- Windows NVIDIA 完整包已内置所需官方 NVIDIA 运行库；仍需兼容的显卡和驱动。TensorRT 等可选组件在软件内按需安装

## 为什么这一版值得先看

| 你最关心的事 | 这个维护版给你的答案 |
| --- | --- |
| 下载以后能不能直接打开 | Windows 主推荐 `portable.zip`，macOS 主推荐 `.dmg`，Linux 提供整合包 |
| 野狐棋谱还能不能抓 | 已恢复这条链路，并继续维护 |
| 到底该输入什么 | 现在直接输入“野狐昵称”，程序自动找到账号 |
| 第一次打开会不会卡在设置上 | 会优先配置内置分析环境，普通 Windows 包还有智能优化；NVIDIA 完整包自带运行库，仍需兼容的显卡和驱动 |

## 先下载哪个

| 你的系统 | 直接下载这个 | 说明 |
| --- | --- | --- |
| Windows 64 位，RTX 20/30/40/50 NVIDIA | `<date>-windows64.nvidia.portable.zip` | 推荐 CUDA 12.8 包，免安装 |
| Windows 64 位，RTX 20/30/40/50 NVIDIA | `<date>-windows64.nvidia.installer.exe` | 推荐 CUDA 12.8 包，安装器 |
| Windows 64 位，AMD / Intel / 较老 NVIDIA | `<date>-windows64.opencl.portable.zip` | OpenCL 兼容版，免安装 |
| Windows 64 位，AMD / Intel / 较老 NVIDIA | `<date>-windows64.opencl.installer.exe` | OpenCL 兼容版，安装器 |
| Windows 64 位 | `<date>-windows64.with-katago.portable.zip` | OpenCL 表现不好时的 CPU 兜底免安装版 |
| Windows 64 位 | `<date>-windows64.with-katago.installer.exe` | 想安装的 CPU 兜底版 |
| Windows 64 位，RTX 30 系及以下 TensorRT | 先下载统一 NVIDIA 包 | 打开后在 `KataGo 一键设置` 里按需安装 |
| Windows 64 位 | `<date>-windows64.without.engine.portable.zip` | 想自己决定分析引擎时再选 |
| Windows 64 位 | `<date>-windows64.without.engine.installer.exe` | 想保留安装流程，但自己决定分析引擎 |
| macOS Apple Silicon | `<date>-mac-apple-silicon.with-katago.dmg` | M1 / M2 / M3 / M4 等机器 |
| macOS Intel | `<date>-mac-intel.with-katago.dmg` | Intel 芯片 Mac |
| Linux 64 位 | `<date>-linux64.with-katago.zip` | Linux 64 位整合包 |

## 这次你会直接感受到什么

- 原版已经失效的野狐抓谱链路，重新可用
- 现在直接输入野狐昵称，程序自动找到账号再抓最近公开棋谱
- Windows 主推荐现在放在 `.portable.zip`
- 普通 Windows 包也支持智能优化，一次测速后就会自动保存推荐线程数
- NVIDIA 显卡统一使用覆盖 RTX 20/30/40/50 的 CUDA 12.8 整合包，并把官方运行库打入发布包
- TensorRT 10.9 定位为 RTX 30 系及以下的可选方案；RTX 40/50 默认推荐 CUDA
- 默认权重升级到官方 B11 旗舰 Transformer；搜索速度可能比 B10 慢，但单次判断质量更高
- 第一次启动会优先准备好内置分析环境
- 发布页尽量只保留普通用户最容易选的主包

# English

**This is the actively maintained `LizzieYzy` fork and a practical `KataGo review GUI` for normal users. Fox game fetching works again, portable Windows downloads are easier to choose, and KataGo is easier to get running well. Download it, open it, enter a Fox nickname, and keep reviewing.**

## Download quick guide

- Windows x64 with RTX 20/30/40/50 NVIDIA GPU: choose `<date>-windows64.nvidia.portable.zip`
- Windows x64 NVIDIA installer alternative: choose `<date>-windows64.nvidia.installer.exe`
- Windows x64 with AMD, Intel, or older NVIDIA GPU: choose `<date>-windows64.opencl.portable.zip`
- Windows x64 OpenCL installer alternative: choose `<date>-windows64.opencl.installer.exe`
- Windows x64 CPU fallback: choose `<date>-windows64.with-katago.portable.zip`
- Windows x64 CPU fallback installer: choose `<date>-windows64.with-katago.installer.exe`
- Windows x64 RTX 30 series and earlier TensorRT: start with the unified NVIDIA package, then install TensorRT from KataGo Auto Setup
- Windows x64 custom engine: choose `<date>-windows64.without.engine.portable.zip`
- Windows x64 custom engine installer: choose `<date>-windows64.without.engine.installer.exe`
- macOS Apple Silicon: choose `<date>-mac-apple-silicon.with-katago.dmg`
- macOS Intel: choose `<date>-mac-intel.with-katago.dmg`
- Linux x64: choose `<date>-linux64.with-katago.zip`

## Highlights

- Fox game fetching restored
- nickname-based workflow for normal users
- bundled KataGo packages try to auto-finish the first setup
- the regular Windows bundle also supports Smart Optimize for a better saved thread setting
- the full Windows NVIDIA package includes the required official NVIDIA runtime files; a compatible GPU and driver are still required. Optional components such as TensorRT are installed on demand in the app
- recommended full packages now bundle the stronger official B11 flagship Transformer; it can search more slowly than B10, which remains available on demand
- one CUDA 12.8 package now covers RTX 20/30/40/50; TensorRT is optional for RTX 30 series and earlier
- Windows release now recommends the portable bundle first
- the public release page stays focused on a smaller, clearer asset set

# 日本語

**このリリースは、現在も保守されている `lizzieyzy` の保守版であり、普通の利用者向けの `KataGo 復盤 GUI` でもあります。壊れていた野狐棋譜取得を復旧し、ダウンロード後すぐ使いやすい形に整えています。**

- Windows x64 は `portable.zip` を優先案内
- NVIDIA GPU 向けには CUDA 版の専用パッケージも提供
- 通常の Windows 同梱版でも Smart Optimize により推奨スレッド数を保存しやすくした
- 初回起動ではそのまま使えるように分析まわりの自動準備を優先
- Windows NVIDIA 完全版には必要な公式ランタイムを同梱。対応 GPU とドライバーは必要で、TensorRT などの追加コンポーネントはアプリ内で必要に応じてインストール
- UI と文書はニックネーム入力を前提に案内

# 한국어

**이 릴리스는 지금도 유지보수 중인 `lizzieyzy` 유지보수판이자, 일반 사용자를 위한 `KataGo 복기 GUI` 입니다. 고장난 Fox 기보 가져오기를 복구하고 다운로드 후 바로 쓰기 쉬운 형태로 정리했습니다.**

- Windows x64 는 `portable.zip` 를 우선 안내
- NVIDIA 그래픽카드용 CUDA 전용 패키지도 함께 제공
- 일반 Windows 통합판도 Smart Optimize 로 더 알맞은 스레드 값을 저장하기 쉽게 했다
- 첫 실행에서는 바로 쓸 수 있도록 분석 환경 자동 준비를 우선 시도
- Windows NVIDIA 전체 패키지에는 필요한 공식 런타임이 포함된다. 호환 GPU와 드라이버는 필요하며, TensorRT 같은 선택 구성 요소는 앱에서 필요할 때 설치한다
- UI 와 문서는 닉네임 입력 기준으로 안내
