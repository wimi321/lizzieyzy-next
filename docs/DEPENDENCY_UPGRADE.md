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

### 未升级的依赖

| 依赖 | 当前版本 | 最新版本 | 原因 |
|------|----------|----------|------|
| `socket.io-client` | 1.0.0 | 2.1.2 | API不兼容，需要代码修改 |

### socket.io-client 升级注意事项

socket.io-client 2.x版本与1.x版本存在API不兼容：

**需要修改的代码位置**: `src/main/java/featurecat/lizzie/gui/OnlineDialog.java`

**API变更**:
- `Socket.EVENT_CONNECT` → `Socket.EVENT_CONNECT` (保持不变)
- `Socket.EVENT_MESSAGE` → 私有化，需要使用其他方式
- `Socket.EVENT_ERROR` → 需要使用字符串常量
- `Socket.EVENT_PING` → 需要使用字符串常量
- `Socket.EVENT_PONG` → 需要使用字符串常量
- `Socket.EVENT_CONNECT_TIMEOUT` → 需要使用字符串常量
- `Socket.EVENT_RECONNECT` → 需要使用字符串常量
- `Socket.EVENT_RECONNECT_ATTEMPT` → 需要使用字符串常量
- `Socket.EVENT_RECONNECT_FAILED` → 需要使用字符串常量
- `Socket.EVENT_RECONNECT_ERROR` → 需要使用字符串常量
- `Socket.EVENT_RECONNECTING` → 需要使用字符串常量

**升级代码示例**:
```java
// 旧代码 (1.x)
socket.on(Socket.EVENT_CONNECT, args -> { ... });
socket.on(Socket.EVENT_MESSAGE, args -> { ... });

// 新代码 (2.x)
import io.socket.client.Socket;
socket.on(Socket.EVENT_CONNECT, args -> { ... });
socket.on("message", args -> { ... });  // 使用字符串常量
```

---

## 阶段四：Java版本升级（待规划）

### 当前状态
- **当前版本**: Java 8
- **Java 8 商业支持结束**: 2025年1月14日

### 推荐升级路径
- **首选**: Java 17 LTS
- **可选**: Java 21 LTS

### 升级前置条件

1. **fmt-maven-plugin升级**
   - 当前版本2.5.1不支持Java 11+
   - 需要升级到2.13+版本

2. **代码兼容性检查**
   - 使用 `jdeps` 工具分析依赖
   - 检查内部API使用情况
   - 评估第三方库兼容性

3. **可能需要的JVM参数**
```
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.desktop/java.awt=ALL-UNNAMED
```

### Java升级步骤

1. **准备阶段**
   - 运行 `jdeps --jdk-internals` 分析代码
   - 检查所有依赖的Java 17兼容性
   - 创建测试分支

2. **编译配置更新**
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.13.0</version>
    <configuration>
        <source>17</source>
        <target>17</target>
    </configuration>
</plugin>
```

3. **fmt-maven-plugin升级**
```xml
<plugin>
    <groupId>com.coveo</groupId>
    <artifactId>fmt-maven-plugin</artifactId>
    <version>2.13</version>
</plugin>
```

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
