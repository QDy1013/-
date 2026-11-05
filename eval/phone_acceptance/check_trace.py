#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_trace.py

读取真机受控系统通知的匿名记录 trace.json，逐项验收：

1. stages 必须是 list，created、updated、restarted、cancelled 各出现一次并按此顺序排列；
2. created / updated / restarted 各恰好两张卡，标题为“性能验收考试”“交性能验收报告”且不重复；
3. created 的时间分别为“明天 09:00”“明天 18:00”；
4. updated 的时间分别为“后天 10:00”“后天 20:00”，且 updated 标记均为 true；
5. restarted 的标题、when、updated 与 updated 阶段完全一致（与卡片顺序无关）；
6. cancelled 的 cards 必须为空；
7. menu 不含“粘贴”“分享导入”“读当前群”“手动补录”，且包含“自动读取自检”“通知读取权限”。

用法：
    python check_trace.py [trace.json 路径]

未提供参数时读取与本脚本同目录的 trace.json。
输出一个 JSON 对象（含 passed 与 checks）；检查失败退出码非 0。
输入缺失、JSON 损坏、字段缺失或数据类型错误时只输出可读的错误 JSON，不抛 traceback。
"""

import json
import os
import sys

EXPECTED_STAGE_ORDER = ("created", "updated", "restarted", "cancelled")
EXPECTED_CARD_COUNT = 2

CREATED_SCHEDULE = {
    "性能验收考试": "明天 09:00",
    "交性能验收报告": "明天 18:00",
}

UPDATED_SCHEDULE = {
    "性能验收考试": "后天 10:00",
    "交性能验收报告": "后天 20:00",
}

FORBIDDEN_MENU_KEYWORDS = ("粘贴", "分享导入", "读当前群", "手动补录")
REQUIRED_MENU_KEYWORDS = ("自动读取自检", "通知读取权限")


class TraceInputError(Exception):
    """trace 文件无法读取或无法解析为 JSON 时抛出。"""


def describe_type(value):
    """把 Python 值转换成便于阅读的中文类型名。"""
    # bool 是 int 的子类，必须先判断。
    if isinstance(value, bool):
        return "布尔值"
    if isinstance(value, (int, float)):
        return "数字"
    if isinstance(value, str):
        return "字符串"
    if isinstance(value, list):
        return "list"
    if isinstance(value, dict):
        return "对象(dict)"
    if value is None:
        return "null"
    return type(value).__name__


def add_check(checks, name, passed, message):
    """把一条检查结果追加到结果数组。"""
    checks.append(
        {
            "name": name,
            "passed": bool(passed),
            "message": message,
        }
    )


def resolve_trace_path(argv):
    """解析命令行参数，缺省使用脚本同目录的 trace.json。"""
    if len(argv) > 1 and str(argv[1]).strip():
        return argv[1]
    script_dir = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(script_dir, "trace.json")


def read_trace_json(path):
    """读取并解析 trace 文件，任何失败都转换为可读的 TraceInputError。"""
    try:
        # utf-8-sig 兼容带 BOM 的 UTF-8 文件，普通 UTF-8 文件不受影响。
        with open(path, "r", encoding="utf-8-sig") as handle:
            text = handle.read()
    except FileNotFoundError:
        raise TraceInputError("找不到 trace 文件：%s" % path)
    except IsADirectoryError:
        raise TraceInputError("trace 路径是目录而不是文件：%s" % path)
    except PermissionError:
        raise TraceInputError("没有权限读取 trace 文件：%s" % path)
    except UnicodeDecodeError as exc:
        raise TraceInputError(
            "trace 文件不是合法的 UTF-8 文本：%s（%s）" % (path, exc)
        )
    except OSError as exc:
        raise TraceInputError("读取 trace 文件失败：%s（%s）" % (path, exc))

    if not text.strip():
        raise TraceInputError("trace 文件为空，无法解析 JSON：%s" % path)

    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise TraceInputError(
            "trace 文件不是合法 JSON：第 %d 行第 %d 列附近 %s"
            % (exc.lineno, exc.colno, exc.msg)
        )


def validate_root(checks, data):
    """校验顶层结构必须是 JSON 对象。"""
    if not isinstance(data, dict):
        add_check(
            checks,
            "trace.type",
            False,
            "trace 顶层必须是 JSON 对象，实际为 %s" % describe_type(data),
        )
        return False
    add_check(checks, "trace.type", True, "trace 顶层是 JSON 对象")
    return True


def collect_stage_names(checks, stages):
    """按原始顺序收集阶段名，保持重复项，绝不把 stages 转成 dict 覆盖重复阶段。"""
    names = []
    for index, entry in enumerate(stages):
        position = "stages 第 %d 项" % (index + 1)
        if not isinstance(entry, dict):
            add_check(
                checks,
                "stages.entries",
                False,
                "%s 必须是对象，实际为 %s" % (position, describe_type(entry)),
            )
            return None
        if "stage" not in entry:
            add_check(checks, "stages.entries", False, "%s 缺少 stage 字段" % position)
            return None
        name = entry["stage"]
        if not isinstance(name, str):
            add_check(
                checks,
                "stages.entries",
                False,
                "%s 的 stage 必须是字符串，实际为 %s"
                % (position, describe_type(name)),
            )
            return None
        names.append(name)
    return names


def check_stage_order(checks, names):
    """阶段必须各出现一次，并且顺序为 created、updated、restarted、cancelled。"""
    expected = list(EXPECTED_STAGE_ORDER)
    if names == expected:
        add_check(
            checks,
            "stages.order",
            True,
            "阶段顺序为 created、updated、restarted、cancelled，且各出现一次",
        )
        return True

    missing = [name for name in expected if name not in names]
    duplicated = sorted({name for name in names if names.count(name) > 1})
    unexpected = [name for name in names if name not in expected]

    details = []
    if missing:
        details.append("缺少阶段：" + "、".join(missing))
    if duplicated:
        details.append("重复阶段：" + "、".join(duplicated))
    if unexpected:
        details.append("未知阶段：" + "、".join(unexpected))
    if names:
        details.append("实际顺序：" + "、".join(names))
    else:
        details.append("stages 为空")
    add_check(
        checks,
        "stages.order",
        False,
        "阶段必须各出现一次且按 created、updated、restarted、cancelled 排列；"
        + "；".join(details),
    )
    return False


def load_stage_cards(checks, stage_name, stage):
    """校验某阶段的 cards 结构与字段类型，结构不合法时返回 None。"""
    label = "%s.cards" % stage_name
    if "cards" not in stage:
        add_check(checks, label, False, "阶段 %s 缺少 cards 字段" % stage_name)
        return None
    cards = stage["cards"]
    if not isinstance(cards, list):
        add_check(
            checks,
            label,
            False,
            "阶段 %s 的 cards 必须是 list，实际为 %s"
            % (stage_name, describe_type(cards)),
        )
        return None

    problems = []
    for index, card in enumerate(cards):
        position = "第 %d 张卡" % (index + 1)
        if not isinstance(card, dict):
            problems.append("%s必须是对象，实际为 %s" % (position, describe_type(card)))
            continue
        for field in ("title", "when", "updated"):
            if field not in card:
                problems.append("%s缺少 %s 字段" % (position, field))
        if "title" in card and not isinstance(card["title"], str):
            problems.append(
                "%s的 title 必须是字符串，实际为 %s"
                % (position, describe_type(card["title"]))
            )
        if "when" in card and not isinstance(card["when"], str):
            problems.append(
                "%s的 when 必须是字符串，实际为 %s"
                % (position, describe_type(card["when"]))
            )
        if "updated" in card and type(card["updated"]) is not bool:
            problems.append(
                "%s的 updated 必须是布尔值，实际为 %s"
                % (position, describe_type(card["updated"]))
            )

    if problems:
        add_check(checks, label, False, "；".join(problems))
        return None

    add_check(
        checks,
        label,
        True,
        "阶段 %s 的 cards 结构合法，共 %d 张" % (stage_name, len(cards)),
    )
    return cards


def check_card_count(checks, stage_name, cards):
    """检查卡片数量是否恰好为两张。"""
    actual = len(cards)
    if actual == EXPECTED_CARD_COUNT:
        add_check(
            checks,
            "%s.card_count" % stage_name,
            True,
            "阶段 %s 恰好有 %d 张卡" % (stage_name, EXPECTED_CARD_COUNT),
        )
        return True
    add_check(
        checks,
        "%s.card_count" % stage_name,
        False,
        "阶段 %s 必须有 %d 张卡，实际为 %d 张"
        % (stage_name, EXPECTED_CARD_COUNT, actual),
    )
    return False


def check_card_titles(checks, stage_name, cards, expected_titles):
    """检查标题集合正确且没有重复。"""
    titles = [card["title"] for card in cards]
    expected_set = set(expected_titles)
    actual_set = set(titles)
    duplicated = sorted({title for title in titles if titles.count(title) > 1})
    missing = sorted(expected_set - actual_set)
    unexpected = sorted(actual_set - expected_set)

    if actual_set == expected_set and not duplicated and len(titles) == len(expected_titles):
        add_check(
            checks,
            "%s.titles" % stage_name,
            True,
            "阶段 %s 的标题为性能验收考试、交性能验收报告，且没有重复" % stage_name,
        )
        return True

    details = []
    if duplicated:
        details.append("重复标题：" + "、".join(duplicated))
    if missing:
        details.append("缺少标题：" + "、".join(missing))
    if unexpected:
        details.append("多余标题：" + "、".join(unexpected))
    if not details:
        details.append("标题与预期不一致")
    add_check(
        checks,
        "%s.titles" % stage_name,
        False,
        "阶段 %s 的标题不符合要求；%s" % (stage_name, "；".join(details)),
    )
    return False


def check_card_schedule(checks, stage_name, cards, expected_schedule):
    """按标题检查 when 是否为预期时间，并要求标题集合完整、不重复。"""
    actual_when = {}
    duplicated = set()
    for card in cards:
        title = card["title"]
        if title in actual_when:
            duplicated.add(title)
        actual_when[title] = card["when"]

    problems = []
    if duplicated:
        problems.append("标题重复出现：" + "、".join(sorted(duplicated)))
    missing = sorted(set(expected_schedule) - set(actual_when))
    if missing:
        problems.append("缺少标题：" + "、".join(missing))
    unexpected = sorted(set(actual_when) - set(expected_schedule))
    if unexpected:
        problems.append("未知标题：" + "、".join(unexpected))
    for title in sorted(set(expected_schedule) & set(actual_when)):
        expected_when = expected_schedule[title]
        if actual_when[title] != expected_when:
            problems.append(
                "%s 的 when 应为“%s”，实际为“%s”"
                % (title, expected_when, actual_when[title])
            )

    if problems:
        add_check(
            checks,
            "%s.schedule" % stage_name,
            False,
            "阶段 %s 的时间不符合要求；%s" % (stage_name, "；".join(problems)),
        )
        return False
    add_check(
        checks,
        "%s.schedule" % stage_name,
        True,
        "阶段 %s 的两张卡时间符合预期" % stage_name,
    )
    return True


def check_updated_flag(checks, stage_name, cards, expected_value):
    """检查所有卡片的 updated 布尔值是否等于期望值。"""
    wrong = [
        card["title"]
        for card in cards
        if card["updated"] is not expected_value
    ]
    if wrong:
        add_check(
            checks,
            "%s.updated_flag" % stage_name,
            False,
            "阶段 %s 中以下卡片的 updated 应为 %s：%s"
            % (stage_name, expected_value, "、".join(wrong)),
        )
        return False
    add_check(
        checks,
        "%s.updated_flag" % stage_name,
        True,
        "阶段 %s 的所有卡片 updated 均为 %s" % (stage_name, expected_value),
    )
    return True


def normalize_cards(cards):
    """把卡片转成可排序的三元组列表，用于忽略顺序的比较。"""
    return sorted(
        (card["title"], card["when"], card["updated"]) for card in cards
    )


def check_restarted_matches(checks, updated_cards, restarted_cards):
    """restarted 的标题、when、updated 必须与 updated 完全一致，与顺序无关。"""
    updated_key = normalize_cards(updated_cards)
    restarted_key = normalize_cards(restarted_cards)
    if updated_key == restarted_key:
        add_check(
            checks,
            "restarted.matches_updated",
            True,
            "restarted 的标题、when、updated 与 updated 完全一致（与顺序无关）",
        )
        return True
    add_check(
        checks,
        "restarted.matches_updated",
        False,
        "restarted 必须与 updated 完全一致，与顺序无关；updated=%s，restarted=%s"
        % (updated_key, restarted_key),
    )
    return False


def check_cancelled(checks, stage):
    """cancelled 的 cards 必须存在、必须是 list 且为空。"""
    if "cards" not in stage:
        add_check(checks, "cancelled.cards", False, "阶段 cancelled 缺少 cards 字段")
        return False
    cards = stage["cards"]
    if not isinstance(cards, list):
        add_check(
            checks,
            "cancelled.cards",
            False,
            "阶段 cancelled 的 cards 必须是 list，实际为 %s" % describe_type(cards),
        )
        return False
    if cards:
        add_check(
            checks,
            "cancelled.cards",
            False,
            "阶段 cancelled 的 cards 必须为空，实际有 %d 张" % len(cards),
        )
        return False
    add_check(
        checks,
        "cancelled.cards",
        True,
        "阶段 cancelled 的 cards 为空",
    )
    return True


def check_menu(checks, data):
    """menu 必须是字符串 list，且关键词约束满足。"""
    if "menu" not in data:
        add_check(checks, "menu.type", False, "trace 缺少 menu 字段")
        return
    menu = data["menu"]
    if not isinstance(menu, list):
        add_check(
            checks,
            "menu.type",
            False,
            "menu 必须是 list，实际为 %s" % describe_type(menu),
        )
        return

    invalid_entries = []
    for index, entry in enumerate(menu):
        if not isinstance(entry, str):
            invalid_entries.append(
                "第 %d 项为 %s" % (index + 1, describe_type(entry))
            )
    if invalid_entries:
        add_check(
            checks,
            "menu.type",
            False,
            "menu 的每一项都必须是字符串；" + "；".join(invalid_entries),
        )
        return
    add_check(checks, "menu.type", True, "menu 是字符串 list，共 %d 项" % len(menu))

    offending = []
    for keyword in FORBIDDEN_MENU_KEYWORDS:
        for entry in menu:
            if keyword in entry:
                offending.append("“%s”（出现在“%s”）" % (keyword, entry))
    if offending:
        add_check(
            checks,
            "menu.forbidden_absent",
            False,
            "menu 不得包含粘贴、分享导入、读当前群、手动补录；命中："
            + "、".join(offending),
        )
    else:
        add_check(
            checks,
            "menu.forbidden_absent",
            True,
            "menu 不含粘贴、分享导入、读当前群、手动补录",
        )

    missing = [
        keyword
        for keyword in REQUIRED_MENU_KEYWORDS
        if not any(keyword in entry for entry in menu)
    ]
    if missing:
        add_check(
            checks,
            "menu.required_present",
            False,
            "menu 必须包含自动读取自检、通知读取权限；缺少："
            + "、".join(missing),
        )
    else:
        add_check(
            checks,
            "menu.required_present",
            True,
            "menu 同时包含自动读取自检、通知读取权限",
        )


def validate_stages(checks, data):
    """逐阶段执行顺序、结构与内容检查。"""
    if "stages" not in data:
        add_check(checks, "stages.type", False, "trace 缺少 stages 字段")
        return
    stages = data["stages"]
    if not isinstance(stages, list):
        add_check(
            checks,
            "stages.type",
            False,
            "stages 必须是 list，实际为 %s" % describe_type(stages),
        )
        return

    add_check(checks, "stages.type", True, "stages 是 list，共 %d 个阶段" % len(stages))

    names = collect_stage_names(checks, stages)
    if names is None:
        return
    if not check_stage_order(checks, names):
        # 顺序或数量不正确时，后续的逐阶段比对没有可靠基准，直接结束。
        return

    stage_map = {entry["stage"]: entry for entry in stages}

    created_cards = load_stage_cards(checks, "created", stage_map["created"])
    if created_cards is not None:
        check_card_count(checks, "created", created_cards)
        check_card_titles(checks, "created", created_cards, list(CREATED_SCHEDULE))
        check_card_schedule(checks, "created", created_cards, CREATED_SCHEDULE)
        check_updated_flag(checks, "created", created_cards, False)

    updated_cards = load_stage_cards(checks, "updated", stage_map["updated"])
    if updated_cards is not None:
        check_card_count(checks, "updated", updated_cards)
        check_card_titles(checks, "updated", updated_cards, list(UPDATED_SCHEDULE))
        check_card_schedule(checks, "updated", updated_cards, UPDATED_SCHEDULE)
        check_updated_flag(checks, "updated", updated_cards, True)

    restarted_cards = load_stage_cards(checks, "restarted", stage_map["restarted"])
    if restarted_cards is not None:
        check_card_count(checks, "restarted", restarted_cards)
        check_card_titles(checks, "restarted", restarted_cards, list(UPDATED_SCHEDULE))
        check_card_schedule(checks, "restarted", restarted_cards, UPDATED_SCHEDULE)
        check_updated_flag(checks, "restarted", restarted_cards, True)

    if updated_cards is not None and restarted_cards is not None:
        check_restarted_matches(checks, updated_cards, restarted_cards)

    check_cancelled(checks, stage_map["cancelled"])


def run(argv):
    """执行完整验收，返回 (输出对象, 退出码)。"""
    checks = []
    path = resolve_trace_path(argv)

    try:
        data = read_trace_json(path)
    except TraceInputError as exc:
        checks.append(
            {
                "name": "trace.read",
                "passed": False,
                "message": str(exc),
            }
        )
        return {"passed": False, "checks": checks}, 2

    if not validate_root(checks, data):
        return {"passed": False, "checks": checks}, 2

    validate_stages(checks, data)
    check_menu(checks, data)

    passed = all(check["passed"] for check in checks)
    return {"passed": passed, "checks": checks}, (0 if passed else 1)


def main():
    try:
        payload, exit_code = run(sys.argv)
    except Exception as exc:  # 兜底：任何意外都转成 JSON 错误，不输出 traceback。
        payload = {
            "passed": False,
            "checks": [
                {
                    "name": "checker.error",
                    "passed": False,
                    "message": "验收器内部错误：%s" % exc,
                }
            ],
        }
        exit_code = 3

    text = json.dumps(payload, ensure_ascii=False, indent=2)
    try:
        sys.stdout.write(text + "\n")
    except UnicodeEncodeError:
        sys.stdout.write(json.dumps(payload, ensure_ascii=True, indent=2) + "\n")
    sys.stdout.flush()
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
