# -*- coding: utf-8 -*-
"""接龙检测（方案书 7.2）

微信接龙有固定形状：`#接龙` + 标题 + 编号名单。这让"我接没接"变成可判断的事实，
而不是靠语义猜。名单里出现我的姓名/别名/学号 → 已接；否则提醒。
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .timeparse import parse_jielong_deadline
from datetime import datetime

RE_NUM_LINE = re.compile(r"^\s*(\d{1,3})\s*[.、)]\s*(.+?)\s*$", re.M)
RE_TITLE = re.compile(r"[（(]([^）)]*)[）)]")
ME_NAMES = ("李承宇", "承宇", "小李", "李同学")
ME_SID = "20230137"


@dataclass
class Jielong:
    is_jielong: bool
    title: str | None = None
    roster: list[str] | None = None
    joined: bool | None = None
    deadline: datetime | None = None
    deadline_note: str = ""
    note: str = ""


def is_jielong(text: str) -> bool:
    stripped = text.strip()
    if stripped.startswith("#接龙"):
        return True
    if "#接龙" in stripped[:20]:
        return True
    nums = RE_NUM_LINE.findall(stripped)
    if len(nums) >= 3:
        idx = [int(n) for n, _ in nums]
        if idx == list(range(1, len(idx) + 1)):   # 连续编号
            return True
    return False


def parse(text: str, send: datetime) -> Jielong:
    if not is_jielong(text):
        return Jielong(False)

    lines = [l for l in text.splitlines() if l.strip()]
    title = None
    for line in lines:
        if line.strip().startswith("#接龙"):
            continue
        m = RE_TITLE.search(line)
        title = line[:m.start()].strip() if m else line.strip()
        break
    if not title:
        title = "未命名接龙"

    roster: list[str] = []
    for num, body in RE_NUM_LINE.findall(text):
        name = re.split(r"[\s（(：:]", body.strip())[0]
        if name:
            roster.append(name)

    joined = any(n in roster for n in ME_NAMES) or any(ME_SID in r for r in roster)
    tp = parse_jielong_deadline(text, send)
    return Jielong(
        is_jielong=True,
        title=title,
        roster=roster,
        joined=joined,
        deadline=tp.value,
        deadline_note=tp.note,
        note=f"{len(roster)} 人已接" + ("，含你" if joined else "，你没有"),
    )


def reminder_card(j: Jielong, sender: str) -> dict | None:
    """你没接，才生成提醒卡片。"""
    if not j.is_jielong or j.joined:
        return None
    return {
        "kind": "jielong",
        "title": j.title,
        "summary": f"{sender} 发起的《{j.title}》已有 {len(j.roster or [])} 人参加，你还没接。",
        "deadline": j.deadline.isoformat(timespec="seconds") if j.deadline else None,
        "needs_confirm": j.deadline is None,
    }
