# 开发指南

这份文档面向准备改代码、修打包、补文档或继续维护这个仓库的人。

如果你只是普通使用者，优先看 [安装指南](INSTALL.md)、[排错指南](TROUBLESHOOTING.md) 和 [发布包说明](PACKAGES.md)。

## 先知道这几个事实

- 这是一个持续维护中的 LizzieYzy 分支，不是一次性补丁仓库。
- 当前最重要的用户链路是：能装、能开、能通过 **野狐昵称** 获取最新公开棋谱、能正常分析。
- 项目已有 Java 回归、发布脚本和多平台 CI 门禁；真实 GUI、显卡后端和签名安装包仍需对应平台手工验收。

## 本地构建

### 方案一：使用系统自带 Java / Maven

只要你本机有可用的 Java 和 Maven，就可以直接构建：

```bash
mvn -B -DskipTests package
```

### 方案二：使用仓库里的工具缓存

维护这个仓库时，通常优先使用仓库内已经准备好的工具：

- JDK: `.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home`
- Maven: `.tools/apache-maven-3.9.10/bin/mvn`

示例：

```bash
export JAVA_HOME="$PWD/.tools/jdk-21/jdk-21.0.10.jdk/Contents/Home"
export PATH="$PWD/.tools/apache-maven-3.9.10/bin:$JAVA_HOME/bin:$PATH"
mvn -B -DskipTests package
```

构建完成后，当前常用输出包括：

- `target/lizzie-yzy2.5.3.jar`
- `target/lizzie-yzy2.5.3-shaded.jar`

## 本地一键 CI 预检

提交前建议运行与 GitHub Actions 同源的本地预检。它会校验 JDK 21、执行完整
Maven `verify`、打包辅助脚本、换行和链接检查，并把准确的 JUnit 数量写入
`target/local-ci/`。

Windows：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_local_ci.ps1 -Profile All
```

macOS / Linux：

```bash
bash scripts/run_local_ci.sh --profile portable
```

推送前的干净工作树复核：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_local_ci.ps1 -Profile All -RequireClean
```

可用 profile 为 `Windows`、`Portable` 和 `All`；加 `-DryRun` / `--dry-run`
可只查看计划执行的步骤。`LIZZIE_PYTHON`、`LIZZIE_MAVEN`、`LIZZIE_BASH`
和 `LIZZIE_POWERSHELL` 可用于指定工具路径。

本地预检用于在推送前尽早发现问题，不能代替受保护分支上的干净 Windows 和
Ubuntu runner，也不能代替 macOS 签名、公证与多平台发布资产审计。

如果你改了打包、引擎路径、首次启动流程、野狐抓谱流程，建议再做对应平台的手工验证。

## 换行规则

- 仓库中的文本文件统一使用 LF，不受开发者操作系统或 `core.autocrlf` 配置影响。
- 只有 Windows 命令脚本 `.bat`、`.cmd` 在工作区使用 CRLF；PowerShell、Shell、Java、Python、Markdown 和资源文件均使用 LF。
- `.gitattributes` 是 Git 层面的最终规则，`.editorconfig` 用于让常见编辑器在保存时提前遵守规则。
- 日常功能 PR 不要运行全仓 `git add --renormalize .`，也不要混入无关的 `fmt:format` 改写。
- CI 会运行 `python3 scripts/check_line_endings.py`，发现混合换行或错误行尾时直接失败。

## 仓库结构速览

### 代码目录

- `src/main/java/featurecat/lizzie/gui`
  - 主要界面、对话框、窗口、交互逻辑
- `src/main/java/featurecat/lizzie/analysis`
  - 引擎集成、分析流程、远程连接、野狐抓谱相关逻辑
  - 包括 `GetFoxRequest.java` 这类和野狐请求直接相关的实现
- `src/main/java/featurecat/lizzie/rules`
  - 棋盘、落子、SGF / GIB 解析、局面数据结构
- `src/main/java/featurecat/lizzie/util`
  - 常用工具类
- `src/main/java/featurecat/lizzie/theme`
  - 主题相关逻辑

### 资源目录

- `src/main/resources/l10n`
  - 多语言文案资源，例如 `DisplayStrings_zh_CN.properties`
- `src/main/resources/assets`
  - 内置资源、工具依赖、辅助素材
- `theme/`
  - 主题资源文件

### 打包与发布相关目录

- `scripts/`
  - 打包脚本、工具脚本
- `runtime/`
  - 打包时用到的运行时内容
- `engines/`
  - 内置引擎相关文件
- `weights/`
  - 默认权重文件
- `dist/`
  - 打包输出相关目录

### 文档与仓库配置

- `docs/`
  - 安装、排错、维护、发布、开发等文档
- `.github/`
  - CI、issue 模板、PR 模板、release 配置
- `assets/`
  - GitHub 项目页使用的视觉素材

## 常见改动从哪里开始

### 1. 改野狐抓谱或 野狐昵称 相关流程

优先看：

- `src/main/java/featurecat/lizzie/analysis/`
- `src/main/java/featurecat/lizzie/gui/`
- `src/main/resources/l10n/`

除了代码本身，还要确认：

- 界面里仍然写的是“野狐昵称 / Fox nickname”
- README 和安装文档没有回到旧的 UID / 用户名说法
- 至少做一轮真实抓谱验证

### 2. 改界面文案、多语言、菜单入口

优先看：

- `src/main/resources/l10n/DisplayStrings*.properties`
- `src/main/java/featurecat/lizzie/gui/`

建议同时确认：

- 中文、英文术语一致
- `with-katago`、`nvidia`、`without.engine` 拼写保持一致
- 用户可见文案变化是否需要同步 README 或安装文档

### 3. 改打包、内置引擎、发布资产

优先看：

- `scripts/prepare_bundled_runtime.sh`
- `scripts/prepare_bundled_katago.sh`
- `scripts/package_release.sh`
- `scripts/package_macos_dmg.sh`
- [发布检查清单](RELEASE_CHECKLIST.md)

这类改动通常还要同步：

- `README.md`
- `docs/PACKAGES.md`
- `docs/TESTED_PLATFORMS.md`
- GitHub Releases 说明

## 当前打包脚本分工

- `scripts/prepare_bundled_runtime.sh`
  - 准备带运行时的整合包内容
- `scripts/prepare_bundled_katago.sh`
  - 准备内置 KataGo 和默认权重
- `scripts/package_release.sh`
  - 生成 Windows / Linux / 进阶 zip 包
- `scripts/package_macos_dmg.sh`
  - 生成 macOS `.dmg`
- `scripts/validate_release_assets.sh`
  - 检查 `dist/release/` 里是否只剩普通用户应该看到的主资产
- `scripts/check_markdown_links.py`
  - 检查本地 Markdown 链接是否失效

## 提交前建议再看一眼

- 用户主链路有没有被影响：安装、启动、野狐昵称抓谱、分析
- 文案有没有回到旧的 UID / 用户名说法
- 发布包名、README、文档是否仍然一致
- 如果改了打包流程，是否补了对应说明和验证记录
- 如果改了界面，是否应该补截图

## 建议搭配阅读

- [贡献指南](../CONTRIBUTING.md)
- [维护说明](MAINTENANCE.md)
- [发布检查清单](RELEASE_CHECKLIST.md)
- [发布包说明](PACKAGES.md)
- [已验证平台](TESTED_PLATFORMS.md)
