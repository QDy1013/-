# -*- coding: utf-8 -*-
"""一条消息里多件事的回归用例：拆分、多个 deadline、整体重复推送去重。

每个场景用全新的 Pipeline，发送者都是"王老师"，发送时间从 2026-11-04 21:30 起；
场景 7 在同一群里 7 分钟后重发场景 1 的整句，验证去重对拆出来的每一件也生效。

只验证对外行为：活动卡数量、标题、deadline。本文件只钉期望，不迁就现状：
现在有若干条不通过是正常的，修复在后面做。
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

S1 = "这周要交两个东西：周三前交实验报告，周五前交读书笔记"
S2 = "明天晚上8点前交实验报告，周日24点前交读书笔记"
S3 = "作业1周三交，作业2周五交"
S4 = "周五18:00前提交实验报告，交学习通，逾期不候"
S5 = "周五前交实验报告，交到邮箱"
S6 = "周五之前交实验报告和读书笔记"

D_WED_2359 = datetime(2026, 11, 4, 23, 59)
D_FRI_2359 = datetime(2026, 11, 6, 23, 59)
D_THU_2000 = datetime(2026, 11, 5, 20, 0)
D_MON_0000 = datetime(2026, 11, 9, 0, 0)
D_FRI_1800 = datetime(2026, 11, 6, 18, 0)

pass_count = 0
fail_count = 0
_msg_no = 0


def check(name, ok, detail=""):
    global pass_count, fail_count
    if ok:
        pass_count += 1
        print("  \u2713 " + name)
    else:
        fail_count += 1
        print("  \u2717 " + name + "   " + detail)


def fmt(dt):
    return "None（待确认）" if dt is None else dt.strftime("%Y-%m-%d %H:%M")


def describe(cards):
    return "卡数=%d，标题=%s，deadline=%s" % (
        len(cards),
        [getattr(c, "title", None) for c in cards],
        [fmt(getattr(c, "deadline", None)) for c in cards],
    )


def same_deadline(actual, expected):
    if actual is None or expected is None:
        return actual is expected
    try:
        return actual.strftime("%Y-%m-%d %H:%M") == expected.strftime("%Y-%m-%d %H:%M")
    except (AttributeError, ValueError):
        return False


def new_pipe():
    """每个场景一个全新引擎。"""
    return Pipeline(Model(ROOT / "engine" / "model.json"))


def send(pipe, text, minutes=0):
    """发一条消息；msg_id 全局递增，时间 = 2026-11-04 21:30 + minutes。"""
    global _msg_no
    _msg_no += 1
    pipe.process({
        "msg_id": "m%d" % _msg_no,
        "group_id": GROUP_ID,
        "group_name": GROUP_NAME,
        "sender": TEACHER,
        "ts": datetime(2026, 11, 4, 21, 30) + timedelta(minutes=minutes),
        "text": text,
    })


def expect_count(pipe, tag, n):
    cards = pipe.active_cards()
    check(tag, len(cards) == n, describe(cards))
    return cards


def expect_item(pipe, tag, needle, deadline):
    """活动卡里恰好有一张标题含 needle，且 deadline 对得上。"""
    cards = pipe.active_cards()
    matched = [c for c in cards if needle in (getattr(c, "title", "") or "")]
    ok = len(matched) == 1 and same_deadline(getattr(matched[0], "deadline", None), deadline)
    check(tag, ok, describe(cards))


def main():
    # 1. 一件事一句话，两个截止时间
    print("== 1. 周三实验报告 + 周五读书笔记 ==  " + S1)
    p1 = new_pipe()
    send(p1, S1)
    expect_count(p1, "1 恰好 2 张活动卡", 2)
    expect_item(p1, "1 实验报告 → deadline 2026-11-04 23:59", "实验报告", D_WED_2359)
    expect_item(p1, "1 读书笔记 → deadline 2026-11-06 23:59", "读书笔记", D_FRI_2359)

    # 2. 相对时间：明天晚上8点 / 周日24点
    print("\n== 2. 明天晚上8点 + 周日24点 ==  " + S2)
    p2 = new_pipe()
    send(p2, S2)
    expect_count(p2, "2 恰好 2 张活动卡", 2)
    expect_item(p2, "2 实验报告 → deadline 2026-11-05 20:00", "实验报告", D_THU_2000)
    expect_item(p2, "2 读书笔记 → deadline 2026-11-09 00:00", "读书笔记", D_MON_0000)

    # 3. 两个作业，各自一个截止时间（不看顺序）
    print("\n== 3. 作业1周三交，作业2周五交 ==  " + S3)
    p3 = new_pipe()
    send(p3, S3)
    cards3 = expect_count(p3, "3 恰好 2 张活动卡", 2)
    got3 = sorted(fmt(getattr(c, "deadline", None)) for c in cards3)
    want3 = sorted([fmt(D_WED_2359), fmt(D_FRI_2359)])
    check("3 deadline 分别为 2026-11-04 23:59 和 2026-11-06 23:59",
          len(cards3) == 2 and got3 == want3, describe(cards3))

    # 4. 只有一个时间，交学习通/逾期不候 不能被拆成第二件事
    print("\n== 4. 周五18:00 一整个通知 ==  " + S4)
    p4 = new_pipe()
    send(p4, S4)
    cards4 = expect_count(p4, "4 恰好 1 张活动卡（时间不能被拆）", 1)
    check("4 deadline 2026-11-06 18:00",
          len(cards4) == 1 and same_deadline(getattr(cards4[0], "deadline", None), D_FRI_1800),
          describe(cards4))

    # 5. 交到邮箱不是第二件事
    print("\n== 5. 周五前交实验报告，交到邮箱 ==  " + S5)
    p5 = new_pipe()
    send(p5, S5)
    expect_count(p5, "5 恰好 1 张活动卡", 1)

    # 6. "和"连接的两件事仍是一份作业集合
    print("\n== 6. 周五之前交实验报告和读书笔记 ==  " + S6)
    p6 = new_pipe()
    send(p6, S6)
    expect_count(p6, "6 恰好 1 张活动卡", 1)

    # 7. 同群同人 7 分钟后重发整句 → 活动卡仍 2 张
    print("\n== 7. 重发场景 1 的整句 ==  " + S1)
    p7 = new_pipe()
    send(p7, S1)
    first7 = expect_count(p7, "7 前提：首发后 2 张活动卡", 2)
    send(p7, S1, minutes=7)
    second7 = expect_count(p7, "7 7 分钟后重发 → 活动卡仍是 2 张", 2)
    check("7 重发没有新增任何卡（含拆出来的每一件）",
          len(first7) == len(second7) == 2, describe(second7))

    print("\n通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count:
        sys.exit(1)
    print("test_multi_items 全部通过")


if __name__ == "__main__":
    main()
