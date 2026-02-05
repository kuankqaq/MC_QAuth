# QAuth 更新日志

## v1.4.1 - 双向聊天功能

### 新增功能
- **MC → QQ**: 玩家发送 `#` 开头的消息自动转发到 QQ 群，并提示玩家
- **QQ → MC**: QQ 群用户通过 `/chat` 命令发送消息到 MC 服务器
- WebSocket 反向连接架构，机器人无需公网 IP

### Bug 修复
- 修复 `/chat` 命令的 FinishedException 错误处理
- 玩家发送 `#` 消息后显示"消息已转发到QQ群"提示
- QQ→MC 消息使用 `tellraw` 命令，去除 `[Server]` 前缀

### 版本号更新
- Bukkit: 1.4.1-Release
- Fabric: 1.4.1

### 文件修改

| 文件 | 操作 | 说明 |
|------|------|------|
| `pom.xml` | 修改 | 添加 Java-WebSocket 依赖 |
| `QAuth-Fabric/build.gradle` | 修改 | 添加 Java-WebSocket 依赖 |
| `src/main/java/com/kuank/QAuth.java` | 修改 | WebSocket 服务端 + 聊天监听 |
| `QAuth-Fabric/.../QAuthMod.java` | 修改 | WebSocket 服务端 + 聊天监听 |
| `QAuth_nb2/__init__.py` | 修改 | WebSocket 客户端 + /chat 命令 |
| `src/main/resources/config.yml` | 修改 | 新增 websocket 配置节 |

### 新增配置项

**MC 服务器 (config.yml / qauth.properties)**:
```yaml
websocket:
  enabled: false
  port: 25580
```

**机器人 (.env)**:
```properties
CHAT_GROUP_ID=123456789
WS_SERVERS={"sv1":"ws://mc-server-ip:25580"}
```

### 依赖要求
- 机器人需安装: `pip install websockets`

---

# QAuth v1.3 更新日志

## 本次更新内容

### 1. 修复 plugin.yml 版本号
- 将版本号从 `1.2-Release` 修正为 `1.3-Release`

### 2. 新增 Fabric 1.20.1 支持
- 新增 `QAuth-Fabric/` 目录，包含完整的 Fabric mod 项目
- 支持 Minecraft 1.20.1 + Fabric Loader 0.15.x
- 功能与 Bukkit 版本完全一致

### 3. 文档整合
- 将多服务器配置说明合并到 readme.md
- 更新验证码格式说明
- 添加 Fabric 版本安装说明

---

## Fabric Mod 构建方法

```bash
cd QAuth-Fabric
./gradlew build
```

构建产物位于 `QAuth-Fabric/build/libs/qauth-fabric-1.3.jar`

---

## 文件修改清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `src/main/resources/plugin.yml` | 修改 | 版本号 1.2 → 1.3 |
| `QAuth-Fabric/build.gradle` | 新建 | Gradle 构建配置 |
| `QAuth-Fabric/gradle.properties` | 新建 | 版本配置 |
| `QAuth-Fabric/settings.gradle` | 新建 | 项目设置 |
| `QAuth-Fabric/src/.../QAuthMod.java` | 新建 | Mod 主类 |
| `QAuth-Fabric/src/.../fabric.mod.json` | 新建 | Mod 元数据 |
| `readme.md` | 修改 | 合并更新内容 |
| `update.md` | 修改 | 记录本次更新 |
