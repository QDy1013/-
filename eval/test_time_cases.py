# -*- coding: utf-8 -*-
"""时间解析跨端验收（Python 侧）：与 android/test/Step6Test.java 共用 eval/time_cases.tsv。

判定口径和 Java 侧保持一致：
  - 解析不出时间（value 为空 / confidence == "none"）→ 视为 NONE
  - 否则按 "yyyy-MM-dd HH:mm" 格式化后与 expect 比较

注意 confidence：engine/timeparse.py 里 TimeParse.ok 会把"已过期"（low）也算作不好，
但 Java 的 Engine.Deadline.ok() 只排除 none，已过期的日期照样给出时间。为了让同一份
用例在两端口径一致，这里也只把 none 当 NONE——比的是"解析出的时间对不对"。

用法（在项目根目录下，脚本用 -I 隔离模式跑）：
    python -I eval/test_time_cases.py
失败时退出码 1。
"""
from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path

# 脚本在 eval/ 下，项目根目录是它的上一级；-I 不会把脚本目录放进 sys.path，所以显式加。
ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from engine.timeparse import parse_deadline  # noqa: E402

FMT = "%Y-%m-%d %H:%M"
NONE = "NONE"
TSV = ROOT / "eval" / "time_cases.tsv"


def read_cases(path: Path) -> list[tuple[str, datetime, str]]:
    rows: list[tuple[str, datetime, str]] = []
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        text, send, expect = parts[0].strip(), parts[1].strip(), parts[2].strip()
        if text == "text" and send == "send":      # 表头
            continue
        rows.append((text, datetime.strptime(send, FMT), expect))
    return rows


def main() -> None:
    if not TSV.is_file():
        print(f"找不到用例文件：{TSV}")
        sys.exit(2)

    cases = read_cases(TSV)
    print(f"== 时间解析（{TSV.relative_to(ROOT)}，{len(cases)} 条）==")

    ok_n = bad_n = 0
    for text, send, expect in cases:
        r = parse_deadline(text, send)
        ok = r.value is not None and r.confidence != "none"
        got = r.value.strftime(FMT) if ok else NONE
        if got == expect:
            ok_n += 1
            print(f"  ✓ 「{text}」→ {expect}")
        else:
            bad_n += 1
            print(f"  ✗ 「{text}」→ {expect}   实际 {got}")

    print(f"\n通过 {ok_n} 项，失败 {bad_n} 项")
    if bad_n:
        sys.exit(1)


if __name__ == "__main__":
    main()
