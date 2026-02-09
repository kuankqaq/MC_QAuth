# QAuth QQ官方Bot适配器插件

基于 NoneBot2 + `nonebot-adapter-qq` 的 Minecraft 服务器身份验证插件。

采用**多租户架构**，每个QQ群的数据完全隔离，不同服主可以在各自的群中独立使用，互不干扰。

## 架构概述

- 每个QQ群是一个独立租户，拥有自己的服务器列表、绑定数据和管理员
- 服务器配置通过**私聊Bot**完成（多轮对话引导），再到**群聊**中用验证码关联
- 所有数据库表均包含 `group_id` 字段，确保群间数据隔离
- 用户标识使用 QQ 官方适配器的 `openid`（非QQ号）

## 依赖

```
nonebot2
nonebot-adapter-qq
mcrcon
```

## 配置

`.env` 中只需配置全局超管：

```properties
SUPERUSERS=["超管openid"]
```

无需配置 `RCON_SERVERS`、`WS_SERVERS` 等环境变量，所有服务器信息通过私聊Bot添加并存入数据库。

## 添加服务器流程

这是本插件的核心功能，通过私聊 + 群聊两步完成：

```
1. 用户私聊Bot，发送「添加服务器」
2. Bot引导多轮对话，逐步收集：
   → 服务器ID（如 sv1，仅字母数字）
   → 服务器名称（如 生存服）
   → RCON地址（如 127.0.0.1）
   → RCON端口（如 25575）
   → RCON密码
3. Bot自动测试RCON连接
4. 连接成功后生成随机验证码（有效期10分钟）
5. 用户在目标QQ群中发送「服务器验证 <验证码>」
6. 服务器配置写入数据库，关联到该群
7. 用户自动成为该群的插件管理员
```

多轮对话过程中可随时发送「取消」中止。

## 命令参考

### 私聊命令

| 命令 | 说明 |
|------|------|
| `添加服务器` | 多轮对话引导添加MC服务器，完成后生成验证码 |
| `帮助` | 查看私聊帮助信息 |

### 群聊命令 - 普通用户

| 命令 | 说明 |
|------|------|
| `绑定 <验证码>` | 绑定MC账号，验证码格式如 `sv1-a1b2c3` |
| `mc查询 <游戏名>` | 查询游戏名对应的绑定信息 |
| `查绑定 <游戏名>` | 同上 |
| `服务器列表` | 查看本群已配置的服务器 |
| `查询信息` | 查看自己的 openid 和绑定记录 |
| `查询信息 @某人` | 查看他人的 openid 和绑定记录 |
| `服务器验证 <验证码>` | 将私聊中配置好的服务器关联到本群 |
| `帮助` | 查看帮助菜单（根据权限显示不同内容） |

### 群聊命令 - 管理员

需要群管理员或全局超管权限。添加服务器时自动获得群管理员身份。

| 命令 | 说明 |
|------|------|
| `强制绑定 <服务器ID> <游戏名> <openid>` | 强制为指定用户绑定MC账号 |
| `删除服务器 <ID>` | 删除本群的服务器及其所有绑定记录 |
| `修改服务器 <ID> <字段> <新值>` | 修改服务器配置 |

`修改服务器` 可修改的字段：

- `name` - 服务器名称
- `rcon_host` - RCON 地址
- `rcon_port` - RCON 端口
- `rcon_password` - RCON 密码

### 群聊命令 - 全局超管

仅 `.env` 中 `SUPERUSERS` 配置的用户可用。

| 命令 | 说明 |
|------|------|
| `取消服务器管理员 @某人` | 移除某人在本群的管理员身份 |

## 权限模型

插件有三级权限，逐级递增：

| 角色 | 获得方式 | 权限范围 |
|------|---------|---------|
| 普通用户 | 默认 | 绑定、查询、查看服务器列表 |
| 群管理员 | 添加服务器到群时自动获得 | 强制绑定、删除/修改本群服务器 |
| 全局超管 | `.env` 中 `SUPERUSERS` 配置 | 以上全部 + 取消任意群管理员 |

- 群管理员权限仅限所在群，不跨群生效
- 全局超管在所有群中都拥有管理员权限

## 数据库

使用 SQLite，数据库文件为 `data.db`，包含三张表：

### servers 表

存储各群配置的MC服务器信息。

```sql
CREATE TABLE servers (
    group_id TEXT NOT NULL,
    server_id TEXT NOT NULL,
    name TEXT NOT NULL,
    rcon_host TEXT NOT NULL,
    rcon_port INTEGER NOT NULL DEFAULT 25575,
    rcon_password TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id, server_id)
);
```

### binds 表

存储玩家MC账号与QQ的绑定关系。

```sql
CREATE TABLE binds (
    group_id TEXT NOT NULL,
    qq_id TEXT NOT NULL,
    server_id TEXT NOT NULL,
    game_name TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id, qq_id, server_id),
    UNIQUE (group_id, game_name, server_id)
);
```

### group_admins 表

存储各群的插件管理员。

```sql
CREATE TABLE group_admins (
    group_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (group_id, user_id)
);
```

## 注意事项

### openid 相关

- QQ官方Bot适配器返回的用户标识是 `openid`，不是QQ号
- 同一用户在不同Bot下的 openid 不同，更换Bot后绑定数据无法复用
- `强制绑定` 命令需要手动输入目标用户的 openid，可通过 `查询信息 @某人` 获取

### 验证码

- 添加服务器的验证码存储在内存中，Bot重启后失效
- 验证码有效期 10 分钟，过期需重新在私聊中走添加流程
- MC绑定验证码（如 `sv1-a1b2c3`）由游戏内 `/link` 生成，与添加服务器验证码无关

### RCON

- 添加服务器时会自动测试 RCON 连接，连接失败则无法添加
- 所有 RCON 调用通过 `asyncio.to_thread()` 异步执行，不会阻塞事件循环
- MC服务器需安装 QAuth 插件（Bukkit 或 Fabric）并开启 RCON

### 数据隔离

- 同一个MC服务器可以被添加到多个群，各群的绑定数据互不影响
- 删除服务器时会同时删除该群中该服务器的所有绑定记录
- 不同群中可以使用相同的服务器ID（如都叫 `sv1`），不会冲突
