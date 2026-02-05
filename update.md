# QAuth 更新日志

## v1.4.1 - 双向聊天功能

### 新增功能
- **MC → QQ**: 玩家发送 `#` 开头的消息自动转发到 QQ 群，并提示玩家
- **QQ → MC**: QQ 群用户通过 `/chat 服务器id 内容` 命令发送消息到 MC 服务器
- WebSocket 反向连接架构，机器人无需公网 IP

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
