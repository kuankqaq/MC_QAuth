import sqlite3
import json
import asyncio
from nonebot import on_regex, on_command, get_driver, get_bot
from nonebot.adapters.onebot.v11 import Bot, MessageEvent, MessageSegment, Message, GroupMessageEvent
from nonebot.params import RegexGroup, CommandArg
from nonebot.permission import SUPERUSER
from nonebot.exception import FinishedException
from mcrcon import MCRcon

config = get_driver().config
# 多服务器RCON配置
_rcon_cfg = getattr(config, "rcon_servers", {})
if isinstance(_rcon_cfg, str):
    RCON_SERVERS = json.loads(_rcon_cfg) if _rcon_cfg else {}
else:
    RCON_SERVERS = _rcon_cfg if _rcon_cfg else {}

# 双向聊天配置
CHAT_GROUP_ID = getattr(config, "chat_group_id", "")
_ws_cfg = getattr(config, "ws_servers", {})
if isinstance(_ws_cfg, str):
    WS_SERVERS = json.loads(_ws_cfg) if _ws_cfg else {}
else:
    WS_SERVERS = _ws_cfg if _ws_cfg else {}

DB_FILE = "data.db"


def get_rcon_config(server_id: str):
    """根据服务器ID获取RCON配置"""
    return RCON_SERVERS.get(server_id)


def parse_code(code: str):
    """解析验证码，返回(服务器ID, 纯验证码)"""
    if "-" in code:
        parts = code.split("-", 1)
        return parts[0], parts[1]
    return None, code
#数据库
def init_db():
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    # 获取第一个服务器ID作为旧数据的默认服务器
    first_server_id = list(RCON_SERVERS.keys())[0] if RCON_SERVERS else 'default'

    # 检查是否需要迁移旧表
    c.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='binds'")
    if c.fetchone():
        # 检查表结构是否有server_id列
        c.execute("PRAGMA table_info(binds)")
        columns = [col[1] for col in c.fetchall()]
        if 'server_id' not in columns:
            # 迁移旧表：旧玩家绑定到第一个服务器
            c.execute(f"ALTER TABLE binds ADD COLUMN server_id TEXT DEFAULT '{first_server_id}'")
            # 删除旧的唯一约束需要重建表
            c.execute('''CREATE TABLE IF NOT EXISTS binds_new
                         (qq_id TEXT, server_id TEXT, game_name TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                          PRIMARY KEY (qq_id, server_id),
                          UNIQUE (game_name, server_id))''')
            c.execute("INSERT INTO binds_new (qq_id, server_id, game_name, created_at) SELECT qq_id, server_id, game_name, created_at FROM binds")
            c.execute("DROP TABLE binds")
            c.execute("ALTER TABLE binds_new RENAME TO binds")
    else:
        # 创建新表结构：支持多服务器绑定
        c.execute('''CREATE TABLE IF NOT EXISTS binds
                     (qq_id TEXT, server_id TEXT, game_name TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (qq_id, server_id),
                      UNIQUE (game_name, server_id))''')
    conn.commit()
    conn.close()

init_db()
#绑定指令
bind_cmd = on_regex(r"^绑定\s+([a-zA-Z0-9]+-[a-zA-Z0-9]+)$")

@bind_cmd.handle()
async def handle_bind(bot: Bot, event: MessageEvent, args: tuple = RegexGroup()):
    full_code = args[0]
    server_id, code = parse_code(full_code)
    user_qq = str(event.get_user_id())

    if not server_id:
        await bind_cmd.finish("验证码格式错误！请确保使用完整的验证码（如 sv1-a1b2c3）")

    rcon_cfg = get_rcon_config(server_id)
    if not rcon_cfg:
        await bind_cmd.finish(f"未知的服务器: {server_id}")

    reply_msg = ""
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()

    try:
        # 检查该QQ在此服务器上是否已绑定
        c.execute("SELECT game_name FROM binds WHERE qq_id=? AND server_id=?", (user_qq, server_id))
        existing = c.fetchone()
        if existing:
            await bind_cmd.finish(f"你已经在此服务器绑定过账号 {existing[0]} 了，无法重复绑定！")

        with MCRcon(rcon_cfg["host"], rcon_cfg["password"], port=int(rcon_cfg["port"])) as mcr:
            resp = mcr.command(f"qadmin verify {full_code}")
            clean_resp = resp.strip()

            if "SUCCESS:" in clean_resp:
                game_name = clean_resp.split(":")[1]
                # 检查该游戏账号在此服务器上是否已被其他QQ绑定
                c.execute("SELECT qq_id FROM binds WHERE game_name=? AND server_id=?", (game_name, server_id))
                if c.fetchone():
                    reply_msg = f"错误：游戏账号 {game_name} 已经被其他QQ绑定了！"
                else:
                    c.execute("INSERT INTO binds (qq_id, server_id, game_name) VALUES (?, ?, ?)", (user_qq, server_id, game_name))
                    conn.commit()
                    server_name = rcon_cfg.get("name", server_id)
                    reply_msg = f"绑定成功！\n游戏ID: {game_name}\nQQ: {user_qq}\n服务器: {server_name}\n祝游戏愉快！"

            elif "FAIL:InvalidCode" in clean_resp:
                reply_msg = "验证码错误或已过期！请在游戏内重新输入 /link 获取。"
            elif "FAIL:PlayerOffline" in clean_resp:
                reply_msg = "玩家不在线！请保持游戏在线状态再进行绑定。"
            else:
                reply_msg = f"服务器返回了未知错误: {clean_resp}"

    except FinishedException:
        raise
    except Exception as e:
        reply_msg = f"连接服务器失败: {e}"
    finally:
        conn.close()

    if reply_msg:
        await bind_cmd.finish(reply_msg)

#mc查询
query_cmd = on_command("mc查询", aliases={"查绑定"}, priority=5)
@query_cmd.handle()
async def handle_query(bot: Bot, event: MessageEvent, args: Message = CommandArg()):
    target_name = args.extract_plain_text().strip()

    if not target_name:
        await query_cmd.finish("请输入要查询的游戏名，例如：/mc查询 Steve")

    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()
    c.execute("SELECT qq_id, server_id FROM binds WHERE game_name=?", (target_name,))
    rows = c.fetchall()
    conn.close()

    if rows:
        target_qq = rows[0][0]
        servers = [RCON_SERVERS.get(r[1], {}).get("name", r[1]) for r in rows]
        await query_cmd.finish(Message(f"游戏ID: {target_name}\n绑定QQ: ") + MessageSegment.at(target_qq) + Message(f"\n服务器: {', '.join(servers)}"))
    else:
        await query_cmd.finish(f"未查询到玩家 {target_name} 的绑定记录。")

#更改信息
change_cmd = on_command("更改mc信息", aliases={"强制绑定"}, permission=SUPERUSER, priority=1)
@change_cmd.handle()
async def handle_change(bot: Bot, event: MessageEvent):
    target_qq = None
    for segment in event.message:
        if segment.type == "at":
            target_qq = str(segment.data["qq"])
            break

    raw_text = event.get_plaintext().strip()
    parts = raw_text.split()
    server_id = None
    game_name = None
    for part in parts:
        if "更改mc信息" in part or "强制绑定" in part:
            continue
        if server_id is None:
            server_id = part
        else:
            game_name = part
            break

    if not target_qq:
        await change_cmd.finish("请在指令中 @ 你要绑定的那个人！")
    if not server_id or not game_name:
        await change_cmd.finish("请输入服务器ID和游戏ID！\n格式: /更改mc信息 <服务器ID> <游戏ID> @某人")

    rcon_cfg = get_rcon_config(server_id)
    if not rcon_cfg:
        await change_cmd.finish(f"未知的服务器: {server_id}\n使用 /服务器列表 查看可用服务器")

    reply_msg = ""
    conn = sqlite3.connect(DB_FILE)
    c = conn.cursor()

    try:
        # 只删除该服务器上的绑定记录
        c.execute("DELETE FROM binds WHERE qq_id=? AND server_id=?", (target_qq, server_id))
        c.execute("DELETE FROM binds WHERE game_name=? AND server_id=?", (game_name, server_id))
        c.execute("INSERT INTO binds (qq_id, server_id, game_name) VALUES (?, ?, ?)", (target_qq, server_id, game_name))
        conn.commit()

        rcon_info = ""
        try:
            with MCRcon(rcon_cfg["host"], rcon_cfg["password"], port=int(rcon_cfg["port"])) as mcr:
                resp = mcr.command(f"qadmin unlock {game_name}")
                if "SUCCESS" in resp:
                    rcon_info = "\n玩家在线，已同步解锁！"
                else:
                    rcon_info = "\n玩家不在线，数据已更新。"
        except Exception as e_rcon:
            rcon_info = f"\nRCON连接失败: {e_rcon}"

        server_name = rcon_cfg.get("name", server_id)
        reply_msg = Message(f"强制绑定执行完毕。\nID: {game_name}\n服务器: {server_name}\nQQ: ") + MessageSegment.at(target_qq) + Message(rcon_info)

    except FinishedException:
        raise
    except Exception as e:
        reply_msg = f"数据库操作失败: {e}"
    finally:
        conn.close()

    await change_cmd.finish(reply_msg)

#服务器列表
server_list_cmd = on_command("服务器列表", priority=5)
@server_list_cmd.handle()
async def handle_server_list(bot: Bot, event: MessageEvent):
    if not RCON_SERVERS:
        await server_list_cmd.finish("未配置任何服务器！")

    lines = ["已配置的服务器列表:"]
    for sid, cfg in RCON_SERVERS.items():
        name = cfg.get("name", sid)
        lines.append(f"  {sid}: {name}")
    await server_list_cmd.finish("\n".join(lines))


# ==================== 双向聊天功能 ====================

# /chat 命令 - 发送消息到 MC 服务器
chat_cmd = on_command("chat", priority=5)

@chat_cmd.handle()
async def handle_chat(bot: Bot, event: MessageEvent, args: Message = CommandArg()):
    if not CHAT_GROUP_ID:
        await chat_cmd.finish("聊天功能未配置！")

    if isinstance(event, GroupMessageEvent) and str(event.group_id) != str(CHAT_GROUP_ID):
        return

    text = args.extract_plain_text().strip()
    if not text:
        await chat_cmd.finish("用法: /chat [服务器ID] <消息>\n例如: /chat sv1 你好")

    parts = text.split(maxsplit=1)
    if len(parts) == 2 and parts[0] in RCON_SERVERS:
        server_id = parts[0]
        message = parts[1]
    elif len(parts) >= 1 and RCON_SERVERS:
        server_id = list(RCON_SERVERS.keys())[0]
        message = text
    else:
        await chat_cmd.finish("未配置服务器或消息为空！")
        return

    rcon_cfg = get_rcon_config(server_id)
    if not rcon_cfg:
        await chat_cmd.finish(f"未知的服务器: {server_id}")

    try:
        sender_name = event.sender.card or event.sender.nickname or str(event.user_id)
        with MCRcon(rcon_cfg["host"], rcon_cfg["password"], port=int(rcon_cfg["port"])) as mcr:
            # 使用 tellraw 避免 [Server] 前缀
            tellraw_json = f'{{"text":"§b[QQ] §f{sender_name}: §7{message}"}}'
            mcr.command(f'tellraw @a {tellraw_json}')
        await chat_cmd.finish(f"消息已发送到 {rcon_cfg.get('name', server_id)}")
    except FinishedException:
        raise
    except Exception as e:
        await chat_cmd.finish(f"发送失败: {e}")


# WebSocket 客户端 - 接收 MC 消息并转发到 QQ 群
async def ws_client(server_id: str, ws_url: str):
    """连接到 MC 服务器的 WebSocket 并转发消息到 QQ 群"""
    import websockets

    while True:
        try:
            async with websockets.connect(ws_url) as ws:
                print(f"[QAuth] WebSocket 已连接到 {server_id}: {ws_url}")
                async for message in ws:
                    try:
                        data = json.loads(message)
                        if data.get("type") == "chat" and CHAT_GROUP_ID:
                            player = data.get("player", "???")
                            msg = data.get("message", "")
                            srv_id = data.get("server_id", server_id)
                            srv_name = RCON_SERVERS.get(srv_id, {}).get("name", srv_id)

                            bot = get_bot()
                            await bot.send_group_msg(
                                group_id=int(CHAT_GROUP_ID),
                                message=f"[{srv_name}] {player}: {msg}"
                            )
                    except Exception as e:
                        print(f"[QAuth] 处理 WebSocket 消息出错: {e}")
        except Exception as e:
            print(f"[QAuth] WebSocket 连接失败 ({server_id}): {e}")
            await asyncio.sleep(5)  # 5秒后重连


# 启动 WebSocket 客户端
driver = get_driver()

@driver.on_startup
async def start_ws_clients():
    """机器人启动时连接到所有配置的 MC WebSocket 服务器"""
    if not WS_SERVERS or not CHAT_GROUP_ID:
        return

    for server_id, ws_url in WS_SERVERS.items():
        asyncio.create_task(ws_client(server_id, ws_url))