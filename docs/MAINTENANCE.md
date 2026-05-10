# 维护说明

这份文档的目标不是讲历史，而是明确这个仓库现在到底怎么维护、优先维护什么、哪些事情会继续做。

## 这个分支的定位

`LizzieYzy Next` 不是一次性修补版，而是一个面向继续维护的分支。

当前维护重点很明确：

- 修复原版已经失效的野狐棋谱同步
- 把输入方式统一成普通用户更容易理解的说法：中文写 **野狐昵称**，英文写 **Fox nickname**
- 继续提供更好安装的多平台发布包
- 尽量降低新用户第一次使用的门槛
- 在保留 LizzieYzy 原有能力的前提下，优先保证“能装、能开、能抓谱、能分析”

## 当前优先维护的范围

### 1. 野狐棋谱获取

优先保证下面这条链路稳定：

1. 用户打开程序
2. 进入“野狐棋谱（输入野狐昵称获取）”
3. 输入野狐昵称
4. 成功获取最新公开棋谱

### 2. 多平台发布包

当前发布包策略以“普通用户能快速选对并直接用”为核心。

当前主推荐矩阵固定为 15 个公开资产：

- Windows 64 位：`opencl.portable.zip`
- Windows 64 位：`opencl.installer.exe`
- Windows 64 位：`with-katago.portable.zip`
- Windows 64 位：`with-katago.installer.exe`
- Windows 64 位：`nvidia.portable.zip`
- Windows 64 位：`nvidia.installer.exe`
- Windows 64 位：`nvidia50.cuda.portable.zip`
- Windows 64 位：`nvidia50.cuda.installer.exe`
- Windows 64 位：`nvidia50.trt.portable.zip`
- Windows 64 位：`nvidia50.trt.installer.exe`
- Windows 64 位：`without.engine.portable.zip`
- Windows 64 位：`without.engine.installer.exe`
- macOS Apple Silicon：`.dmg`
- macOS Intel：`.dmg`
- Linux 64 位：`with-katago.zip`

历史兼容包只有在明确需要时才通过额外开关构建，不再进入主 release 页面。

### 3. 内置 KataGo 整合体验

整合包的目标是：

- 带上合适的引擎与权重
- 第一次启动尽量自动识别内置 KataGo
- 少让用户手动填引擎路径

如果某个改动会影响这个目标，需要优先评估用户体验再合并。

## 当前统一术语

为了避免 README、发布页、界面、issue 模板各说各话，现在统一使用以下口径：

- 中文：`野狐昵称`
- 英文：`Fox nickname`
- 包类型：`with-katago`、`nvidia`、`without.engine`

不要再要求用户先理解 UID 或账号数字，再去抓谱。

## 发布维护原则

### 1. 用户先看懂，再下载

发布页要做到用户一眼能理解：

- 哪个包适合自己系统
- 哪个包是开箱即用
- 哪个包需要自己配引擎

### 2. 少而清楚，不要包型泛滥

目前已经明确收缩的方向：

- 不再保留多余的 macOS `.app.zip`
- 不再把历史兼容包混进主 release 页面
- Windows 64 位必须同时兼顾开箱即用用户和自定义引擎用户

### 3. Release Notes 的第一句要讲清价值

每次发版，最前面都应该先写最核心的用户价值，例如：

- 原版野狐棋谱同步已失效，这个维护版已修复
- 输入野狐昵称即可获取最新公开棋谱，程序会自动找到账号
- Windows 主推荐已经切到免安装 `.portable.zip`

## 维护者建议遵守的检查项

每次改动下面这些内容时，请至少补一轮自查：

- 野狐抓谱逻辑
- 包内引擎路径
- 权重文件位置
- 发布包文件名
- README 下载建议
- 界面和文档里“野狐昵称 / Fox nickname”的说法有没有保持一致
