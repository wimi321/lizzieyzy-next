# 发布检查清单

这份清单面向维护者，目标是让每次发版都尽量稳定、可复用，而且从用户视角看起来足够清楚。

## 一、发版前先确认目标

每次发版至少要做到这四件事：

- 用户进入 release 页面后，第一眼就知道该下载哪个包
- `with-katago` 包尽量开箱即用
- 野狐棋谱同步仍然可用，而且明确写成“野狐ID”
- README、安装文档、发布页文案、真实资产名保持一致

## 二、当前推荐的公开资产集合

新发布时，优先保留下面这组资产：

- `windows64.with-katago.installer.exe`
- `windows64.with-katago.portable.zip`
- `windows64.without.engine.portable.zip`
- `windows64.with-katago-install.txt`
- `windows64.without.engine-install.txt`
- `windows32.without.engine.zip`
- `mac-arm64.with-katago.dmg`
- `mac-arm64.with-katago-install.txt`
- `mac-amd64.with-katago.dmg`
- `mac-amd64.with-katago-install.txt`
- `linux64.with-katago.zip`
- `Macosx.amd64.Linux.amd64.without.engine.zip`

不再建议重新上传的旧思路：

- `windows64.with-katago.zip`
- `windows64.without.engine.zip`
- macOS `.app.zip`
- 含义模糊的 `other-systems` 旧包

## 三、关键脚本和工作流

当前发版链路主要依赖：

- `scripts/prepare_bundled_runtime.sh`
- `scripts/prepare_bundled_katago.sh`
- `scripts/package_release.sh`
- `scripts/package_macos_dmg.sh`
- `scripts/package_windows_exe.sh`

GitHub Actions：

- `.github/workflows/build-windows-release.yml`
- `.github/workflows/build-macos-amd64-release.yml`

## 四、构建前检查

发版前至少确认：

- `README.md` 和 `README_EN.md` 的包名与计划上传的文件完全一致
- 安装文档里的 Windows 主路径仍然是 `installer.exe`
- 界面里仍然写的是 `野狐棋谱（输入野狐ID获取）`
- `weights/default.bin.gz` 存在
- `engines/katago/` 下目标平台文件完整
- 如需 bundled Java，对应 `runtime/` 目录仍然存在

## 五、建议构建顺序

### 1. 构建主程序

```bash
mvn -DskipTests package
```

### 2. 准备 bundled runtime

如果本次 Linux 或旧兼容包需要带 Java：

```bash
./scripts/prepare_bundled_runtime.sh
```

### 3. 准备 bundled KataGo

```bash
./scripts/prepare_bundled_katago.sh
```

当前默认：

- KataGo 版本：`v1.16.4`
- 默认模型：`g170e-b20c256x2-s5303129600-d1228401921.bin.gz`

### 4. 构建 Windows 安装器和便携包

```bash
./scripts/package_windows_exe.sh 2026-03-21 2.5.3 target/lizzie-yzy2.5.3-shaded.jar
```

### 5. 构建 Linux / 兼容 zip 包

```bash
./scripts/package_release.sh 2026-03-21 target/lizzie-yzy2.5.3-shaded.jar
```

### 6. 构建 macOS dmg

在对应芯片机器上运行：

```bash
./scripts/package_macos_dmg.sh 2026-03-21 2.5.3 target/lizzie-yzy2.5.3-shaded.jar
```

## 六、Release Notes 应该先写什么

发布页最上面先回答用户最关心的三件事：

1. 原版野狐棋谱同步已失效，这个维护版已修复
2. 现在输入野狐ID即可获取最新公开棋谱
3. Windows 64 位优先下载 `installer.exe`，macOS 下载 `.dmg`，Linux 下载 `with-katago.zip`

推荐顺序：

- 中文放最前面
- 然后给英文摘要
- 再给日文、韩文短摘要
- 最后再写维护细节或技术补充

建议直接复用：[Release Notes 模板](RELEASE_NOTES_TEMPLATE.md)

## 七、上传前自查

至少逐项确认：

- 文件名日期一致
- Windows 主推荐资产确实是 `installer.exe`
- Windows 无引擎包已经换成 `.exe` 便携包
- macOS 同时有 `arm64` 与 `amd64` 的 `.dmg`
- 对应的 `*-install.txt` 已一起上传
- 没把旧 Windows 64 位 zip 资产重新传回去

## 八、上传后，从用户视角复查

上传完成后，不要只看文件有没有传上去，要按普通用户视角再走一遍：

- release 页面第一屏能不能看懂该下哪个包
- README 里的推荐包名在 release 页面能不能对上
- Windows 用户会不会第一眼还是看到旧 zip 而不是安装器
- 中文说明是不是在最前面，而且信息足够醒目
- “野狐ID”和“首启自动配置”有没有被写清楚

## 九、长期习惯

建议长期保持这些习惯：

- 每次发版都带日期前缀
- 每次发版先更新 README 和 release 文案，再上传资产
- 每次发版后补一轮人工复查
- 拿到新的实机安装反馈后，及时更新 `TESTED_PLATFORMS.md`
- 任何涉及包策略变化的改动，都同步更新 README、安装文档和发布页文案
