# -*- coding: utf-8 -*-
"""第三级：结构化抽取（方案书 6.4「抽取而非生成」）

原则：每个字段都必须能在原文里找到依据；找不到就留空并标「待确认」，不猜、不补。
输出是固定字段，不是一段话。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime

from .timeparse import TimeParse, parse_deadline, parse_jielong_deadline

CHANNELS = ("学习通", "雨课堂", "课程网站", "QQ 群文件", "邮箱", "教务系统", "课代表处",
            "微信转账", "班级接龙", "群接龙", "腾讯会议", "问卷", "飞书", "钉钉")
PLACE_RE = re.compile(r"(三教|二教|一教|四教|实验楼|图书馆|体育馆|报告厅|线上|腾讯会议)"
                      r"[^，。；\n]{0,8}")
RE_SPLIT = re.compile(r"[，。；;\n！!？?]")
VERBS = ("交", "提交", "上传", "传", "填", "报名", "签到", "接龙", "回复", "领取", "打卡",
         "参加", "完成", "补交", "缴费", "选课", "下载")

# ---- 纯收件人子句（"交给组长"这类只有动作 + 收件人的子句）----
TITLE_NOUNS = ("作业", "报告", "材料", "申请表", "登记表", "论文", "习题")
RECIPIENTS = ("课代表", "助教", "辅导员", "班主任", "负责人", "组长", "学委", "班长", "老师", "导员")
RE_DELIVERY_ONLY = re.compile(
    r"^(?:请|麻烦|大家|同学们|各位同学|各位|记得|务必|注意|统一|直接|按时|及时|都|先|再)?\s*"
    r"(?:提交|上交|上缴|转交|交|发)\s*(?:给|到|于)\s*"
    r"(?:各|本组|我们组|你们组)?\s*"
    r"(?:" + "|".join(RECIPIENTS) + r")"
    r"(?:老师|同学|那里|那儿|处)?\s*[。！!，,；;.\s]*$")
# 收件人提交渠道：交给组长 / 提交给课代表 / 上交给助教
RE_DELIVERY_CHANNEL = re.compile(
    r"(?:交给|交到|提交给|提交到|上交给|上交到|上缴给|转交给|交|提交|上交|上缴|发给)\s*"
    r"(?:各|本组|我们组|你们组)?\s*"
    r"(" + "|".join(RECIPIENTS) + r")")
# 标题后面跟着的时间要求，从"请于/须于/需在/务必在"等处截断
_TIME_AHEAD = (r"(?:\d{1,2}\s*[月./]|下?周[一二三四五六日天]|星期[一二三四五六日天]"
               r"|今天|明天|后天|今晚|明晚|今早|明早|本(?:周|月|学期)|这(?:周|月|学期)"
               r"|月底|月末|周末|\d{1,2}\s*[点时]|[上下]午|晚上|中午|早上)")
RE_DELIVERY_CUT = re.compile(
    r"(?:请务必|务必|请于|须于|需在|需要在|须在|必须于|必须|请|须|需|截止到|截止|限"
    r"|(?<![关对由属至])于)\s*"
    r"(?:在|于|到)?\s*(?=" + _TIME_AHEAD + r")")
# 句首的 📢 类符号（不影响中文/括号）
RE_LEAD_SYMBOL = re.compile(
    r"^[\s\u200b\u200c\u200d\ufe0e\ufe0f\u2049\u203c\u2600-\u27bf\u2b00-\u2bff"
    r"\U0001F000-\U0001FAFF]+")


def is_delivery_only(text: str) -> bool:
    """判断子句是否只是"交给组长"这类纯收件人提交短语。

    "交给组长的实验报告"带有事项中心词，不算纯收件人子句。
    """
    return bool(RE_DELIVERY_ONLY.match(clean_title(text)))


def _title_from_clause(clause: str) -> str:
    """从子句里取事项标题：去掉开头符号，截断后续时间要求，保留序号和章节括号。"""
    s = clause.strip()
    if s.startswith("[有全体消息]"):
        s = s[len("[有全体消息]"):].strip()
    s = RE_LEAD_SYMBOL.sub("", s)
    m = RE_DELIVERY_CUT.search(s)
    if m:
        s = s[:m.start()]
    s = clean_title(s)
    s = re.sub(r"[（(]\s*[）)]", "", s)
    s = re.sub(r"^[，,、；;：:\s]+|[，,、；;：:\s]+$", "", s)
    return s.strip()

# ---- 标题清理用的公共正则（find_action 与 pipeline 的兜底标题共用）----
# 时段词集合里补了单字"早""晚"，但它们必须紧跟数字 + 点/时才删，
# 所以整段写成「时段词? 数字 点/时」，不会误删"早自习"。
_TITLE_PERIOD = (r"(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里"
                 r"|今晚|明晚|今早|明早|早|晚)?")
RE_TITLE_HOUR = re.compile(
    _TITLE_PERIOD + r"\s*(\d{1,2}|[一二三四五六七八九十]{1,3})\s*[点时]\s*"
    r"(?:半|\d{0,2}\s*分?)\s*(?:之前|以前|前|截止)?")
RE_TITLE_CLOCK = re.compile(
    _TITLE_PERIOD + r"\s*(\d{1,2})\s*[:：]\s*(\d{2})\s*(?:之前|以前|前|截止)?")
# "本周内/这周内/下周"后面的"内"和"前/之前"一样整段删掉
# "这个周末前/这周末/本周末"整段删（必须在 RE_TITLE_WEEK 之前，否则只会删掉"本周"留下"末前"）
RE_TITLE_WEEKEND = re.compile(r"(?:这个|这|本|下个|下)?\s*周末\s*(?:之前|以前|前|截止|内)?")
RE_TITLE_WEEK = re.compile(
    r"(下|本|这)(周|星期|礼拜)[一二三四五六日天]?\s*(?:之前|以前|前|截止|内)?")
# "本月底/月底/月末/本月内/月内"（含可选"前/之前"）
RE_TITLE_MONTH = re.compile(r"(?:这|本|下)?\s*个?\s*月\s*(?:底|末|内)\s*(?:之前|以前|前)?")
RE_TITLE_DAY = re.compile(
    r"(?:今天|明天|后天|明晚|今晚|大后天|本周|这周|下周|周[一二三四五六日天]"
    r"|星期[一二三四五六日天]|\d{1,2}\s*[月.]\s*\d{1,2}\s*[日号]?|月底|周末)\s*"
    r"(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)?\s*(?:之前|以前|前|截止)?")
# 句首孤立的时段词（如"晚上交作业"）。单字"早/晚"不放进来，避免删掉"早自习"。
RE_TITLE_LEAD_PERIOD = re.compile(
    r"^(?:凌晨|清晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里)\s*(?:之前|以前|前|截止)?")
RE_TITLE_PREFIX = re.compile(
    r"^((?:请务必|请|大家|同学们|各位同学|各位|全体同学|全体|同学|记得|务必|注意|于)\s*)+")


def clean_title(clause: str) -> str:
    """去掉子句里的时间表达、称呼前缀、渠道后缀，供 find_action / 兜底标题共用。"""
    s = clause
    if s.startswith("[有全体消息]"):
        s = s[len("[有全体消息]"):].strip()
    # 时段词 + 钟点（含"半"）+ 可选后缀"之前/以前/前/截止"，整段一起删
    s = RE_TITLE_HOUR.sub("", s)
    # hh:mm 前面同样可能带时段词，后面同样可能带"之前/前/截止"
    s = RE_TITLE_CLOCK.sub("", s)
    s = RE_TITLE_WEEKEND.sub("", s)
    s = RE_TITLE_WEEK.sub("", s)
    s = RE_TITLE_MONTH.sub("", s)
    s = RE_TITLE_DAY.sub("", s)
    s = RE_TITLE_LEAD_PERIOD.sub("", s)
    s = re.sub(r"(上课前|放学前|上课之前)", "", s)
    s = re.sub(r"^[一二三四五六日天下本周这\s]+", "", s)
    # 去掉渠道后缀
    s = re.sub(r"(交|提交|上传|发)到[^，。；、]{0,12}", "", s)
    s = re.sub(r"[，,]?\s*(学习通|雨课堂|课程网站|QQ 群文件|邮箱|教务系统|课代表处|微信转账)[^，。；]{0,6}", "", s)
    s = RE_TITLE_PREFIX.sub("", s)
    s = re.sub(r"(啊|哈|呀|哦|啦|吧|喔|哟)+$", "", s)
    s = re.sub(r"[，,、]+$", "", s)
    return s.strip()


@dataclass
class Extracted:
    action: str | None = None
    deadline: datetime | None = None
    time_conf: str = "none"
    time_note: str = ""
    channel: str | None = None
    place: str | None = None
    needs_confirm: bool = False
    reasons: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "action": self.action,
            "deadline": self.deadline.isoformat(timespec="seconds") if self.deadline else None,
            "time_conf": self.time_conf,
            "channel": self.channel,
            "place": self.place,
            "needs_confirm": self.needs_confirm,
            "reasons": self.reasons,
        }


def find_channel(text: str) -> str | None:
    for c in CHANNELS:
        if c in text:
            return c
    m = RE_DELIVERY_CHANNEL.search(text)
    if m:
        return m.group(1)
    m = re.search(r"(?:交|提交|上传|发)到\s*([^\s，。；、]{2,8})", text)
    if m:
        return m.group(1)
    m = re.search(r"在\s*([^\s，。；、]{2,8})\s*(?:里|上|中)?\s*(?:操作|提交|报名|填|交)", text)
    if m:
        return m.group(1)
    return None


def find_place(text: str) -> str | None:
    m = PLACE_RE.search(text)
    return m.group(0).strip() if m else None


def find_action(text: str) -> tuple[str | None, str | None]:
    """返回 (事项短语, 命中的行动词)。

    做法：按标点切子句，挑第一个含行动词且不像闲聊的子句，用 clean_title 去掉
    时间表达、称呼前缀和渠道后缀。清理后太短时，退到第一个非空子句（它通常
    是"作业：…习题"这种事项名，时间在后面的子句里）。
    """
    clauses = [c.strip() for c in RE_SPLIT.split(text) if c.strip()]
    best, best_i, verb = None, -1, None
    for i, clause in enumerate(clauses):
        # 排除"我明天交"这类第一人称陈述（是讨论，不是给你的通知）
        if re.match(r"^(我|俺|咱)们?", clause):
            continue
        v = next((v for v in VERBS if v in clause), None)
        if not v:
            continue
        best, best_i, verb = clause, i, v
        break
    if best is None:
        return None, None

    # 选中"交给组长"这类纯收件人子句：回头找带事项中心词的前句当标题
    if is_delivery_only(best):
        for clause in reversed(clauses[:best_i]):
            title = _title_from_clause(clause)
            if title and any(n in title for n in TITLE_NOUNS):
                return title, verb
        return None, verb

    s = clean_title(best)
    if len(s) < 3:
        # 前一个子句（第一个非空子句，且不是被选中的那个）同样清理后当标题
        prev = clean_title(clauses[0]) if best_i > 0 else ""
        if prev:
            return prev, verb
        # 没有可用前句：沿用旧办法（<2 才退回子句原文）
        if len(s) < 2:
            return best, verb
    return s, verb


def extract(text: str, send: datetime, is_jielong: bool = False) -> Extracted:
    tp: TimeParse = (parse_jielong_deadline if is_jielong else parse_deadline)(text, send)
    action, verb = find_action(text)
    channel = find_channel(text)
    place = find_place(text)

    e = Extracted(
        action=action,
        deadline=tp.value,
        time_conf=tp.confidence,
        time_note=tp.note,
        channel=channel,
        place=place,
    )
    if tp.value is None:
        e.needs_confirm = True
        e.reasons.append("没有可解析的截止时间")
    elif tp.confidence in ("low",):
        e.needs_confirm = True
        e.reasons.append("时间置信度低：" + (tp.note or "已过期或表达含糊"))
    if action is None:
        e.needs_confirm = True
        e.reasons.append("没找到明确的事项")
    if channel is None and place is None:
        e.reasons.append("没写在哪交/在哪办")
    return e


def human_deadline(dt: datetime | None, now: datetime | None = None) -> str:
    if dt is None:
        return "待确认"
    now = now or datetime.now()
    delta = (dt.date() - now.date()).days
    wd = "一二三四五六日"[dt.weekday()]
    hm = dt.strftime("%H:%M")
    if delta == 0:
        return f"今天 {hm}"
    if delta == 1:
        return f"明天 {hm}"
    if delta == 2:
        return f"后天 {hm}"
    if 3 <= delta <= 6:
        return f"周{wd} {hm}"
    return f"{dt.month} 月 {dt.day} 日 {hm}"
