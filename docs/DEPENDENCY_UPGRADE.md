# 依赖升级计划

本文档记录了项目依赖的升级计划和执行情况。

## 升级概览

| 阶段 | 状态 | 完成日期 |
|------|------|----------|
| 阶段一：安全修复 | ✅ 已完成 | 2026-04-12 |
| 阶段二：Maven插件升级 | ✅ 已完成 | 2026-04-12 |
| 阶段三：核心依赖升级 | ✅ 已完成 | 2026-04-12 |
| 阶段四：Java版本升级 | 📋 待规划 | - |

---

## 阶段一：安全修复依赖升级

### 已升级的依赖

| 依赖 | 原版本 | 新版本 | 说明 |
|------|--------|--------|------|
| `org.json:json` | 20180130 | **20231013** | 修复多个安全漏洞 |
| `org.java-websocket:Java-WebSocket` | 1.5.0 | **1.6.0** | 修复CVE-2020-11050 SSL主机名验证缺失漏洞 |

### 安全漏洞详情

#### org.json:json
- **漏洞**: 多个安全漏洞
- **风险等级**: 高
- **修复版本**: 20231013+

#### Java-WebSocket
- **CVE**: CVE-2020-11050
- **漏洞**: SSL主机名验证缺失，可能导致MITM攻击
- **修复版本**: 1.5.1+

---

## 阶段二：Maven插件升级

### 已升级的插件

| 插件 | 原版本 | 新版本 | 说明 |
|------|--------|--------|------|
| `maven-compiler-plugin` | 3.8.1 | **3.13.0** | 支持最新Java特性 |
| `maven-jar-plugin` | 3.0.2 | **3.4.2** | 修复多个bug |
| `maven-shade-plugin` | 3.1.0 | **3.6.2** | 增强稳定性，修复安全问题 |
| `maven-surefire-plugin` | 2.9 | **3.5.2** | 测试框架更新 |

### 未升级的插件

| 插件 | 当前版本 | 最新版本 | 原因 |
|------|----------|----------|------|
| `fmt-maven-plugin` | 2.5.1 | 2.13 | 需要Java 11+，当前项目使用Java 8 |

---

## 阶段三：核心依赖升级

### 已升级的依赖

| 依赖 | 原版本 | 新版本 | 说明 |
|------|--------|--------|------|
| `jcefmaven` | 95.7.14.11 | **127.3.1** | Chromium从95升级到127，重大安全更新 |
| `ganymed-ssh2` | build210 | **262** | SSH库更新 |

### 不升级的依赖

| 依赖 | 当前版本 | 最新版本 | 原因 |
|------|----------|----------|------|
| `socket.io-client` | 1.0.0 | 2.1.2 | ⚠️ 服务器兼容性 - 不可升级 |

### socket.io-client 版本说明

#### 服务器版本确认

| 服务器地址 | Socket.IO 版本 | 实现方式 |
|------------|----------------|----------|
| `rtgame.yikeweiqi.com` | 2.x | Netty-SocketIO 1.7.x |
| `wshall.huanle.qq.com` | 2.x | Netty-SocketIO 1.7.x |

#### 版本兼容性矩阵

| 客户端版本 | 兼容的服务器版本 | 当前状态 |
|------------|------------------|----------|
| **1.x** (当前使用) | Socket.IO 2.x | ✅ 兼容 |
| 2.x | Socket.IO 3.x/4.x | ❌ 不兼容 |

#### 结论

**socket.io-client 保持 1.0.0 版本，不进行升级。**

原因：
1. 服务器运行 Socket.IO 2.x（Netty-SocketIO 1.7.x）
2. socket.io-client 2.x 仅兼容 Socket.IO 3.x/4.x 服务器
3. 升级客户端将导致无法连接服务器

#### 未来升级路径

如需升级 socket.io-client 到 2.x，需要：
1. 服务器端先升级到 Socket.IO 3.x/4.x
2. 修改 OnlineDialog.java 中的事件监听代码
3. 全面测试连接功能

---

## 阶段四：Java版本升级 ✅ 已完成

### 升级结果
- **原版本**: Java 8
- **新版本**: Java 17 LTS
- **Java 8 商业支持结束**: 2025年1月14日

### 兼容性分析结果

#### ✅ 代码兼容性 - 良好

| 检查项 | 状态 | 说明 |
|--------|------|------|
| `sun.*` 内部API | ✅ 无问题 | 仅在注释和JVM参数中使用 |
| `javax.xml.bind` | ✅ 无问题 | 未使用 |
| `SecurityManager` | ✅ 无问题 | 未使用 |
| `URLClassLoader` 强转 | ✅ 无问题 | 未使用 |
| 反射访问私有字段 | ✅ 无问题 | 未使用 |
| 已移除API | ✅ 无问题 | 未使用 |

#### 依赖兼容性

| 依赖 | 版本 | Java 17 兼容性 | 说明 |
|------|------|----------------|------|
| `jcefmaven` | 127.3.1 | ✅ 支持 | 需要添加JVM参数 |
| `fmt-maven-plugin` | 2.29 (Spotify) | ✅ 支持 | 从 com.coveo 迁移到 com.spotify.fmt |
| `socket.io-client` | 1.0.0 | ✅ 支持 | 无问题 |
| `ganymed-ssh2` | 262 | ✅ 支持 | 无问题 |
| `Java-WebSocket` | 1.6.0 | ✅ 支持 | 无问题 |

### 已执行的变更

#### 1. pom.xml 编译配置更新

```xml
<source>17</source>
<target>17</target>
```

#### 2. fmt-maven-plugin 迁移

从 `com.coveo:fmt-maven-plugin:2.5.1` 迁移到 `com.spotify.fmt:fmt-maven-plugin:2.29`

原因：com.coveo 版本 2.13 在 Java 17 上因模块访问限制无法运行（`jdk.compiler` 不导出 `com.sun.tools.javac.parser`），Spotify 维护的版本 2.29 使用 fork 模式运行 google-java-format，原生支持 Java 17。

#### 3. Java 版本解析修复

[Lizzie.java](file:///e:/project/lizzieyzy-next/src/main/java/featurecat/lizzie/Lizzie.java) 中的版本解析逻辑已更新，支持 Java 9+ 的新版本号格式（如 `17.0.1` 而非 `1.8.0_xxx`）。

#### 4. JVM 启动参数添加

在 [CaptureTsumeGo.java](file:///e:/project/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/CaptureTsumeGo.java) 和 [ReadBoard.java](file:///e:/project/lizzieyzy-next/src/main/java/featurecat/lizzie/analysis/ReadBoard.java) 中添加了条件性 JVM 参数：

```java
if (Lizzie.javaVersion >= 17) {
    jvmArgs.add("--add-opens");
    jvmArgs.add("java.desktop/sun.awt=ALL-UNNAMED");
    jvmArgs.add("--add-opens");
    jvmArgs.add("java.desktop/java.awt=ALL-UNNAMED");
    jvmArgs.add("--add-opens");
    jvmArgs.add("java.base/java.lang=ALL-UNNAMED");
}
```

### 应用启动要求

使用 JDK 17 运行应用时，需要添加以下 JVM 参数：

```bash
java --add-opens java.desktop/sun.awt=ALL-UNNAMED \
     --add-opens java.desktop/java.awt=ALL-UNNAMED \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     -jar lizzie-yzy2.5.3-shaded.jar
```

### 验证结果

- [x] 使用 JDK 17 编译成功
- [x] 使用 JDK 17 打包成功
- [ ] 应用启动正常（需手动验证）
- [ ] JCEF 浏览器功能正常（需手动验证）
- [ ] 所有 GUI 功能正常（需手动验证）
- [ ] WebSocket 连接正常（需手动验证）
- [ ] SSH 连接正常（需手动验证）
- [ ] 跨平台测试 (Windows/macOS/Linux)（需手动验证）

---

## 验证清单

### 已验证项目
- [x] 项目编译成功 (`mvn clean compile`)
- [x] 项目打包成功 (`mvn package`)
- [ ] 单元测试通过 (`mvn test`)
- [ ] 应用启动正常
- [ ] WebSocket连接功能正常
- [ ] SSH连接功能正常
- [ ] 内嵌浏览器功能正常
- [ ] JSON解析功能正常
- [ ] 跨平台测试 (Windows/macOS/Linux)

### 功能测试建议

1. **WebSocket功能测试**
   - 测试WebSocket连接建立
   - 测试消息收发
   - 测试断线重连

2. **SSH功能测试**
   - 测试SSH连接建立
   - 测试命令执行
   - 测试文件传输

3. **JCEF浏览器功能测试**
   - 测试浏览器初始化
   - 测试页面加载
   - 测试JavaScript交互

---

## 回滚方案

如果升级后出现问题，可以通过Git回滚到升级前的版本：

```bash
# 查看升级前的提交
git log --oneline

# 回滚到指定版本
git checkout <commit-hash>
```

---

## 参考链接

- [Maven Compiler Plugin](https://maven.apache.org/plugins/maven-compiler-plugin/)
- [Maven Shade Plugin](https://maven.apache.org/plugins/maven-shade-plugin/)
- [JCEF Maven](https://github.com/jcefmaven/jcefmaven)
- [Socket.IO Java Client](https://github.com/socketio/socket.io-client-java)
- [Java 8 to 17 Migration Guide](https://openjdk.org/)
