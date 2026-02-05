# 📘 QAuth & 机器人绑定系统 - 完整帮助文档

这份文档汇集了 **Minecraft 插件 (QAuth v1.4.1)** 和 **Nonebot2 机器人** 的所有功能、指令及配置说明。

---

## 1. 玩家指南 (Player Guide)

**适用对象**：所有进入服务器的玩家。

当玩家进入服务器时，如果未绑定 QQ，将会被**限制移动**并收到屏幕提示。

### 🕹️ 游戏内指令

| 指令    | 描述                                          | 示例    |
| :------ | :-------------------------------------------- | :------ |
| `/link` | 获取绑定验证码。系统会生成带服务器前缀的验证码。 | `/link` |
| `#消息` | 发送消息到 QQ 群 (需服务器开启双向聊天功能)    | `#大家好` |

### 验证码格式

| 版本 | 格式 | 示例 |
|------|------|------|
| v1.2 (旧) | 6位随机字符 | `a1b2c3` |
| v1.3 (新) | 服务器ID-6位随机字符 | `sv1-a1b2c3` |

### 🤖 机器人指令 (QQ)

| 指令               | 描述                                       | 示例            |
| :----------------- | :----------------------------------------- | :-------------- |
| `绑定 <验证码>`    | 发送在游戏内获取的验证码，完成绑定并解锁。 | `绑定 sv1-a1b2c3` |
| `/mc查询 <游戏名>` | 查询某个游戏 ID 绑定了哪个 QQ。            | `/mc查询 Steve` |
| `/查绑定 <游戏名>` | 同上，别名指令。                           | `/查绑定 Steve` |
| `/服务器列表`      | 查看所有已配置的服务器                     | `/服务器列表`   |
| `/chat [服务器ID] <消息>` | 发送消息到 MC 服务器 (双向聊天)      | `/chat sv1 你好` |

---

## 2. 管理员指南 (Admin Guide)

**适用对象**：服务器 OP、后台管理员、机器人超级用户。

### 🕹️ 游戏内管理指令 (QAuth 插件)

*   **权限节点**：`qauth.admin` (默认 OP 拥有)
*   **主指令**：`/qadmin`

| 指令                    | 描述                        | 备注                                                   |
| :---------------------- | :-------------------------- | :----------------------------------------------------- |
| `/qadmin help`          | 查看管理员帮助菜单。        | 显示所有可用指令                                       |
| `/qadmin unlock <ID>`   | **强制解锁**指定玩家。      | 无论该玩家是否绑定QQ，直接解除移动限制并打上验证标签。 |
| `/qadmin verify <code>` | **(内部指令)** 验证码回调。 | **请勿手动输入**，这是给机器人 RCON 自动调用的接口。   |

### 🤖 机器人管理指令 (QQ)

*   **权限要求**：需要在 `.env` 文件中配置 `SUPERUSERS`。

| 指令                          | 描述                                                         | 示例                           |
| :---------------------------- | :----------------------------------------------------------- | :----------------------------- |
| `/更改mc信息 <服务器ID> <ID> @某人` | **强制绑定/改绑**。强制将游戏ID绑定给指定QQ。如果玩家在线且被冻结，会同步解锁。 | `/更改mc信息 sv1 Steve @小明` |
| `/强制绑定 <服务器ID> <ID> @某人`   | 同上，别名指令。                                             | `/强制绑定 sv1 Steve @小明`   |

---

## 3. 部署与配置 (Deployment)

### 3.1 支持的平台

| 平台 | 版本 | 文件 |
|------|------|------|
| Bukkit/Spigot/Paper | 1.21+ | `Qauth-1.4.1-Release.jar` |
| Fabric | 1.20.1 | `qauth-fabric-1.4.1.jar` |

### 3.2 Minecraft 服务端设置

文件：`server.properties`

必须确保 RCON 开启，且密码与机器人配置一致。

```properties
enable-rcon=true
rcon.port=25575
rcon.password=你的强密码
broadcast-rcon-to-ops=false
```

### 3.3 插件配置

#### Bukkit 版本 (plugins/QAuth/config.yml)

```yaml
# 服务器唯一标识符
server-id: "sv1"

# WebSocket 双向聊天 (可选)
websocket:
  enabled: false
  port: 25580

# 自定义消息
messages:
  not-bound: "&c您的账号未绑定QQ，已被限制移动！"
  use-link: "&a请输入指令 /link 获取验证码"
  code-generated: "&a验证码: &b{code} &7(请发给机器人: 绑定 {code})"
  already-verified: "&a无需重复验证。"
  verify-success: "&a【系统】验证成功/绑定信息已更新，限制解除！"
```

#### Fabric 版本 (config/qauth.properties)

```properties
server-id=sv1
websocket.enabled=false
websocket.port=25580
msg.not-bound=§c您的账号未绑定QQ，已被限制移动！
msg.use-link=§a请输入指令 /link 获取验证码
msg.code-generated=§a验证码: §b{code} §7(请发给机器人: 绑定 {code})
msg.already-verified=§a无需重复验证。
msg.verify-success=§a【系统】验证成功/绑定信息已更新，限制解除！
```

### 3.4 Python 机器人设置

文件：`.env`

```properties
# 基础配置
COMMAND_START=["/"]
SUPERUSERS=["你的QQ号"]

# MC 服务器 RCON 配置 (多服务器JSON格式)
RCON_SERVERS={"sv1":{"host":"127.0.0.1","port":25575,"password":"pass1","name":"生存服"},"sv2":{"host":"127.0.0.1","port":25576,"password":"pass2","name":"创造服"}}

# 双向聊天配置 (可选)
CHAT_GROUP_ID=123456789
WS_SERVERS={"sv1":"ws://mc-server-ip:25580","sv2":"ws://mc-server2-ip:25580"}
```

**依赖**: 双向聊天需要安装 `pip install websockets`

### 3.5 数据库说明

*   **文件位置**：机器人根目录下的 `data.db` (SQLite)。
*   **表结构**：
    *   `qq_id` (TEXT, 主键)
    *   `game_name` (TEXT, 唯一)
    *   `created_at` (时间戳)
*   **重置数据**：若要清空所有绑定关系，直接删除 `data.db` 文件重启机器人即可。

---

## 4. 多服务器配置示例

### 双服务器配置示例

**服务器1 (生存服) config.yml:**
```yaml
server-id: "sv1"
```

**服务器2 (创造服) config.yml:**
```yaml
server-id: "sv2"
```

**机器人 .env:**
```properties
RCON_SERVERS={"sv1":{"host":"192.168.1.10","port":25575,"password":"survival_pass","name":"生存服"},"sv2":{"host":"192.168.1.11","port":25575,"password":"creative_pass","name":"创造服"}}
```

> **警告**: 如果多个服务器使用相同的 `server-id`，会导致验证码冲突！

---

## 5. 双向聊天配置

双向聊天功能允许 MC 服务器与 QQ 群互通消息。

### 架构说明

```
MC 服务器 (WebSocket 服务端, 端口 25580)
    ↑
    │ 机器人主动连接 (反向 WebSocket)
    │
QQ 机器人 (WebSocket 客户端)
```

**优势**: 机器人无需公网 IP，只需 MC 服务器有公网 IP 即可。

### 配置步骤

1. **MC 服务器**: 在 config.yml 或 qauth.properties 中启用 WebSocket
   ```yaml
   websocket:
     enabled: true
     port: 25580
   ```

2. **防火墙**: 开放 WebSocket 端口 (默认 25580)

3. **机器人 .env**: 配置聊天群和 WebSocket 地址
   ```properties
   CHAT_GROUP_ID=123456789
   WS_SERVERS={"sv1":"ws://mc-server-ip:25580"}
   ```

### 使用方式

| 方向 | 操作 | 效果 |
|------|------|------|
| MC → QQ | 玩家发送 `#你好` | QQ 群显示 `[生存服] Steve: 你好` |
| QQ → MC | 用户发送 `/chat 你好` | MC 显示 `[QQ] 昵称: 你好` |

---

## 6. 常见问题 (FAQ)

**Q: 玩家输入 `/link` 后，机器人提示"连接服务器失败"？**
*   检查 Minecraft 服务器是否已完全启动（显示 Done!）。
*   检查 `server.properties` 里的 `rcon.password` 是否和机器人 `.env` 里的一样。
*   检查服务器防火墙是否放行了 25575 端口。

**Q: 我在控制台/后台输入 `/link` 提示"只有玩家可以使用"？**
*   `/link` 获取验证码需要绑定到一个具体的在线玩家身上，控制台没有实体，所以无法使用。请在游戏内输入。

**Q: 机器人提示"服务返回了未知错误：Unknown command"？**
*   说明 Java 插件版本过低，不支持机器人发送的指令。请重新打包上传最新版 (v1.4.1) 的 `QAuth.jar` 并重启服务器。

**Q: 正版玩家改名了怎么办？**
*   现在的逻辑是基于**游戏名**绑定的。如果玩家改名，系统会视其为新玩家（未绑定）。他需要重新输入 `/link` 绑定。管理员也可以使用 `/更改mc信息` 帮他迁移数据。

**Q: 旧版本的验证码还能用吗？**
*   不能。v1.3 版本要求验证码必须带服务器前缀。

**Q: 可以只有一个服务器吗？**
*   可以。即使只有一个服务器，也需要配置 `server-id` 和对应的 RCON 配置。

**Q: server-id 有什么限制？**
*   建议使用简短的英文字母和数字，如 `sv1`、`lobby`、`survival` 等。避免使用特殊字符。

**Q: 双向聊天功能，机器人提示 WebSocket 连接失败？**
*   检查 MC 服务器的 `websocket.enabled` 是否为 `true`
*   检查防火墙是否放行了 WebSocket 端口 (默认 25580)
*   确认 `.env` 中的 `WS_SERVERS` 地址格式正确 (如 `ws://ip:port`)

**Q: 玩家发送 # 消息但 QQ 群没收到？**
*   确认服务器已启用 WebSocket 且机器人已连接
*   检查机器人控制台是否有 "WebSocket 已连接" 的日志

**Q: /chat 命令只能在指定群使用吗？**
*   是的，只有 `CHAT_GROUP_ID` 配置的群才能使用 `/chat` 命令发送消息到 MC。
