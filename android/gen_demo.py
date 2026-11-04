# -*- coding: utf-8 -*-
"""生成演示脚本（方案书第 10 节「现场脚本」）

产物：android/replay/assets/messages.txt
每行 group|sender|text，回放器按顺序发成通知。
时间表达一律用相对说法（明晚/周五/下周三），这样任何一天演示都成立。
"""
from __future__ import annotations

import random
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android" / "replay" / "assets" / "messages.txt"

rng = random.Random(20261103)

G_DB = "数据库系统 2026秋"
G_CLASS = "计科 2301 班群"
G_PROJ = "挑战杯·小组群"
G_NET = "计算机网络 2026秋"
GROUPS = [G_DB, G_CLASS, G_PROJ, G_NET]

CHATTER = ["哈哈哈", "笑死", "收到", "1", "+1", "好的", "嗯嗯", "有人去食堂吗",
           "我先去趟快递", "好家伙", "绝了", "蹲一个", "刚睡醒", "今晚有人打球吗",
           "😂", "😅", "👍", "[图片]", "这也太真实了", "救命", "？", "在的",
           "我看了下第三章的题有点难", "你们打算做什么题目", "这门课给分怎么样啊",
           "咱们小组要不周三晚上对一下", "有人知道实验报告要写多少字吗",
           "求一份上学期的卷子", "我昨天就交了", "作业好多啊", "学分够了吗"]

MEMBERS = ["陈嘉禾", "林晓", "周若曦", "郑浩然", "孙语桐", "何思远", "马佳怡", "韩雨轩"]

lines: list[str] = []


def add(group: str, sender: str, text: str) -> None:
    text = text.replace("\n", "\\n")
    lines.append(f"{group}|{sender}|{text}")


def burst(group: str, sender_pool: list[str], n: int) -> None:
    for _ in range(n):
        add(group, rng.choice(sender_pool), rng.choice(CHATTER))


# ---- 0:00 现场：4 个群、200 多条未读，先铺满一屏让人感受"爬楼"
for _ in range(120):
    g = rng.choice(GROUPS)
    pool = MEMBERS if g != G_DB else MEMBERS + ["王老师"]
    add(g, rng.choice(pool), rng.choice(CHATTER))

# ---- 第一条：课程群作业通知，被 30 条"收到"和闲聊淹没
add(G_DB, "王老师", "明晚 8 点前交实验报告，交到学习通。")
burst(G_DB, MEMBERS, 30)
burst(G_CLASS, MEMBERS, 8)

# ---- 第二条：班级群接龙，我没有接
add(G_CLASS, "陈嘉禾", "#接龙\\n体测报名（周五18点截止）\\n1. 周若曦\\n2. 郑浩然\\n3. 孙语桐\\n4. 何思远\\n5. 马佳怡")
burst(G_CLASS, MEMBERS, 6)

# ---- 第三条：@我的组队消息
add(G_PROJ, "周若曦", "@李承宇 展示 PPT 你来做哈，周五 18 点前发群里")
burst(G_PROJ, ["郑浩然", "陈嘉禾"], 4)

# ---- 填充：让列表继续滚，制造"错过"的压力
burst(G_NET, MEMBERS, 12)
add(G_NET, "刘老师", "下周三上课前交读书报告，交到课程邮箱。")
burst(G_NET, MEMBERS, 10)

# ---- 2:00 现场：老师改截止时间，卡片应当场更新
burst(G_DB, MEMBERS, 14)
add(G_DB, "王老师", "更正一下，实验报告截止改到下周一 22 点，之前说的那个时间不作数。")
burst(G_DB, MEMBERS, 8)

# ---- 干扰项：不该变成待办的
add(G_CLASS, "郑浩然", "我明天把实验报告交了，你们呢")
add(G_CLASS, "孙语桐", "明天一起吃饭？")
add(G_CLASS, "何思远", "明天停课，不用来")
add(G_DB, "林晓", "有人知道周五考什么吗")
add(G_PROJ, "陈嘉禾", "作业好多啊")
burst(G_CLASS, MEMBERS, 20)

# ---- 收尾：再铺一点未读
for _ in range(30):
    add(rng.choice(GROUPS), rng.choice(MEMBERS), rng.choice(CHATTER))

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text("\n".join(lines), encoding="utf-8")
print(f"演示脚本 {len(lines)} 条 → {OUT}")
print("其中的待办线索：")
for l in lines:
    if any(k in l for k in ("交实验报告", "接龙", "PPT", "读书报告", "更正")):
        print("  ·", l[:80].replace("\\n", " "))
