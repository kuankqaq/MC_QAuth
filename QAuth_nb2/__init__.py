import sqlite3
import json
from nonebot import on_regex, on_command, get_driver
from nonebot.adapters.onebot.v11 import Bot, MessageEvent, MessageSegment, Message
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
    c.execute('''CREATE TABLE IF NOT EXISTS binds
                 (qq_id TEXT PRIMARY KEY, game_name TEXT UNIQUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')
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
        c.execute("SELECT game_name FROM binds WHERE qq_id=?", (user_qq,))
        if c.fetchone():
            await bind_cmd.finish("你已经绑定过账号了，无法重复绑定！")

        with MCRcon(rcon_cfg["host"], rcon_cfg["password"], port=int(rcon_cfg["port"])) as mcr:
            resp = mcr.command(f"qadmin verify {full_code}")
            clean_resp = resp.strip()

            if "SUCCESS:" in clean_resp:
                game_name = clean_resp.split(":")[1]
                c.execute("SELECT qq_id FROM binds WHERE game_name=?", (game_name,))
                if c.fetchone():
                    reply_msg = f"错误：游戏账号 {game_name} 已经被其他QQ绑定了！"
                else:
                    c.execute("INSERT INTO binds (qq_id, game_name) VALUES (?, ?)", (user_qq, game_name))
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
    c.execute("SELECT qq_id FROM binds WHERE game_name=?", (target_name,))
    row = c.fetchone()
    conn.close()

    if row:
        target_qq = row[0]
        await query_cmd.finish(Message(f"游戏ID: {target_name}\n绑定QQ: ") + MessageSegment.at(target_qq))
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
        c.execute("DELETE FROM binds WHERE qq_id=?", (target_qq,))
        c.execute("DELETE FROM binds WHERE game_name=?", (game_name,))
        c.execute("INSERT INTO binds (qq_id, game_name) VALUES (?, ?)", (target_qq, game_name))
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