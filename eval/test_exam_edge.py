# -*- coding: utf-8 -*-
"""考试卡边界场景验收用例（Python 版）。

仿照 eval/test_exam_cards.py 的写法：辅助函数照抄进本文件，不 import 该模块。
只测对外行为：出不出卡、kind、bring / place 等字段。
每个场景用新的 Pipeline(model)，先 check 消息发出后的卡数，再 check 具体字段。
全程离线，时间戳固定；T0 = 2026-12-20 10:00。

只写测试，不改任何业务代码；新字段 bring / place 用 getattr 读取，
这样测试文件本身不依赖未落地的接口。
"""

import sys
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from engine.pipeline import Pipeline, Model  # noqa: E402
from engine.timeparse import parse_deadline  # noqa: E402

GROUP = "高等数学2026秋"
GID = "g-math"
TEACHER = "王老师"
STUDENT = "陈嘉禾"
ADVISOR = "张辅导员"

T0 = datetime(2026, 12, 20, 10, 0)

pass_count = 0
fail_count = 0


def check(name, ok, detail=""):
    global pass_count, fail_count
    if ok:
        pass_count += 1
        print("  ✓ " + name)
    else:
        fail_count += 1
        print("  ✗ " + name + "   " + detail)


class MsgSeq(object):
    """与 test_revoke_rules 一致：跨场景连续编号，保证 msg_id 唯一。"""

    def __init__(self):
        self.n = 0

    def msg(self, gid, gname, sender, text, ts):
        self.n += 1
        return {
            "msg_id": "m%d" % self.n,
            "group_id": gid,
            "group_name": gname,
            "sender": sender,
            "ts": ts,
            "text": text,
        }


def cards_of(p):
    """未撤销的卡，对应规则里的“活动卡”。"""
    return p.active_cards()


def exam_cards(p):
    return [c for c in p.active_cards() if c.kind == "exam"]


def expected_deadline(text, ts):
    """期望时间不手算：用引擎自己的时间解析函数解析同一句文字。"""
    return parse_deadline(text, ts).value


def same_deadline(actual, expected):
    """容忍 naive/aware 表示的差异，按本地墙上时间比较。"""
    if actual is None or expected is None:
        return actual is expected
    if actual == expected:
        return True
    try:
        return actual.strftime("%Y-%m-%d %H:%M") == expected.strftime("%Y-%m-%d %H:%M")
    except (AttributeError, ValueError):
        return False


def bring_of(card):
    return getattr(card, "bring", None) or ""


def title_of(card):
    return getattr(card, "title", None) or ""


def place_of(card):
    return getattr(card, "place", None) or ""


def brief(cards):
    return "卡数=%d，类型=[%s]" % (len(cards), ",".join(c.kind for c in cards))


def main():
    model = Model(ROOT / "engine" / "model.json")
    seq = MsgSeq()

    # ---------- 场景 1：老师带 @所有人 与携带物品，测 bring 字段能抽出“学生证” ----------
    print("== 1. 老师发带携带物的期中考试通知 ==")
    p1 = Pipeline(model)
    t1 = "@所有人 下周三上午9点期中考试，请带上学生证和计算器"
    p1.process(seq.msg(GID, GROUP, TEACHER, t1, T0))
    c1 = cards_of(p1)
    check("1 消息发出后恰好 1 张卡", len(c1) == 1, brief(c1))
    check("1 恰好 1 张 exam 卡（kind 为 exam）",
          len(c1) == 1 and c1[0].kind == "exam", brief(c1))
    check("1 bring 含“学生证”", len(c1) == 1 and "学生证" in bring_of(c1[0]),
          "bring=" + (bring_of(c1[0]) if c1 else "无卡"))

    # ---------- 场景 2：老师给绝对日期 + 地点 + 闭卷，测 place 字段能抽出“教二” ----------
    print("\n== 2. 老师发带地点的期末考试通知 ==")
    p2 = Pipeline(model)
    t2 = "期末考试12月28日下午2点，教二305，闭卷"
    p2.process(seq.msg(GID, GROUP, TEACHER, t2, T0))
    c2 = cards_of(p2)
    check("2 消息发出后恰好 1 张卡", len(c2) == 1, brief(c2))
    check("2 恰好 1 张 exam 卡（kind 为 exam）",
          len(c2) == 1 and c2[0].kind == "exam", brief(c2))
    check("2 place 含“教二”", len(c2) == 1 and "教二" in place_of(c2[0]),
          "place=" + (place_of(c2[0]) if c2 else "无卡"))

    # ---------- 场景 3：发送者是“张辅导员”而非老师，测照样出 exam 卡 ----------
    print("\n== 3. 张辅导员发英语四级模拟考试 ==")
    p3 = Pipeline(model)
    t3 = "周四晚7点英语四级模拟考试在教一102"
    p3.process(seq.msg(GID, GROUP, ADVISOR, t3, T0))
    c3 = cards_of(p3)
    check("3 消息发出后恰好 1 张卡", len(c3) == 1, brief(c3))
    check("3 恰好 1 张 exam 卡（kind 为 exam）",
          len(c3) == 1 and c3[0].kind == "exam", brief(c3))

    # ---------- 场景 4：老师发“范围/看完”类消息，测不出 exam 卡；只检查这一点 ----------
    print("\n== 4. 老师发考试范围已发、周五前看完 ==")
    p4 = Pipeline(model)
    t4 = "期中考试范围已发在群文件，周五9点前看完"
    p4.process(seq.msg(GID, GROUP, TEACHER, t4, T0))
    c4 = cards_of(p4)
    check("4 消息发出后 exam 卡数为 0", len(exam_cards(p4)) == 0, brief(c4))
    check("4 没有 exam 卡（具体字段：所有卡 kind 均非 exam，只检查这一点）",
          all(c.kind != "exam" for c in c4), brief(c4))

    # ---------- 场景 5：泛泛提醒“考试周注意休息”，测任何卡都不出 ----------
    print("\n== 5. 老师发泛泛的考试周提醒 ==")
    p5 = Pipeline(model)
    t5 = "考试周大家注意休息"
    p5.process(seq.msg(GID, GROUP, TEACHER, t5, T0))
    c5 = cards_of(p5)
    check("5 消息发出后没有任何卡（卡数为 0）", len(c5) == 0, brief(c5))
    check("5 具体字段：没有 exam 卡也没有 todo 卡",
          len(exam_cards(p5)) == 0 and all(c.kind != "todo" for c in c5), brief(c5))

    print("\n通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
