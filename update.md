# QAuth v1.3 更新日志

## 更新概述

本次更新将 QAuth 从单服务器支持升级为多服务器支持，并新增 Fabric 1.20.1 版本。

---

## 主要更新内容

### 1. 多服务器支持
- 验证码格式变更：`6位随机字符` → `服务器ID-6位随机字符`
- 新增 `server-id` 配置项
- 机器人支持多服务器 RCON 路由

### 2. 新增 Fabric 1.20.1 支持
- 新增 `QAuth-Fabric/` 目录
- 支持 Minecraft 1.20.1 + Fabric Loader 0.15.x
- 功能与 Bukkit 版本完全一致

### 3. 新增功能
- bStats 统计支持
- 可自定义消息配置
- `/服务器列表` 指令

---

## 验证码格式变更

| 版本 | 格式 | 示例 |
|------|------|------|
| v1.2 (旧) | 6位随机字符 | `a1b2c3` |
| v1.3 (新) | 服务器ID-6位随机字符 | `sv1-a1b2c3` |

---

## 文件修改清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `QAuth/pom.xml` | 修改 | 版本升级、添加bStats依赖 |
| `QAuth/src/.../plugin.yml` | 修改 | 版本号 1.2 → 1.3 |
| `QAuth/src/.../config.yml` | 新建 | 服务器ID和自定义消息配置 |
| `QAuth/src/.../QAuth.java` | 修改 | 多服务器支持、bStats统计 |
| `QAuth-Fabric/` | 新建 | Fabric mod 项目 |
| `QAuth_nb2/__init__.py` | 修改 | 多服务器RCON路由 |

---

## 部署注意事项

### MC服务器端
1. 替换 `QAuth.jar` 为新版本
2. 首次启动后编辑 `plugins/QAuth/config.yml`
3. **必须**为每个服务器设置唯一的 `server-id`
4. 重启服务器

### 机器人端
1. 更新 `QAuth_nb2/__init__.py`
2. 修改 `.env` 文件，配置 RCON 信息
3. 重启机器人

---

## Fabric Mod 构建

```bash
cd QAuth-Fabric
./gradlew build
```

构建产物：`QAuth-Fabric/build/libs/qauth-fabric-1.3.jar`
