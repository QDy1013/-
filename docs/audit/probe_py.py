# -*- coding: utf-8 -*-
"""审查用探针：不改项目文件，只读它的代码和数据做对照实验。"""
import json
import sys
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(sys.argv[1])
sys.path.insert(0, str(ROOT))

from engine.pipeline import Model, Pipeline  # noqa: E402
from engine.features import is_noise  # noqa: E402
from engine.timeparse import parse_deadline  # noqa: E402

model = Model(ROOT / "engine" / "model.json")


# ---------------------------------------------------------------- A. 手写集：发送者由标准答案决定
def hardset(sender_mode: str) -> dict:
    cases = [json.loads(l) for l in (ROOT / "eval" / "hardset.jsonl").read_text(encoding="utf-8").splitlines() if l.strip()]
    tp = fp = fn = 0
    for i, c in enumerate(cases, 1):
        if c.get("needs_context") or c.get("jielong"):
            continue
        if sender_mode == "leak":
            sender = "王老师" if c["label"] in ("action", "notice") else "陈嘉禾"
        elif sender_mode == "teacher":
            sender = "王老师"
        else:
            sender = "陈嘉禾"
        p = Pipeline(model)
        p.process({"msg_id": f"h{i}", "group_id": "g", "group_name": "计科 2301 班群",
                   "sender": sender, "ts": c["ts"], "text": c["text"], "course": None})
        has = any(x.kind == "todo" for x in p.active_cards())
        if c["label"] == "action":
            tp += has
            fn += not has
        else:
            fp += has
    return {"tp": tp, "fp": fp, "fn": fn,
            "recall": round(tp / max(1, tp + fn), 3), "precision": round(tp / max(1, tp + fp), 3)}


print("== A. 手写集（不含接龙）在不同发送者设定下 ==")
for mode in ("leak", "teacher", "student"):
    print(f"  {mode:8s}", hardset(mode))

# ---------------------------------------------------------------- B. 训练/留出文本重复率
rows = [json.loads(l) for l in (ROOT / "data" / "dataset.jsonl").open(encoding="utf-8")]
rows.sort(key=lambda r: (r["ts"], r["msg_id"]))
cut = int(len(rows) * 0.7)
train, hold = rows[:cut], rows[cut:]
train_texts = {r["text"] for r in train}
hold_nn = [r for r in hold if not is_noise(r["text"])]
dup = sum(1 for r in hold_nn if r["text"] in train_texts)
print("\n== B. 留出集非噪声消息，原文在训练集里一字不差出现过的比例 ==")
print(f"  {dup}/{len(hold_nn)} = {dup / len(hold_nn):.1%}")
for lab in ("action", "notice", "discussion", "chatter"):
    sub = [r for r in hold_nn if r["label"] == lab]
    d = sum(1 for r in sub if r["text"] in train_texts)
    print(f"    {lab:10s} {d}/{len(sub)}")
print("  全数据集不同文本数:", len({r['text'] for r in rows}), "/", len(rows))

# ---------------------------------------------------------------- C. 撤销误触发：非撤销消息删掉了卡片
hold_dt = [dict(r) for r in hold]
p = Pipeline(model)
false_revoke = []
for r in hold_dt:
    before = {id(c) for c in p.cards}
    before_cards = list(p.cards)
    p.process(r)
    after = {id(c) for c in p.cards}
    removed = [c for c in before_cards if id(c) not in after]
    if removed and r.get("hard") != "revoke":
        false_revoke.append((r["text"], removed[0].title, removed[0].source_msg_id))
print("\n== C. 留出集中，不是撤销通知却删掉了已有卡片 ==")
print(f"  共 {len(false_revoke)} 次")
for t, title, sid in false_revoke[:6]:
    print(f"    「{t}」 删掉了卡片「{title}」")

# 时间不准的卡片里，有多少是被"更正"改过（不算错）的
gold = {r["msg_id"]: (r["todo"] or {}).get("deadline") for r in hold if r["label"] == "action"}
mis_upd = mis_other = 0
for c in p.active_cards():
    if c.kind != "todo":
        continue
    g = gold.get(c.source_msg_id)
    if not g:
        continue
    if c.deadline != datetime.fromisoformat(g):
        if c.updated:
            mis_upd += 1
        else:
            mis_other += 1
print(f"  时间判错的待办卡：被更正更新过的 {mis_upd} 张，其他 {mis_other} 张")

# ---------------------------------------------------------------- D. 合成数据里时间写法
import re
acts = [r["text"] for r in rows if r["label"] == "action"]
cn_hour = sum(1 for t in acts if re.search(r"[一二三四五六七八九十两]+\s*点", t))
half = sum(1 for t in acts if "点半" in t)
print("\n== D. 合成数据 action 消息中的时间写法 ==")
print(f"  共 {len(acts)} 条；中文数字钟点（八点/十点）{cn_hour} 条；'点半' {half} 条")

# ---------------------------------------------------------------- E. Python 时间解析对照（供和 Java 对比）
send = datetime(2026, 12, 20, 10, 0)
exprs = ["明晚八点前交实验报告", "明天下午3点半前交", "周五晚上8点30分前提交",
         "1月10日前交期末报告", "作业要求2-3页，周五前交", "明早8点交，晚了不收"]
print("\n== E. Python parse_deadline（发送时刻 2026-12-20 周日 10:00）==")
for e in exprs:
    r = parse_deadline(e, send)
    print(f"  {e:22s} → {r.value}  [{r.confidence}]")
