# -*- coding: utf-8 -*-
"""自然语言变更 / 撤销的回归用例：延期、推迟、提前、顺延、改为、取消、免交、无需提交。

每个场景用全新的 Pipeline，消息时间从 2026-11-04 19:00 起，每发一条加 7 分钟，
msg_id 全局递增。只验证对外行为：活动卡数量、标题、deadline。
"""

import sys
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from engine.pipeline import Pipeline, Model  # noqa: E402

GROUP_ID = "g1"
GROUP_NAME = "数据库群"
TEACHER = "王老师"
STUDENT = "陈嘉禾"
MONITOR = "李班长"
BASE = "周五18:00前提交实验报告，交学习通"

D_FRI_1800 = datetime(2026, 11, 6, 18, 0)
D_NEXT_MON_2200 = datetime(2026, 11, 9, 22, 0)
D_SUN_2000 = datetime(2026, 11, 8, 20, 0)
D_THU_1200 = datetime(2026, 11, 5, 12, 0)
D_NEXT_TUE_2100 = datetime(2026, 11, 10, 21, 0)

pass_count = 0
fail_count = 0


def check(name, ok, detail=""):
    global pass_count, fail_count
    if ok:
        pass_count += 1
        print("  \u2713 " + name)
    else:
        fail_count += 1
        print("  \u2717 " + name + "   " + detail)


def describe(cards):
    return "卡数=%d，标题=%s，deadline=%s" % (
        len(cards),
        [getattr(c, "title", None) for c in cards],
        [str(getattr(c, "deadline", None)) for c in cards],
    )


def same_deadline(actual, expected):
    if actual is None or expected is None:
        return actual is expected
    if actual == expected:
        return True
    try:
        return actual.strftime("%Y-%m-%d %H:%M") == expected.strftime("%Y-%m-%d %H:%M")
    except (AttributeError, ValueError):
        return False


def has_title(cards, needle):
    return any(needle in (getattr(c, "title", "") or "") for c in cards)


class Clock(object):
    """全局递增 msg_id + 固定 7 分钟间隔。"""

    def __init__(self):
        self.ts = datetime(2026, 11, 4, 19, 0)
        self.n = 0

    def send(self, p, sender, text, group_id=GROUP_ID, group_name=GROUP_NAME):
        self.n += 1
        p.process({
            "msg_id": "m%d" % self.n,
            "group_id": group_id,
            "group_name": group_name,
            "sender": sender,
            "ts": self.ts,
            "text": text,
        })
        self.ts += timedelta(minutes=7)


MODEL = None


def new_base(clock, tag):
    """每个场景的公共前提：BASE 出 1 张卡、deadline 2026-11-06 18:00。"""
    p = Pipeline(MODEL)
    clock.send(p, TEACHER, BASE)
    cards = p.active_cards()
    check(tag + " 前提：BASE 出 1 张卡", len(cards) == 1, describe(cards))
    check(tag + " 前提：BASE 的 deadline 是 2026-11-06 18:00",
          len(cards) == 1 and same_deadline(cards[0].deadline, D_FRI_1800),
          describe(cards))
    return p


def expect_deadline(p, tag, expected):
    cards = p.active_cards()
    check(tag, len(cards) == 1 and same_deadline(cards[0].deadline, expected), describe(cards))


def expect_no_active(p, tag):
    cards = p.active_cards()
    check(tag, len(cards) == 0, describe(cards))
    check(tag + "（无“不用”类残留卡）", not has_title(cards, "不用"))


def main():
    global MODEL
    MODEL = Model(ROOT / "engine" / "model.json")
    clock = Clock()

    # 1. 延期到下周一晚上10点
    print("== 1. 延期到下周 ==  " + BASE)
    p = new_base(clock, "1")
    clock.send(p, TEACHER, "实验报告延期到下周一晚上10点")
    expect_deadline(p, "1 延期到下周一晚上10点 → 1 张卡，deadline 2026-11-09 22:00", D_NEXT_MON_2200)

    # 2. 推迟到周日晚上8点
    print("\n== 2. 推迟到周日 ==")
    p = new_base(clock, "2")
    clock.send(p, TEACHER, "实验报告推迟到周日晚上8点")
    expect_deadline(p, "2 推迟到周日晚上8点 → 1 张卡，deadline 2026-11-08 20:00", D_SUN_2000)

    # 3. 提前到周四中午12点
    print("\n== 3. 提前到周四 ==")
    p = new_base(clock, "3")
    clock.send(p, TEACHER, "实验报告提前到周四中午12点")
    expect_deadline(p, "3 提前到周四中午12点 → 1 张卡，deadline 2026-11-05 12:00", D_THU_1200)

    # 4. 原发送者说不用交了
    print("\n== 4. 老师说不交 ==")
    p = new_base(clock, "4")
    clock.send(p, TEACHER, "实验报告不用交了")
    expect_no_active(p, "4 老师说“实验报告不用交了” → 活动卡 0 张")

    # 5. 班长说“周五那个不用交了”
    print("\n== 5. 班长撤销课程设计 ==")
    p = Pipeline(MODEL)
    clock.send(p, TEACHER, "周五交课程设计")
    base5 = p.active_cards()
    check("5 前提：课程设计出 1 张卡", len(base5) == 1, describe(base5))
    clock.send(p, MONITOR, "周五那个不用交了")
    expect_no_active(p, "5 李班长说“周五那个不用交了” → 活动卡 0 张")

    # 6. 无需提交了
    print("\n== 6. 无需提交 ==")
    p = Pipeline(MODEL)
    clock.send(p, TEACHER, "周五交课程设计")
    base6 = p.active_cards()
    check("6 前提：课程设计出 1 张卡", len(base6) == 1, describe(base6))
    clock.send(p, TEACHER, "课程设计无需提交了")
    expect_no_active(p, "6 老师说“课程设计无需提交了” → 活动卡 0 张")

    # 7. 免交
    print("\n== 7. 免交 ==")
    p = Pipeline(MODEL)
    clock.send(p, TEACHER, "周五交课程设计")
    base7 = p.active_cards()
    check("7 前提：课程设计出 1 张卡", len(base7) == 1, describe(base7))
    clock.send(p, TEACHER, "课程设计免交")
    expect_no_active(p, "7 老师说“课程设计免交” → 活动卡 0 张")

    # 8. 同学提问“不用交了吗”不该撤销
    print("\n== 8. 同学提问 ==")
    p = new_base(clock, "8")
    clock.send(p, STUDENT, "实验报告不用交了吗")
    cards8 = p.active_cards()
    check("8 同学问“实验报告不用交了吗” → 仍 1 张", len(cards8) == 1, describe(cards8))
    expect_deadline(p, "8 deadline 仍 2026-11-06 18:00", D_FRI_1800)
    check("8 无标题含“不用”的卡", not has_title(cards8, "不用"), describe(cards8))

    # 9. 既非老师也非原发送者说“不用交了” → 不撤销
    print("\n== 9. 无关的人说不用交了 ==")
    p = new_base(clock, "9")
    clock.send(p, STUDENT, "实验报告不用交了")
    cards9 = p.active_cards()
    check("9 陈嘉禾说“实验报告不用交了” → 仍 1 张", len(cards9) == 1, describe(cards9))
    expect_deadline(p, "9 deadline 仍 2026-11-06 18:00", D_FRI_1800)
    check("9 无标题含“不用”的卡", not has_title(cards9, "不用"), describe(cards9))

    # 10. 顺延到下周二晚上9点
    print("\n== 10. 顺延 ==")
    p = new_base(clock, "10")
    clock.send(p, TEACHER, "实验报告顺延到下周二晚上9点")
    expect_deadline(p, "10 顺延到下周二晚上9点 → 1 张卡，deadline 2026-11-10 21:00", D_NEXT_TUE_2100)

    # 11. 更正改为周日晚上8点，再取消
    print("\n== 11. 更正后再取消 ==")
    p = new_base(clock, "11")
    clock.send(p, TEACHER, "更正一下，实验报告改为周日晚上8点")
    expect_deadline(p, "11 更正为周日晚上8点 → 1 张卡，deadline 2026-11-08 20:00", D_SUN_2000)
    clock.send(p, TEACHER, "实验报告取消")
    expect_no_active(p, "11 老师说“实验报告取消” → 活动卡 0 张")

    # 12. 无关的“不用带电脑”不能撤销实验报告卡
    print("\n== 12. 无关的“不用” ==")
    p = new_base(clock, "12")
    clock.send(p, TEACHER, "下周一上课，大家不用带电脑")
    cards12 = p.active_cards()
    target = next((c for c in cards12 if "实验报告" in (getattr(c, "title", "") or "")), None)
    check("12 老师说“下周一上课，大家不用带电脑” → 实验报告的卡仍在",
          len(cards12) == 1 and target is not None, describe(cards12))
    check("12 实验报告卡的 deadline 仍 2026-11-06 18:00",
          target is not None and same_deadline(target.deadline, D_FRI_1800), describe(cards12))

    print("\n通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count > 0:
        sys.exit(1)
    print("test_natural_revisions 全部通过")


if __name__ == "__main__":
    main()
