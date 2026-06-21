# QAuth & NoneBot 双向聊天/验证系统

QAuth 是一个 Minecraft QQ 账号验证与双向聊天项目，包含：

- Bukkit/Spigot/Paper 插件
- Fabric 服务端 Mod
- Forge 1.7.10 服务端 Mod
- NoneBot2 OneBot v11 机器人脚本
- QQ 官方 Bot 适配脚本

当前版本：`1.5.0`

## 1.5.0 新功能

- 新增 `auth.enabled` 开关，可以关闭 QQ 账号验证，只保留 WebSocket 双向聊天。
- WebSocket 聊天变成真正双向：
  - MC 玩家发送 `#消息`，机器人转发到 QQ 群。
  - QQ 群用户发送 `/chat [服务器ID] 消息`，机器人通过 WebSocket 转发到 MC。
- OneBot 脚本 `/chat` 优先使用 WebSocket；没有配置 WebSocket 时才回退旧的 RCON `tellraw`。
- 新增 Forge 1.7.10 构建产物。

## 构建产物

| 平台 | 文件 |
| --- | --- |
| Bukkit/Spigot/Paper 1.21+ | `target/Qauth-1.5.0-Release.jar` |
| Fabric 1.20.1 | `QAuth-Fabric/build/libs/qauth-fabric-1.5.0.jar` |
| Forge 1.7.10 | `QAuth-Forge1710/build/libs/QAuth-Forge1710-1.5.0.jar` |

## Minecraft 端配置

### Bukkit `plugins/QAuth/config.yml`

```yaml
server-id: "sv1"

auth:
  enabled: true

websocket:
  enabled: true
  port: 25580

messages:
  not-bound: "&c您的账号未绑定QQ，已被限制移动！"
  use-link: "&a请输入指令 /link 获取验证码"
  code-generated: "&a验证码: &b{code} &7(请发给机器人: 绑定 {code})"
  already-verified: "&a无需重复验证。"
  verify-success: "&a【系统】验证成功/绑定信息已更新，限制解除！"
```

### Fabric / Forge `config/qauth.properties`

```properties
server-id=sv1
auth.enabled=true
websocket.enabled=true
websocket.port=25580
msg.not-bound=§c您的账号未绑定QQ，已被限制移动！
msg.use-link=§a请输入指令 /link 获取验证码
msg.code-generated=§a验证码: §b{code} §7(请发给机器人: 绑定 {code})
msg.already-verified=§a无需重复验证。
msg.verify-success=§a【系统】验证成功/绑定信息已更新，限制解除！
```

### 只保留 WebSocket 聊天

如果你不需要 QQ 账号验证，只想使用 QQ/MC 双向聊天：

```yaml
auth:
  enabled: false

websocket:
  enabled: true
  port: 25580
```

Fabric / Forge 写法：

```properties
auth.enabled=false
websocket.enabled=true
websocket.port=25580
```

关闭验证后：

- 玩家进服不会被冻结。
- `/link` 不再生成验证码。
- `/qadmin verify <code>` 会返回 `FAIL:AuthDisabled`。
- WebSocket 双向聊天仍然可用。

## OneBot 机器人配置

`.env` 示例：

```properties
COMMAND_START=["/"]
SUPERUSERS=["你的QQ号"]

RCON_SERVERS={"sv1":{"host":"127.0.0.1","port":25575,"password":"pass1","name":"生存服"}}

CHAT_GROUP_ID=123456789
WS_SERVERS={"sv1":"ws://mc-server-ip:25580"}
```

依赖：

```bash
pip install websockets mcrcon
```

说明：

- `WS_SERVERS` 用于双向聊天。
- `RCON_SERVERS` 仍用于绑定验证、强制绑定、查询等旧功能。
- `/chat sv1 你好` 会优先通过 WebSocket 发给 MC。
- 如果没有配置 `WS_SERVERS`，`/chat` 会回退到 RCON `tellraw`。

## 游戏内指令

| 指令 | 说明 |
| --- | --- |
| `/link` | 生成绑定验证码。验证关闭时不生效。 |
| `/qadmin verify <code>` | 机器人回调验证验证码。 |
| `/qadmin unlock <player>` | 管理员强制解锁玩家。 |
| `#消息` | 发送消息到 QQ 群。需要启用 WebSocket。 |

## QQ 群指令

| 指令 | 说明 |
| --- | --- |
| `绑定 <验证码>` | 完成账号绑定。 |
| `/mc查询 <游戏名>` / `/查绑定 <游戏名>` | 查询绑定记录。 |
| `/更改mc信息 <服务器ID> <游戏ID> @某人` | 超级用户强制绑定。 |
| `/chat [服务器ID] <消息>` | 发送 QQ 消息到 MC。 |

## 构建

Bukkit：

```bash
mvn clean package
```

Fabric：

```bash
gradle clean build
```

Forge 1.7.10 需要 JDK 8 和 Gradle 2.14

```bash
gradle clean build
```
