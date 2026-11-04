# -*- coding: utf-8 -*-
"""真实群通知用语的时间/标题期望锁定（Python 版）。

发送时间固定为 2026-11-04 21:30（周三晚）。用例都取自群里真实会出现的说法：
"本周日24点""今晚十点前""明天中午前""11.8号之前""本月底前"……

本文件只钉期望，不迁就现状：当前有若干条不通过是正常的，修复在后面做。

三张表：
  表 1 时间表   → engine.extract.extract(原文, 发送时间).deadline
  表 2 标题表   → extract(...).action（只测 action 不为 None 的条目）
  表 3 fallback → engine.pipeline.Pipeline._fallback_title(原文)
                 （它是 staticmethod，不需要构造 Pipeline 实例）
"""

import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from engine.extract import extract  # noqa: E402
from engine.pipeline import Pipeline  # noqa: E402

SEND = datetime(2026, 11, 4, 21, 30)

# 表 1：(原文, 期望截止时间)
TIME_CASES = [
    ("大家记得本周日24点前提交选课表格", datetime(2026, 11, 9, 0, 0)),
    ("明天24点前交作业", datetime(2026, 11, 6, 0, 0)),
    ("今晚24点前交实验报告", datetime(2026, 11, 5, 0, 0)),
    ("收一下学生证复印件，明天中午前给我", datetime(2026, 11, 5, 12, 0)),
    ("后天中午前交报告", datetime(2026, 11, 6, 12, 0)),
    ("各位同学注意，本周五（11月6日）下午5点前把实验报告发到课代表邮箱",
     datetime(2026, 11, 6, 17, 0)),
    ("后天中午12点之前交团费，找学委", datetime(2026, 11, 6, 12, 0)),
    ("明晚8点开会，别迟到", datetime(2026, 11, 5, 20, 0)),
    ("今晚十点前必须交，不然扣分", datetime(2026, 11, 4, 22, 0)),
    ("11.8号之前把表格填一下", datetime(2026, 11, 8, 23, 59)),
    ("请于本月底前完成实习报告提交", datetime(2026, 11, 30, 23, 59)),
]

# 表 2：(原文, 期望标题)；均要求 extract(...).action 不为 None
TITLE_CASES = [
    ("明天早八点在教三201签到，迟到扣分", "在教三201签到"),
    ("作业：课本P56-58习题，下周一上课前交", "作业：课本P56-58习题"),
    ("请各位同学于11月10日前完成问卷填写，链接见群文件", "完成问卷填写"),
    ("请于本月底前完成实习报告提交", "完成实习报告提交"),
    ("本周内完成线上学习，系统明天关闭", "完成线上学习"),
    ("提醒一下，后天中午12点之前交团费，找学委", "交团费"),
    ("大家记得本周日24点前提交选课表格", "提交选课表格"),
]

# 表 3：没有行动词时的 fallback 标题（原文, 期望标题）
FALLBACK_CASES = [
    ("明晚8点开会，别迟到", "开会"),
    ("周六上午九点体测，带学生证和运动鞋", "体测"),
    ("收一下学生证复印件，明天中午前给我", "收一下学生证复印件"),
]

pass_count = 0
fail_count = 0


def ok(name):
    global pass_count
    pass_count += 1
    print("  ✓ " + name)


def fail(name, detail):
    global fail_count
    fail_count += 1
    print("  ✗ " + name + "   " + detail)


def fmt_dt(dt):
    return dt.strftime("%Y-%m-%d %H:%M") if dt is not None else "None（待确认）"


def main():
    print("== 表 1 时间（发送时间 2026-11-04 21:30）==")
    for text, expected in TIME_CASES:
        actual = extract(text, SEND).deadline
        if actual == expected:
            ok(text + " → " + fmt_dt(expected))
        else:
            fail(text, "期望=" + fmt_dt(expected) + "，实际=" + fmt_dt(actual))

    print("\n== 表 2 标题（extract().action）==")
    for text, expected in TITLE_CASES:
        actual = extract(text, SEND).action
        if actual == expected:
            ok(text + " → " + expected)
        else:
            fail(text, "期望=" + repr(expected) + "，实际=" + repr(actual))

    print("\n== 表 3 fallback 标题 ==")
    for text, expected in FALLBACK_CASES:
        actual = Pipeline._fallback_title(text)
        if actual == expected:
            ok(text + " → " + expected)
        else:
            fail(text, "期望=" + repr(expected) + "，实际=" + repr(actual))

    print("")
    print("通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count > 0:
        sys.exit(1)
    print("test_real_phrases 全部通过")


if __name__ == "__main__":
    main()
