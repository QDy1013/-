# -*- coding: utf-8 -*-
"""口语化提醒 / 疑问句 / 标题清理 / 考试与接龙卡的验收用例（Python 版）。

覆盖：
  A1 句中语气词"呢"不应被当成提问 → 仍出卡
  A2 句尾"呢"是在问 → 不出卡
  A3 句尾"吗"是在问 → 不出卡
  B1 extract.find_action 对"这个周末前/这周末前/本周末前"的标题清理
  C1 考试通知 → kind=exam、place、deadline
  D1 接龙 → kind=jielong、标题、deadline

每个场景用全新 Pipeline，只发这一条消息，发送时间固定 2026-11-04 21:30。
本文件只钉期望，不迁就现状：现在有若干条不通过是正常的。
"""

import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from engine.pipeline import Pipeline, Model  # noqa: E402
from engine.extract import find_action  # noqa: E402

GROUP_ID = "g1"
GROUP_NAME = "数据库群"
TEACHER = "王老师"
MONITOR = "李班长"
STUDENT = "陈嘉禾"

T0 = datetime(2026, 11, 4, 21, 30)

A1_TEXT = "那个，周五之前呢，把实验报告交一下哈"
A2_TEXT = "周五之前交实验报告呢"
A3_TEXT = "周五之前交实验报告吗"
C1_TEXT = "期末考试1月5日上午9点，教学楼A101，带笔和学生证"
D1_TEXT = "#接龙 周末聚餐，周五18点前报名\n1. 张三\n2. 李四"

D_FRI_2359 = datetime(2026, 11, 6, 23, 59)
D_FRI_1800 = datetime(2026, 11, 6, 18, 0)
D_EXAM_0900 = datetime(2027, 1, 5, 9, 0)

pass_count = 0
fail_count = 0


def check(name, ok, detail=""):
    global pass_count, fail_count
    if ok:
        pass_count += 1
        print("  ✓ " + name)
    else:
        fail_count += 1
        print("  ✗ " + name + "   " + str(detail))


def fmt(value):
    if value is None:
        return "None（待确认）"
    return value.strftime("%Y-%m-%d %H:%M")


def same_deadline(actual, expected):
    if actual is None or expected is None:
        return actual is expected
    return fmt(actual) == fmt(expected)


def describe(cards):
    parts = []
    for c in cards:
        parts.append("{%s|%s|%s|%s}" % (
            getattr(c, "kind", "?"),
            getattr(c, "title", "?"),
            fmt(getattr(c, "deadline", None)),
            getattr(c, "place", None),
        ))
    return "活动卡=%d %s" % (len(cards), " ".join(parts))


def new_pipeline():
    model = Model(ROOT / "engine" / "model.json")
    return Pipeline(model)


def send(p, sender, text, ts=T0, msg_id="m1"):
    p.process({
        "msg_id": msg_id,
        "group_id": GROUP_ID,
        "group_name": GROUP_NAME,
        "sender": sender,
        "ts": ts,
        "text": text,
    })
    return p.active_cards()


def title_of(card):
    return (getattr(card, "title", "") or "").strip()


def main():
    # A1 句中的"呢"是语气词，不是提问
    print("== A1 句中语气词：%s ==" % A1_TEXT)
    cards = send(new_pipeline(), TEACHER, A1_TEXT)
    check("A1 恰好 1 张活动卡", len(cards) == 1, describe(cards))
    if len(cards) == 1:
        check("A1 标题为「把实验报告交一下」",
              title_of(cards[0]) == "把实验报告交一下",
              "实际标题=%r" % title_of(cards[0]))
        check("A1 deadline 2026-11-06 23:59",
              same_deadline(cards[0].deadline, D_FRI_2359),
              "实际=%s" % fmt(cards[0].deadline))

    # A2 句尾"呢"是在问 → 不生成活动卡
    print("\n== A2 句尾疑问（呢）：%s ==" % A2_TEXT)
    cards = send(new_pipeline(), STUDENT, A2_TEXT, msg_id="m2")
    check("A2 0 张活动卡", len(cards) == 0, describe(cards))

    # A3 句尾"吗"是在问 → 不生成活动卡
    print("\n== A3 句尾疑问（吗）：%s ==" % A3_TEXT)
    cards = send(new_pipeline(), TEACHER, A3_TEXT, msg_id="m3")
    check("A3 0 张活动卡", len(cards) == 0, describe(cards))

    # B1 标题清理：周末前的时间前缀要去干净
    print("\n== B1 find_action 标题清理 ==")
    b1_cases = [
        ("这个周末前把问卷填了", "把问卷填了"),
        ("这周末前交报告", "交报告"),
        ("本周末前提交总结", "提交总结"),
    ]
    for text, want in b1_cases:
        got = find_action(text)[0]
        check("B1 %r → %r" % (text, want), got == want, "实际=%r" % (got,))

    # C1 考试通知
    print("\n== C1 考试通知：%s ==" % C1_TEXT)
    cards = send(new_pipeline(), TEACHER, C1_TEXT, msg_id="m4")
    check("C1 恰好 1 张活动卡", len(cards) == 1, describe(cards))
    if len(cards) == 1:
        card = cards[0]
        check("C1 kind=exam", getattr(card, "kind", None) == "exam",
              "实际=%r" % getattr(card, "kind", None))
        place = getattr(card, "place", None) or ""
        check("C1 place 包含「教学楼A101」", "教学楼A101" in place, "实际=%r" % place)
        check("C1 deadline 2027-01-05 09:00",
              same_deadline(card.deadline, D_EXAM_0900),
              "实际=%s" % fmt(card.deadline))

    # D1 接龙
    print("\n== D1 接龙：%r ==" % D1_TEXT)
    cards = send(new_pipeline(), MONITOR, D1_TEXT, msg_id="m5")
    check("D1 恰好 1 张活动卡", len(cards) == 1, describe(cards))
    if len(cards) == 1:
        card = cards[0]
        check("D1 kind=jielong", getattr(card, "kind", None) == "jielong",
              "实际=%r" % getattr(card, "kind", None))
        check("D1 标题包含「周末聚餐」", "周末聚餐" in title_of(card),
              "实际标题=%r" % title_of(card))
        check("D1 deadline 2026-11-06 18:00",
              same_deadline(card.deadline, D_FRI_1800),
              "实际=%s" % fmt(card.deadline))

    print("\n通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count:
        sys.exit(1)
    print("test_oral_misc 全部通过")


if __name__ == "__main__":
    main()
