# 发布检查清单

这份清单面向维护者，目标是让每次发版都尽量稳定、可复用，而且从用户视角看起来足够清楚。

## 一、发版前先确认目标

每次发版至少要做到这四件事：

- 用户进入 release 页面后，第一眼就知道该下载哪个包
- `with-katago` 包尽量开箱即用
- 野狐棋谱同步仍然可用，而且明确写成“野狐昵称”
- README、安装文档、发布页文案、真实资产名保持一致
- 程序窗口图标、安装包图标、README 展示图标不要混成两套

## 二、当前推荐的公开资产集合

新发布时，优先保留下面这组资产：

- `windows64.with-katago.installer.exe`
- `windows64.with-katago.portable.zip`
- `windows64.nvidia.installer.exe`
- `windows64.nvidia.portable.zip`
- `windows64.without.engine.installer.exe`
- `windows64.without.engine.portable.zip`
- `windows64.with-katago-install.txt`
- `windows64.without.engine-install.txt`
- `mac-arm64.with-katago.dmg`
- `mac-arm64.with-katago-install.txt`
- `mac-amd64.with-katago.dmg`
- `mac-amd64.with-katago-install.txt`
- `linux64.with-katago.zip`

不再建议重新上传的旧思路：

- `windows64.with-katago.zip`
- `windows64.without.engine.zip`
- `windows32.without.engine.zip`
- `Macosx.amd64.Linux.amd64.without.engine.zip`
- macOS `.app.zip`
- 含义模糊的 `other-systems` 旧包

## 三、关键脚本和工作流

当前发版链路主要依赖：

- `scripts/prepare_bundled_runtime.sh`
- `scripts/prepare_bundled_katago.sh`
- `scripts/generate_app_icons.py`
- `scripts/package_release.sh`
- `scripts/package_macos_dmg.sh`
- `scripts/package_windows_exe.sh`
- `scripts/generate_release_notes.py`
- `scripts/validate_release_assets.sh`

GitHub Actions：

- `.github/workflows/build-windows-release.yml`
- `.github/workflows/build-macos-amd64-release.yml`
- `.github/workflows/update-release-notes.yml`

## 四、构建前检查

发版前至少确认：

- `README.md` 和 `README_EN.md` 的包名与计划上传的文件完全一致
- 安装文档里的 Windows 主路径仍然是 `installer.exe`
- 如果提供 NVIDIA 极速包，要同时核对 `nvidia.installer.exe` 和 `nvidia.portable.zip`
- 如果提供 Windows 无引擎包，要同时核对 `without.engine.installer.exe` 和 `without.engine.portable.zip`
- 界面里仍然写的是 `野狐棋谱（输入野狐昵称获取）`
- `src/main/resources/assets/logo.png`、`packaging/icons/app-icon.ico`、`packaging/icons/app-icon.icns` 代表的是同一套当前图标
- `weights/default.bin.gz` 存在
- `engines/katago/` 下目标平台文件完整
- 如需 bundled Java，对应 `runtime/` 目录仍然存在

## 五、建议构建顺序

### 1. 构建主程序

```bash
mvn -DskipTests package
```

### 2. 准备 bundled runtime

如果本次 Linux 主包需要带 Java：

```bash
./scripts/prepare_bundled_runtime.sh
```

### 3. 同步程序图标

```bash
python3 scripts/generate_app_icons.py
```

### 4. 准备 bundled KataGo

```bash
./scripts/prepare_bundled_katago.sh
```

当前默认：

- KataGo 版本：`v1.16.4`
- 默认模型：`g170e-b20c256x2-s5303129600-d1228401921.bin.gz`
- Windows NVIDIA 包：官方 `cuda12.1-cudnn8.9.7` 构建

### 5. 构建 Windows 安装器和便携包

```bash
./scripts/package_windows_exe.sh 2026-03-21 2.5.3 target/lizzie-yzy2.5.3-shaded.jar
./scripts/validate_release_assets.sh windows dist/release 2026-03-21
```

### 6. 构建 Linux 主整合包

```bash
./scripts/package_release.sh 2026-03-21 target/lizzie-yzy2.5.3-shaded.jar
./scripts/validate_release_assets.sh linux dist/release 2026-03-21
```

如果确实需要历史兼容 zip，额外显式打开：

```bash
LEGACY_WINDOWS32_ZIP=1 LEGACY_OTHER_SYSTEMS_ZIP=1 ./scripts/package_release.sh 2026-03-21 target/lizzie-yzy2.5.3-shaded.jar
```

### 7. 构建 macOS dmg

在对应芯片机器上运行：

```bash
./scripts/package_macos_dmg.sh 2026-03-21 2.5.3 target/lizzie-yzy2.5.3-shaded.jar
./scripts/validate_release_assets.sh mac-arm64 dist/release 2026-03-21
```

### 8. 生成发布页文案

本地先预览 release notes：

```bash
python3 scripts/generate_release_notes.py \
  --date-tag 2026-03-21 \
  --release-dir dist/release \
  --output dist/release-meta/2026-03-21-release-notes.md
```

如果资产已经上传到 GitHub release，可以直接用工作流更新发布页正文：

```bash
gh workflow run update-release-notes.yml \
  -f date_tag=2026-03-21 \
  -f release_tag=2.5.3-next-2026-03-24.1
```

## 六、Release Notes 应该先写什么

发布页最上面先回答用户最关心的三件事：

1. 原版野狐棋谱同步已失效，这个维护版已修复
2. 现在输入野狐昵称即可获取最新公开棋谱，程序会自动找到账号
3. Windows 64 位优先下载 `installer.exe`，macOS 下载 `.dmg`，Linux 下载 `with-katago.zip`

推荐顺序：

- 中文放最前面
- 然后给英文摘要
- 再给日文、韩文短摘要
- 最后再写维护细节或技术补充

现在可以直接用 `scripts/generate_release_notes.py` 生成这份多语言正文，再更新到 GitHub release。

## 七、上传前自查

至少逐项确认：

- 文件名日期一致
- Windows 主推荐资产确实是 `installer.exe`
- Windows 无引擎包已经是 `.portable.zip`
- macOS 同时有 `arm64` 与 `amd64` 的 `.dmg`
- 发布目录里没有把 `.txt`、校验文件或历史兼容包混进公开资产
- 没把历史兼容包重新混进主 release 页面

## 八、上传后，从用户视角复查

上传完成后，不要只看文件有没有传上去，要按普通用户视角再走一遍：

- release 页面第一屏能不能看懂该下哪个包
- README 里的推荐包名在 release 页面能不能对上
- Windows 用户会不会第一眼看到 `.exe` 安装器而不是历史 zip
- 中文说明是不是在最前面，而且信息足够醒目
- “野狐昵称”和“首启自动配置”有没有被写清楚
- 下载后的应用图标、安装器图标、主窗口图标是不是同一套
