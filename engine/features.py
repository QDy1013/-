# -*- coding: utf-8 -*-
"""第一级手工特征 + 第二级文本特征（方案书 6.2 / 6.3）

方案书里只写了「8 维手工特征」，没有逐项列出。这里把它定死，训练和端侧用同一套定义：

    0 at_me             @我 / 含我的姓名或别名
    1 at_all            @所有人 / @全体成员
    2 has_time          含时间表达（可解析或近似）
    3 has_action        含行动词（交/提交/填/签到/报名/接龙/回复/领取…）
    4 has_link_file     含链接、文件、[文件]/[图片] 占位
    5 sender_notifier   发送者是否本群高频通知者（历史统计）
    6 has_identity      含身份指代（学号/班级/小组/"选 X 课的同学"/课程名）
    7 reply_burst       回复潮 b：之后 10 分钟内同群确认类回复条数（log1p）

文本特征用字符 2-gram，导出为 {gram: weight}，端侧用哈希表查表即可复算，
不需要在手机上跑分词器。
"""
from __future__ import annotations

import math
import re
from collections import Counter, defaultdict
from datetime import datetime, timedelta

ME_NAME = "李承宇"
ME_ALIASES = ("承宇", "小李", "李同学")
ME_SID = "20230137"

ACTION_WORDS = ("交", "提交", "填", "签到", "报名", "接龙", "回复", "领取", "打卡",
                "上传", "传", "下载", "参加", "准备", "完成", "补交", "缴费", "选课")
TIME_HINTS = ("前", "点", "分", "今天", "明天", "后天", "今晚", "明晚", "周", "星期",
              "月", "日", "截止", "之前", "上课")
LINK_FILE = ("http", "www.", ".com", "[文件]", "[图片]", "[链接]", ".pdf", ".docx", ".zip")
IDENTITY = ("学号", "班级", "班", "组", "选课", "选修", "同学", "课代表", "班委",
            "数据库", "计算机网络", "操作系统", "数据结构", "软件工程", "概率论")
ACKS = {"收到", "好的", "1", "+1", "收到收到", "好嘞", "ok", "OK", "明白", "嗯嗯", "好"}

BURST_WINDOW_MIN = 10
NOTIFIER_MIN_HISTORY = 3
NOTIFIER_RATIO = 0.30

RE_AT_ALL = re.compile(r"@\s*所有人|@\s*全体|\[有全体消息\]")
RE_URL = re.compile(r"https?://|www\.")


def _hits(text: str, words) -> int:
    return 1 if any(w in text for w in words) else 0


def is_noise(text: str) -> bool:
    """一级：纯噪声（表情、收到、1、+1、哈哈哈、撤回提示）。"""
    t = text.strip()
    if not t:
        return True
    core = re.sub(r"[\s\W_]+", "", t)
    if not core:
        return True
    if core in ACKS:
        return True
    if re.fullmatch(r"[0-9+\-]+", core):
        return True
    if re.fullmatch(r"(哈|h|H|2|3|笑|2333)+", core):
        return True
    if "撤回了一条消息" in t or "撤回一条消息" in t:
        return True
    if len(core) <= 2 and core in {"？", "?", "。", "…", "额", "哦", "嗯", "啊"}:
        return True
    return False


class Stats:
    """发送者历史统计 + 回复潮，全部按时间顺序在线更新，训练和推理用同一份逻辑。"""

    def __init__(self) -> None:
        self.sender_total: Counter = Counter()
        self.sender_notify: Counter = Counter()
        self._pending: dict[str, list[tuple[datetime, str]]] = defaultdict(list)

    def observe(self, group_id: str, sender: str, ts: datetime, text: str, label: str | None = None) -> None:
        self.sender_total[(group_id, sender)] += 1
        if label in ("action", "notice"):
            self.sender_notify[(group_id, sender)] += 1
        self._pending[group_id].append((ts, text))

    def notifier_score(self, group_id: str, sender: str) -> float:
        tot = self.sender_total.get((group_id, sender), 0)
        if tot < NOTIFIER_MIN_HISTORY:
            return 0.0
        return 1.0 if self.sender_notify.get((group_id, sender), 0) / tot >= NOTIFIER_RATIO else 0.0

    def burst(self, group_id: str, ts: datetime) -> int:
        """消息 ts 之后 10 分钟内，同群别人发的确认类回复条数。"""
        n = 0
        for t, txt in self._pending.get(group_id, ()):
            if t <= ts:
                continue
            if t - ts > timedelta(minutes=BURST_WINDOW_MIN):
                break
            if txt.strip() in ACKS:
                n += 1
        return n


def manual_features(text: str, sender: str, stats: Stats | None, group_id: str,
                    ts: datetime, course: str | None, allow_burst: bool = True) -> dict[str, float]:
    t = text
    f = {}
    f["at_me"] = 1.0 if ("@" + ME_NAME in t or any(a in t for a in ME_ALIASES) or "@我" in t) else 0.0
    f["at_all"] = 1.0 if RE_AT_ALL.search(t) else 0.0
    f["has_time"] = 1.0 if _hits(t, TIME_HINTS) else 0.0
    f["has_action"] = 1.0 if _hits(t, ACTION_WORDS) else 0.0
    f["has_link_file"] = 1.0 if (_hits(t, LINK_FILE) or RE_URL.search(t)) else 0.0
    f["sender_notifier"] = stats.notifier_score(group_id, sender) if stats else 0.0
    ident = _hits(t, IDENTITY)
    if course and course in t:
        ident = 1
    f["has_identity"] = float(ident)
    # 线上做判断的那一刻，未来的"收到"还没出现，所以训练和推理都按 0 处理；
    # 回复潮由 pipeline 的重打分机制单独兑现，不作为静态特征。
    b = stats.burst(group_id, ts) if (stats and allow_burst) else 0
    f["reply_burst"] = math.log1p(b) / 3.0
    return f


def text_features(text: str) -> list[str]:
    """字符 2-gram，去掉空白和标点。端侧同一份实现。"""
    s = re.sub(r"[\s\W_]+", "", text)
    if len(s) < 2:
        return list(s)
    return [s[i:i + 2] for i in range(len(s) - 1)]


MANUAL_KEYS = ["at_me", "at_all", "has_time", "has_action", "has_link_file",
               "sender_notifier", "has_identity", "reply_burst"]


def vectorize(text: str, sender: str, stats: Stats | None, group_id: str,
              ts: datetime, course: str | None, allow_burst: bool = True) -> dict[str, float]:
    v = {("M:" + k): val for k, val in
         manual_features(text, sender, stats, group_id, ts, course, allow_burst).items()}
    for g in set(text_features(text)):
        v["T:" + g] = 1.0
    # 手工特征与文本特征的交互项（让线性模型能学到"有时间 + 有行动词"这种组合）
    mf = {k: v.get("M:" + k, 0.0) for k in MANUAL_KEYS}
    if mf["has_time"] and mf["has_action"]:
        v["X:time_action"] = 1.0
    if mf["has_time"] and mf["has_action"] and mf["sender_notifier"]:
        v["X:time_action_notifier"] = 1.0
    if mf["at_me"] and mf["has_action"]:
        v["X:atme_action"] = 1.0
    if mf["reply_burst"] > 0.5 and mf["has_time"]:
        v["X:burst_time"] = 1.0
    return v
