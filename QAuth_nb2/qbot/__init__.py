import sqlite3
import socket
import struct
import string
import random
import re
import time
import asyncio
from pathlib import Path
from nonebot import on_regex, on_command, get_driver
from nonebot.adapters.qq import Bot, Message, MessageSegment
from nonebot.adapters.qq.event import GroupAtMessageCreateEvent, C2CMessageCreateEvent
from nonebot.params import RegexGroup, CommandArg
from nonebot.exception import FinishedException

DB_FILE = str(Path(__file__).parent / "data.db")

# 待验证的服务器配置（内存存储，10分钟过期）
# {code: {"user_id": str, "config": dict, "created_at": float}}
pending_servers: dict = {}

# 私聊添加服务器的会话状态
# {user_id: {"step": str, "data": dict}}
add_server_sessions: dict = {}


def init_db():
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute('''CREATE TABLE IF NOT EXISTS servers (
        group_id TEXT NOT NULL,
        server_id TEXT NOT NULL,
        name TEXT NOT NULL,
        rcon_host TEXT NOT NULL,
        rcon_port INTEGER NOT NULL DEFAULT 25575,
        rcon_password TEXT NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (group_id, server_id)
    )''')
    c.execute('''CREATE TABLE IF NOT EXISTS binds (
        group_id TEXT NOT NULL,
        qq_id TEXT NOT NULL,
        server_id TEXT NOT NULL,
        game_name TEXT NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (group_id, qq_id, server_id),
        UNIQUE (group_id, game_name, server_id)
    )''')
    c.execute('''CREATE TABLE IF NOT EXISTS group_admins (
        group_id TEXT NOT NULL,
        user_id TEXT NOT NULL,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (group_id, user_id)
    )''')
    conn.commit()
    conn.close()


init_db()


# ==================== 辅助函数 ====================

def generate_code(length=6) -> str:
    return ''.join(random.choices(string.ascii_lowercase + string.digits, k=length))


def is_group_admin(group_id: str, user_id: str) -> bool:
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("SELECT 1 FROM group_admins WHERE group_id=? AND user_id=?",
              (group_id, user_id))
    result = c.fetchone() is not None
    conn.close()
    return result


def get_server_config(group_id: str, server_id: str) -> dict | None:
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute(
        "SELECT name, rcon_host, rcon_port, rcon_password "
        "FROM servers WHERE group_id=? AND server_id=?",
        (group_id, server_id)
    )
    row = c.fetchone()
    conn.close()
    if row:
        return {"name": row[0], "host": row[1], "port": row[2], "password": row[3]}
    return None


def get_group_servers(group_id: str) -> list:
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("SELECT server_id, name FROM servers WHERE group_id=?", (group_id,))
    rows = c.fetchall()
    conn.close()
    return rows


async def rcon_command(host: str, password: str, port: int, command: str) -> str:
    def _run():
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(5)
        try:
            sock.connect((host, port))
            # 登录
            _rcon_send(sock, 0, 3, password)
            req_id, _, _ = _rcon_recv(sock)
            if req_id == -1:
                raise Exception("RCON 认证失败，密码错误")
            # 发送命令
            _rcon_send(sock, 1, 2, command)
            _, _, resp = _rcon_recv(sock)
            return resp
        finally:
            sock.close()
    return await asyncio.to_thread(_run)


def _rcon_send(sock: socket.socket, req_id: int, pkt_type: int, payload: str):
    data = struct.pack('<ii', req_id, pkt_type) + payload.encode('utf-8') + b'\x00\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)


def _rcon_recv(sock: socket.socket):
    raw_len = _recv_exact(sock, 4)
    length = struct.unpack('<i', raw_len)[0]
    data = _recv_exact(sock, length)
    req_id = struct.unpack('<i', data[0:4])[0]
    pkt_type = struct.unpack('<i', data[4:8])[0]
    payload = data[8:-2].decode('utf-8', errors='replace')
    return req_id, pkt_type, payload


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = b''
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("RCON 连接中断")
        buf += chunk
    return buf


def clean_expired_pending():
    now = time.time()
    expired = [k for k, v in pending_servers.items() if now - v["created_at"] > 600]
    for k in expired:
        del pending_servers[k]


def is_superuser(user_id: str) -> bool:
    config = get_driver().config
    superusers = getattr(config, "superusers", set())
    return user_id in superusers


def parse_code(code: str):
    if "-" in code:
        parts = code.split("-", 1)
        return parts[0], parts[1]
    return None, code


# ==================== 帮助菜单 ====================

HELP_USER = (
    "【QAuth 帮助菜单】\n"
    "\n"
    "--- 用户命令 ---\n"
    "绑定 <验证码>  绑定MC账号（如：绑定 sv1-a1b2c3）\n"
    "mc查询 <游戏名>  查询绑定信息\n"
    "查绑定 <游戏名>  同上\n"
    "服务器列表  查看本群已配置的服务器\n"
    "查询信息  查看自己的openid和绑定\n"
    "查询信息 @某人  查看他人信息\n"
    "服务器验证 <验证码>  将服务器关联到本群"
)

HELP_ADMIN = (
    "\n\n--- 管理员命令 ---\n"
    "强制绑定 <服务器ID> <游戏名> <openid>  强制绑定用户\n"
    "删除服务器 <ID>  删除本群的服务器\n"
    "修改服务器 <ID> <字段> <新值>  修改服务器配置\n"
    "  可修改字段: name, rcon_host, rcon_port, rcon_password"
)

HELP_SUPER = (
    "\n\n--- 超管命令 ---\n"
    "取消服务器管理员 @某人  移除本群管理员"
)

HELP_PRIVATE = (
    "【QAuth 私聊帮助】\n"
    "\n"
    "添加服务器  多轮对话引导添加MC服务器\n"
    "帮助  查看本帮助"
)

help_cmd = on_command("帮助", aliases={"菜单", "help"}, priority=10)


@help_cmd.handle()
async def handle_help(bot: Bot, event: GroupAtMessageCreateEvent):
    group_id = event.group_openid
    user_id = event.get_user_id()

    msg = HELP_USER
    if is_group_admin(group_id, user_id) or is_superuser(user_id):
        msg += HELP_ADMIN
    if is_superuser(user_id):
        msg += HELP_SUPER
    await help_cmd.finish(msg)


help_private_cmd = on_command("服务器帮助", aliases={"菜单", "help"}, priority=11)


@help_private_cmd.handle()
async def handle_help_private(bot: Bot, event: C2CMessageCreateEvent):
    await help_private_cmd.finish(HELP_PRIVATE)


# ==================== 私聊：添加服务器（多轮对话） ====================

ADD_STEPS = ["server_id", "name", "rcon_host", "rcon_port", "rcon_password"]
ADD_PROMPTS = {
    "server_id": "请输入服务器ID（仅限字母和数字，如 sv1）：",
    "name": "请输入服务器名称（如 生存服）：",
    "rcon_host": "请输入RCON地址（如 127.0.0.1）：",
    "rcon_port": "请输入RCON端口（1-65535，如 25575）：",
    "rcon_password": "请输入RCON密码：",
}

add_server_cmd = on_command("添加服务器", priority=5)


@add_server_cmd.handle()
async def add_server_start(bot: Bot, event: C2CMessageCreateEvent):
    user_id = event.get_user_id()
    add_server_sessions[user_id] = {"step": "server_id", "data": {}}
    await add_server_cmd.send(
        "开始添加服务器，请按提示逐步输入。\n\n" + ADD_PROMPTS["server_id"]
    )


@add_server_cmd.got("input")
async def add_server_step(bot: Bot, event: C2CMessageCreateEvent):
    user_id = event.get_user_id()
    session = add_server_sessions.get(user_id)
    if not session:
        add_server_sessions.pop(user_id, None)
        await add_server_cmd.finish("会话已过期，请重新发送「添加服务器」。")

    text = str(event.get_message()).strip()
    step = session["step"]

    # 允许用户中途取消
    if text == "取消":
        add_server_sessions.pop(user_id, None)
        await add_server_cmd.finish("已取消添加服务器。")

    # 校验当前步骤输入
    if step == "server_id":
        if not re.match(r'^[a-zA-Z0-9]+$', text):
            await add_server_cmd.reject("服务器ID只能包含字母和数字，请重新输入：")
        session["data"]["server_id"] = text

    elif step == "name":
        if not text:
            await add_server_cmd.reject("名称不能为空，请重新输入：")
        session["data"]["name"] = text

    elif step == "rcon_host":
        if not text:
            await add_server_cmd.reject("地址不能为空，请重新输入：")
        session["data"]["rcon_host"] = text

    elif step == "rcon_port":
        try:
            port = int(text)
            if port < 1 or port > 65535:
                raise ValueError
        except ValueError:
            await add_server_cmd.reject("端口必须是1-65535之间的数字，请重新输入：")
        session["data"]["rcon_port"] = port

    elif step == "rcon_password":
        session["data"]["rcon_password"] = text

    # 推进到下一步
    step_idx = ADD_STEPS.index(step)
    if step_idx + 1 < len(ADD_STEPS):
        next_step = ADD_STEPS[step_idx + 1]
        session["step"] = next_step
        await add_server_cmd.reject(ADD_PROMPTS[next_step])

    # 所有步骤完成，测试RCON连接
    data = session["data"]
    add_server_sessions.pop(user_id, None)

    await add_server_cmd.send("正在测试RCON连接...")
    try:
        resp = await rcon_command(
            data["rcon_host"], data["rcon_password"],
            data["rcon_port"], "list"
        )
    except Exception as e:
        await add_server_cmd.finish(f"RCON连接失败: {e}\n请检查配置后重新发送「添加服务器」。")

    # 连接成功，生成验证码
    clean_expired_pending()
    code = generate_code(8)
    pending_servers[code] = {
        "user_id": user_id,
        "config": data,
        "created_at": time.time(),
    }
    await add_server_cmd.finish(
        f"RCON连接成功！（{resp.strip()}）\n\n"
        f"验证码: {code}\n"
        f"请在目标QQ群中发送「服务器验证 {code}」将此服务器关联到该群。\n"
        f"验证码有效期10分钟。"
    )


# ==================== 群聊：服务器验证 ====================

server_verify_cmd = on_regex(r"^服务器验证\s+([a-zA-Z0-9]+)$", priority=5)


@server_verify_cmd.handle()
async def handle_server_verify(bot: Bot, event: GroupAtMessageCreateEvent,
                               args: tuple = RegexGroup()):
    code = args[0]
    group_id = event.group_openid
    user_id = event.get_user_id()

    clean_expired_pending()

    pending = pending_servers.get(code)
    if not pending:
        await server_verify_cmd.finish("验证码无效或已过期。请在私聊中重新添加服务器。")

    if pending["user_id"] != user_id:
        await server_verify_cmd.finish("此验证码不属于你，请使用自己的验证码。")

    config = pending["config"]
    server_id = config["server_id"]

    # 检查该群是否已有同ID服务器
    existing = get_server_config(group_id, server_id)
    if existing:
        await server_verify_cmd.finish(
            f"本群已存在服务器ID「{server_id}」，请使用其他ID或先删除旧服务器。"
        )

    # 写入数据库
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    try:
        c.execute(
            "INSERT INTO servers (group_id, server_id, name, rcon_host, rcon_port, rcon_password) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            (group_id, server_id, config["name"],
             config["rcon_host"], config["rcon_port"], config["rcon_password"])
        )
        # 自动设为群管理员
        c.execute(
            "INSERT OR IGNORE INTO group_admins (group_id, user_id) VALUES (?, ?)",
            (group_id, user_id)
        )
        conn.commit()
    except Exception as e:
        conn.close()
        await server_verify_cmd.finish(f"数据库写入失败: {e}")
    conn.close()

    # 移除已使用的验证码
    pending_servers.pop(code, None)

    await server_verify_cmd.finish(
        f"服务器添加成功！\n"
        f"ID: {server_id}\n"
        f"名称: {config['name']}\n"
        f"你已成为本群的插件管理员。"
    )


# ==================== 群聊用户命令：绑定 ====================

bind_cmd = on_regex(r"^绑定\s+([a-zA-Z0-9]+-[a-zA-Z0-9]+)$", priority=5)


@bind_cmd.handle()
async def handle_bind(bot: Bot, event: GroupAtMessageCreateEvent,
                      args: tuple = RegexGroup()):
    full_code = args[0]
    server_id, code = parse_code(full_code)
    group_id = event.group_openid
    user_id = event.get_user_id()

    if not server_id:
        await bind_cmd.finish("验证码格式错误！请使用完整验证码（如 sv1-a1b2c3）")

    rcon_cfg = get_server_config(group_id, server_id)
    if not rcon_cfg:
        await bind_cmd.finish(f"本群未配置服务器: {server_id}")

    # 检查是否已绑定
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("SELECT game_name FROM binds WHERE group_id=? AND qq_id=? AND server_id=?",
              (group_id, user_id, server_id))
    existing = c.fetchone()
    if existing:
        conn.close()
        await bind_cmd.finish(f"你已在此服务器绑定过账号 {existing[0]}，无法重复绑定！")

    try:
        resp = await rcon_command(
            rcon_cfg["host"], rcon_cfg["password"],
            int(rcon_cfg["port"]), f"qadmin verify {full_code}"
        )
        clean_resp = resp.strip()

        if "SUCCESS:" in clean_resp:
            game_name = clean_resp.split(":")[1]
            c.execute("SELECT qq_id FROM binds WHERE group_id=? AND game_name=? AND server_id=?",
                      (group_id, game_name, server_id))
            if c.fetchone():
                reply = f"游戏账号 {game_name} 已被其他人绑定！"
            else:
                c.execute(
                    "INSERT INTO binds (group_id, qq_id, server_id, game_name) VALUES (?, ?, ?, ?)",
                    (group_id, user_id, server_id, game_name)
                )
                conn.commit()
                server_name = rcon_cfg.get("name", server_id)
                reply = (f"绑定成功！\n游戏ID: {game_name}\n"
                         f"服务器: {server_name}\n祝游戏愉快！")
        elif "FAIL:InvalidCode" in clean_resp:
            reply = "验证码错误或已过期！请在游戏内重新输入 /link 获取。"
        elif "FAIL:PlayerOffline" in clean_resp:
            reply = "玩家不在线！请保持游戏在线状态再进行绑定。"
        else:
            reply = f"服务器返回未知错误: {clean_resp}"
    except FinishedException:
        raise
    except Exception as e:
        reply = f"连接服务器失败: {e}"
    finally:
        conn.close()

    await bind_cmd.finish(reply)


# ==================== 群聊用户命令：查绑定 / mc查询 ====================

query_cmd = on_command("mc查询", aliases={"查绑定"}, priority=5)


@query_cmd.handle()
async def handle_query(bot: Bot, event: GroupAtMessageCreateEvent,
                       args: Message = CommandArg()):
    target_name = args.extract_plain_text().strip()
    group_id = event.group_openid

    if not target_name:
        await query_cmd.finish("请输入要查询的游戏名，例如：mc查询 Steve")

    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("SELECT qq_id, server_id FROM binds WHERE group_id=? AND game_name=?",
              (group_id, target_name))
    rows = c.fetchall()
    conn.close()

    if not rows:
        await query_cmd.finish(f"未查询到玩家 {target_name} 的绑定记录。")

    user_openid = rows[0][0]
    servers = []
    for r in rows:
        cfg = get_server_config(group_id, r[1])
        servers.append(cfg["name"] if cfg else r[1])

    await query_cmd.finish(
        Message(f"游戏ID: {target_name}\n绑定用户: ")
        + MessageSegment.mention_user(user_openid)
        + Message(f"\n服务器: {', '.join(servers)}")
    )


# ==================== 群聊用户命令：服务器列表 ====================

server_list_cmd = on_command("服务器列表", priority=5)


@server_list_cmd.handle()
async def handle_server_list(bot: Bot, event: GroupAtMessageCreateEvent):
    group_id = event.group_openid
    servers = get_group_servers(group_id)

    if not servers:
        await server_list_cmd.finish("本群未配置任何服务器。")

    lines = ["本群已配置的服务器:"]
    for sid, name in servers:
        lines.append(f"  {sid}: {name}")
    await server_list_cmd.finish("\n".join(lines))


# ==================== 群聊用户命令：查询信息 ====================

query_info_cmd = on_command("查询信息", priority=5)


@query_info_cmd.handle()
async def handle_query_info(bot: Bot, event: GroupAtMessageCreateEvent,
                            args: Message = CommandArg()):
    group_id = event.group_openid

    # 检查是否有 mention
    target_openid = None
    for seg in event.get_message():
        if seg.type == "mention_user":
            target_openid = seg.data.get("user_id")
            break

    if not target_openid:
        target_openid = event.get_user_id()

    # 查询该用户在本群的绑定
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("SELECT server_id, game_name FROM binds WHERE group_id=? AND qq_id=?",
              (group_id, target_openid))
    rows = c.fetchall()
    conn.close()

    lines = [f"用户 openid: {target_openid}"]
    if rows:
        lines.append("绑定记录:")
        for sid, gname in rows:
            cfg = get_server_config(group_id, sid)
            sname = cfg["name"] if cfg else sid
            lines.append(f"  {sname}({sid}): {gname}")
    else:
        lines.append("该用户在本群无绑定记录。")

    await query_info_cmd.finish("\n".join(lines))


# ==================== 群聊管理员命令：强制绑定 ====================

force_bind_cmd = on_regex(r"^强制绑定\s+(\S+)\s+(\S+)\s+(\S+)$", priority=3)


@force_bind_cmd.handle()
async def handle_force_bind(bot: Bot, event: GroupAtMessageCreateEvent,
                            args: tuple = RegexGroup()):
    group_id = event.group_openid
    user_id = event.get_user_id()

    if not is_group_admin(group_id, user_id) and not is_superuser(user_id):
        await force_bind_cmd.finish("你没有本群管理员权限。")

    server_id, game_name, target_openid = args[0], args[1], args[2]

    rcon_cfg = get_server_config(group_id, server_id)
    if not rcon_cfg:
        await force_bind_cmd.finish(f"本群未配置服务器: {server_id}")

    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    try:
        c.execute("DELETE FROM binds WHERE group_id=? AND qq_id=? AND server_id=?",
                  (group_id, target_openid, server_id))
        c.execute("DELETE FROM binds WHERE group_id=? AND game_name=? AND server_id=?",
                  (group_id, game_name, server_id))
        c.execute(
            "INSERT INTO binds (group_id, qq_id, server_id, game_name) VALUES (?, ?, ?, ?)",
            (group_id, target_openid, server_id, game_name)
        )
        conn.commit()

        rcon_info = ""
        try:
            resp = await rcon_command(
                rcon_cfg["host"], rcon_cfg["password"],
                int(rcon_cfg["port"]), f"qadmin unlock {game_name}"
            )
            if "SUCCESS" in resp:
                rcon_info = "\n玩家在线，已同步解锁！"
            else:
                rcon_info = "\n玩家不在线，数据已更新。"
        except Exception as e_rcon:
            rcon_info = f"\nRCON连接失败: {e_rcon}"

        server_name = rcon_cfg.get("name", server_id)
        reply = (f"强制绑定完成。\n游戏ID: {game_name}\n"
                 f"服务器: {server_name}\nopenid: {target_openid}{rcon_info}")
    except FinishedException:
        raise
    except Exception as e:
        reply = f"数据库操作失败: {e}"
    finally:
        conn.close()

    await force_bind_cmd.finish(reply)


# ==================== 群聊管理员命令：删除服务器 ====================

del_server_cmd = on_regex(r"^删除服务器\s+(\S+)$", priority=3)


@del_server_cmd.handle()
async def handle_del_server(bot: Bot, event: GroupAtMessageCreateEvent,
                            args: tuple = RegexGroup()):
    group_id = event.group_openid
    user_id = event.get_user_id()

    if not is_group_admin(group_id, user_id) and not is_superuser(user_id):
        await del_server_cmd.finish("你没有本群管理员权限。")

    server_id = args[0]
    existing = get_server_config(group_id, server_id)
    if not existing:
        await del_server_cmd.finish(f"本群未配置服务器: {server_id}")

    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    try:
        c.execute("DELETE FROM servers WHERE group_id=? AND server_id=?",
                  (group_id, server_id))
        c.execute("DELETE FROM binds WHERE group_id=? AND server_id=?",
                  (group_id, server_id))
        conn.commit()
    except Exception as e:
        conn.close()
        await del_server_cmd.finish(f"删除失败: {e}")
    conn.close()

    await del_server_cmd.finish(
        f"已删除服务器 {server_id}（{existing['name']}）及其所有绑定记录。"
    )


# ==================== 群聊管理员命令：修改服务器 ====================

EDITABLE_FIELDS = {"name", "rcon_host", "rcon_port", "rcon_password"}

modify_server_cmd = on_regex(r"^修改服务器\s+(\S+)\s+(\S+)\s+(.+)$", priority=3)


@modify_server_cmd.handle()
async def handle_modify_server(bot: Bot, event: GroupAtMessageCreateEvent,
                               args: tuple = RegexGroup()):
    group_id = event.group_openid
    user_id = event.get_user_id()

    if not is_group_admin(group_id, user_id) and not is_superuser(user_id):
        await modify_server_cmd.finish("你没有本群管理员权限。")

    server_id, field, new_value = args[0], args[1], args[2].strip()

    if field not in EDITABLE_FIELDS:
        await modify_server_cmd.finish(
            f"不支持修改字段「{field}」。\n可修改: {', '.join(EDITABLE_FIELDS)}"
        )

    existing = get_server_config(group_id, server_id)
    if not existing:
        await modify_server_cmd.finish(f"本群未配置服务器: {server_id}")

    if field == "rcon_port":
        try:
            port_val = int(new_value)
            if port_val < 1 or port_val > 65535:
                raise ValueError
            new_value = str(port_val)
        except ValueError:
            await modify_server_cmd.finish("端口必须是1-65535之间的数字。")

    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    try:
        c.execute(
            f"UPDATE servers SET {field}=? WHERE group_id=? AND server_id=?",
            (new_value, group_id, server_id)
        )
        conn.commit()
    except Exception as e:
        conn.close()
        await modify_server_cmd.finish(f"修改失败: {e}")
    conn.close()

    await modify_server_cmd.finish(
        f"已修改服务器 {server_id} 的 {field} 为: {new_value}"
    )


# ==================== SUPERUSER 专属：取消服务器管理员 ====================

remove_admin_cmd = on_command("取消服务器管理员", priority=2)


@remove_admin_cmd.handle()
async def handle_remove_admin(bot: Bot, event: GroupAtMessageCreateEvent,
                              args: Message = CommandArg()):
    group_id = event.group_openid
    user_id = event.get_user_id()

    if not is_superuser(user_id):
        await remove_admin_cmd.finish("此命令仅限全局超管使用。")

    # 从消息中提取 mention
    target_openid = None
    for seg in event.get_message():
        if seg.type == "mention_user":
            target_openid = seg.data.get("user_id")
            break

    if not target_openid:
        await remove_admin_cmd.finish(
            "请 @ 要取消管理员的用户。\n格式: 取消服务器管理员 @某人"
        )

    if not is_group_admin(group_id, target_openid):
        await remove_admin_cmd.finish("该用户不是本群管理员。")

    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("DELETE FROM group_admins WHERE group_id=? AND user_id=?",
              (group_id, target_openid))
    conn.commit()
    conn.close()

    await remove_admin_cmd.finish(
        Message("已取消 ")
        + MessageSegment.mention_user(target_openid)
        + Message(" 的本群管理员权限。")
    )
