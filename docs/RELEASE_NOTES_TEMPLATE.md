# Release Notes Template

下面这份模板可以直接作为 GitHub Release 文案的基础版本。建议每次发版时只替换日期、版本号和资产名，不要临时重写结构。

---

# 中文

**这次维护版把原来已经失效的野狐棋谱同步重新做回可用状态。现在可以直接输入野狐ID，获取最新公开棋谱。**

## 这版最重要的变化

- 修复野狐棋谱同步，改为当前可用的实现
- 界面和文档统一成“野狐ID”口径，不再支持用户名搜索
- 第一次启动会优先自动配置内置 KataGo、权重和默认引擎路径
- Windows 64 位主推荐资产改成 `installer.exe`

## 下载怎么选

### Windows

- `<date>-windows64.with-katago.installer.exe`：Windows 64 位首选，双击安装，普通用户直接选这个
- `<date>-windows64.with-katago.portable.zip`：不想安装时使用，解压后运行 `.exe`
- `<date>-windows64.without.engine.portable.zip`：自己配置引擎时使用
- `<date>-windows32.without.engine.zip`：老机器兼容包

### macOS

- `<date>-mac-arm64.with-katago.dmg`：Apple Silicon Mac
- `<date>-mac-amd64.with-katago.dmg`：Intel Mac
- 对应的 `*-install.txt` 里写了 Gatekeeper 提示，以及引擎 / 权重路径

### Linux

- `<date>-linux64.with-katago.zip`：Linux 64 位整合包
- `<date>-Macosx.amd64.Linux.amd64.without.engine.zip`：进阶自定义包

## 你会直接感受到的变化

- 原来已经失效的野狐棋谱同步重新可用
- 输入野狐ID即可获取最新公开棋谱
- 普通 Windows 用户不需要再理解 `.bat`
- `with-katago` 包第一次打开时会优先自动配置引擎

# English

**This maintained release restores the broken Fox kifu sync path. You can now fetch the latest public games directly by entering a Fox ID.**

## Download quick guide

- Windows x64: choose `<date>-windows64.with-katago.installer.exe`
- macOS Apple Silicon: choose `<date>-mac-arm64.with-katago.dmg`
- macOS Intel: choose `<date>-mac-amd64.with-katago.dmg`
- Linux x64: choose `<date>-linux64.with-katago.zip`
- Advanced custom engine setup: choose the `without.engine` packages

## Highlights

- Fox sync restored
- Fox ID only workflow
- First-launch bundled KataGo auto setup
- Windows release is now installer-first

# 日本語

**このメンテナンス版では、壊れていた野狐棋譜同期を復旧し、野狐IDで最新の公開棋譜を取得できるようにしました。**

- Windows x64 は `installer.exe` を優先配布
- 初回起動で内蔵 KataGo を自動設定
- UI と文書は野狐ID表記に統一

# 한국어

**이 유지보수 릴리스에서는 고장나 있던 Fox 기보 동기화를 복구했고, 이제 Fox ID 로 최신 공개 기보를 가져올 수 있습니다.**

- Windows x64 는 `installer.exe` 를 우선 제공
- 첫 실행에서 내장 KataGo 자동 설정 시도
- UI 와 문서를 Fox ID 기준으로 통일
