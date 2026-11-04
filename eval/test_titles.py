# -*- coding: utf-8 -*-
"""事项标题抽取的期望锁定（Python 版）。

真机上发现标题会残留时间的残余字（"上午交作业""前交实验报告"），
本文件先把期望钉死，不迁就现状：当前有若干条不通过是正常的。

只测对外行为：调用 engine.extract.find_action(原文)，取返回的第一个元素（标题）。
不引入 Pipeline，也不改任何业务代码。
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from engine.extract import find_action  # noqa: E402

# (原文, 期望标题)
CASES = [
    ("明天20:00前交实验报告", "交实验报告"),
    ("周五18:00前报名体测", "报名体测"),
    ("明天上午9点交作业，别忘了", "交作业"),
    ("明天上午9点交作业", "交作业"),
    ("下午3点前提交读书报告", "提交读书报告"),
    ("晚上8点半前交小组展示PPT", "交小组展示PPT"),
    ("明天中午12点前交报告", "交报告"),
    ("周三下午15:30之前填报选课意向表", "填报选课意向表"),
    ("请大家周五前交作业", "交作业"),
    ("明天20点前交实验报告", "交实验报告"),
    ("11月11日08:00交读书报告", "交读书报告"),
    ("今晚22点前交实验报告", "交实验报告"),
    ("周五前交实验报告", "交实验报告"),
    ("明天早上8:30前签到", "签到"),
    ("交实验报告", "交实验报告"),
    ("报名体测", "报名体测"),
]

# 这一条不要求等于某个标题，只要求结果里不含时间的残余字。
SPECIAL_TEXT = "请同学们务必在周日晚上9点前报名"
SPECIAL_FORBIDDEN = ("晚上", "点", "前")

pass_count = 0
fail_count = 0


def fail(name, detail):
    global fail_count
    fail_count += 1
    print("  ✗ " + name + "   " + detail)


def ok(name):
    global pass_count
    pass_count += 1
    print("  ✓ " + name)


def main():
    for text, expected in CASES:
        actual, _verb = find_action(text)
        if actual == expected:
            ok(text + " → " + expected)
        else:
            fail(text, "期望=" + repr(expected) + "，实际=" + repr(actual))

    actual, _verb = find_action(SPECIAL_TEXT)
    bad = [w for w in SPECIAL_FORBIDDEN if actual and w in actual]
    if bad:
        fail(SPECIAL_TEXT, "结果不得包含 " + "、".join(SPECIAL_FORBIDDEN)
             + "，实际=" + repr(actual))
    else:
        ok(SPECIAL_TEXT + " → 不含时间的残余字，实际=" + repr(actual))

    print("")
    print("通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count > 0:
        sys.exit(1)
    print("test_titles 全部通过")


if __name__ == "__main__":
    main()
