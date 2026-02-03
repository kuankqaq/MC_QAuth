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
