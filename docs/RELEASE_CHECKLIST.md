# 发布检查清单

这份清单面向维护者，目标是让每次发版都尽量稳定、可复用，而且从用户视角看起来足够清楚。

## 一、发版前先确认目标

每次发版至少要做到这四件事：

- 用户进入 release 页面后，第一眼就知道该下载哪个包
- `with-katago` 包尽量开箱即用
- 野狐棋谱同步仍然可用，而且明确写成“野狐昵称”
- 普通 Windows 包支持“智能优化”的信息要写清楚
- NVIDIA Windows 包“首次自动准备官方运行库”的信息要写清楚
- README、安装文档、发布页文案、真实资产名保持一致
- 软件内“关于”、主窗口标题、安装包启动参数、GitHub release 标题必须显示同一个 release tag，不能停在 `1.0.0`，也不要再把 `1.0.0-` 作为公开 tag 前缀
- 程序窗口图标、安装包图标、README 展示图标不要混成两套

## 二、当前推荐的公开资产集合

新发布时，优先保留下面这组资产：

- `windows64.opencl.portable.zip`
- `windows64.opencl.installer.exe`
- `windows64.with-katago.portable.zip`
- `windows64.with-katago.installer.exe`
- `windows64.nvidia.portable.zip`
- `windows64.nvidia.installer.exe`
- `windows64.without.engine.portable.zip`
- `windows64.without.engine.installer.exe`
- `windows64-install.txt`
- `mac-apple-silicon.with-katago.dmg`
- `mac-apple-silicon-install.txt`
- `mac-intel.with-katago.dmg`
- `mac-intel-install.txt`
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
- `.github/workflows/build-linux-release.yml`
- `.github/workflows/build-macos-arm64-release.yml`
- `.github/workflows/build-macos-amd64-release.yml`
- `.github/workflows/update-release-notes.yml`

## 四、构建前检查

发版前至少确认：

- `README.md` 和 `README_EN.md` 的包名与计划上传的文件完全一致
- 安装文档里的 Windows 主路径仍然是 `portable.zip`
- 如果提供 NVIDIA 极速包，要同时核对 `nvidia.installer.exe` 和 `nvidia.portable.zip`
- 如果提供 OpenCL 包，要同时核对 `opencl.installer.exe` 和 `opencl.portable.zip`
- 如果提供 Windows 无引擎包，要同时核对 `without.engine.installer.exe` 和 `without.engine.portable.zip`
- 界面里仍然写的是 `野狐棋谱（输入野狐昵称获取）`
- 发布页最上面的中文说明里，要明确写“普通 Windows 包也支持智能优化”
- 发布页最上面的中文说明里，要明确写“NVIDIA 包首次会自动准备官方运行库”
- `src/main/resources/assets/logo.png`、`packaging/icons/app-icon.ico`、`packaging/icons/app-icon.icns` 代表的是同一套当前图标
- `weights/default.bin.gz` 存在
- `engines/katago/` 下目标平台文件完整
- 如需 bundled Java，对应 `runtime/` 目录仍然存在
- 发版 tag 已确定，例如 `next-2026-04-24.2`；构建脚本最后一个参数必须传这个 tag

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
- 默认模型：`kata1-zhizi-b28c512nbt-muonfd2.bin.gz`
- Windows NVIDIA 包：官方 `cuda12.1-cudnn8.9.7` 构建

### 5. 构建 Windows 安装器和便携包

```bash
./scripts/package_windows_exe.sh 2026-04-24 1.0.0 target/lizzie-yzy2.5.3-shaded.jar next-2026-04-24.2
./scripts/validate_release_assets.sh windows dist/release 2026-04-24
```

### 6. 构建 Linux 主整合包

```bash
./scripts/package_release.sh 2026-04-24 target/lizzie-yzy2.5.3-shaded.jar next-2026-04-24.2
./scripts/validate_release_assets.sh linux dist/release 2026-04-24
```

如果确实需要历史兼容 zip，额外显式打开：

```bash
LEGACY_WINDOWS32_ZIP=1 LEGACY_OTHER_SYSTEMS_ZIP=1 ./scripts/package_release.sh 2026-04-24 target/lizzie-yzy2.5.3-shaded.jar next-2026-04-24.2
```

### 7. 构建 macOS dmg

在对应芯片机器上运行：

```bash
./scripts/package_macos_dmg.sh 2026-04-24 1.0.0 target/lizzie-yzy2.5.3-shaded.jar next-2026-04-24.2
./scripts/validate_release_assets.sh mac-arm64 dist/release 2026-04-24
```

### 8. 生成发布页文案

本地先预览 release notes：

```bash
python3 scripts/generate_release_notes.py \
  --date-tag 2026-04-24 \
  --release-tag next-2026-04-24.2 \
  --release-dir dist/release \
  --output dist/release-meta/2026-04-24-release-notes.md
```

如果资产已经上传到 GitHub release，可以直接用工作流更新发布页正文：

```bash
gh workflow run update-release-notes.yml \
  -f date_tag=2026-04-24 \
  -f release_tag=next-2026-04-24.2
```

## 六、Release Notes 应该先写什么

发布页最上面先回答用户最关心的三件事：

1. 原版野狐棋谱同步已失效，这个维护版已修复
2. 现在输入野狐昵称即可获取最新公开棋谱，程序会自动找到账号
3. Windows 64 位优先下载 `portable.zip`，macOS 下载 `.dmg`，Linux 下载 `with-katago.zip`
4. 普通 Windows 包也支持智能优化
5. NVIDIA Windows 包首次需要时会自动准备官方运行库

推荐顺序：

- 中文放最前面
- 然后给英文摘要
- 再给日文、韩文短摘要
- 最后再写维护细节或技术补充

现在可以直接用 `scripts/generate_release_notes.py` 生成这份多语言正文，再更新到 GitHub release。

## 七、上传前自查

至少逐项确认：

- 文件名日期一致
- Windows 主推荐资产确实是 `portable.zip`
- Windows 无引擎包已经是 `.portable.zip`
- macOS 同时有 `arm64` 与 `amd64` 的 `.dmg`
- 发布目录里没有把 `.txt`、校验文件或历史兼容包混进公开资产
- 没把历史兼容包重新混进主 release 页面

## 八、上传后，从用户视角复查

上传完成后，不要只看文件有没有传上去，要按普通用户视角再走一遍：

- release 页面第一屏能不能看懂该下哪个包
- README 里的推荐包名在 release 页面能不能对上
- Windows 用户会不会第一眼看到推荐的 `.portable.zip`
- 中文说明是不是在最前面，而且信息足够醒目
- “野狐昵称”和“首启自动配置”有没有被写清楚
- “普通 Windows 包也支持智能优化”有没有写清楚
- “NVIDIA 包首启自动准备官方运行库”有没有写清楚
- 下载后的应用图标、安装器图标、主窗口图标是不是同一套

## 九、Windows 体验复查

每次 Windows 新版发布前，至少做下面这些检查：

- 普通 `with-katago.installer.exe` 首次打开后，不应该再弹出 `Cannot run program "java"` 这类错误
- 普通 `with-katago` 包里应能看到“智能优化 / Smart Optimize”入口
- 做完一次智能优化后，推荐线程数要能写回配置，并在重启后继续保留
- NVIDIA 包首次需要运行库时，应提示正在准备官方运行库，而不是让用户自己研究 CUDA / cuDNN
- NVIDIA 运行库准备完成后，用户目录下应能看到 `runtime/nvidia-runtime/manifest.txt`
