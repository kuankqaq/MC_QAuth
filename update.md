# QAuth 更新日志

## v1.5.0

### 新增

- 新增 `auth.enabled` / `auth.enabled=false` 配置，可关闭 QQ 验证，只保留 WebSocket 双向聊天。
- MC 端 WebSocket 现在支持接收 QQ 消息并广播到游戏内。
- OneBot 机器人 `/chat` 优先使用 WebSocket 发送到 MC。
- 新增 Forge 1.7.10 版本。

### 变更

- Bukkit、Fabric、Forge 版本号统一更新为 `1.5.0`。
- Bukkit 默认配置文件恢复为正常 UTF-8 中文。
- Fabric 构建代理端口更新为 Clash 常用端口 `7890`。
- Forge 1.7.10 构建修复旧 ForgeGradle 下载 URL、Forge 坐标和编译 classpath 问题。

### 构建产物

- `target/Qauth-1.5.0-Release.jar`
- `QAuth-Fabric/build/libs/qauth-fabric-1.5.0.jar`
- `QAuth-Forge1710/build/libs/QAuth-Forge1710-1.5.0.jar`

## v1.4.1

- 增加 MC 到 QQ 的 WebSocket 聊天转发。
- 增加 QQ 到 MC 的 `/chat` 聊天命令。
- 增加 `websocket.enabled` 和 `websocket.port` 配置。
