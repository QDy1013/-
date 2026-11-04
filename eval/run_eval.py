# -*- coding: utf-8 -*-
"""评测（方案书第 9 节）

口径：
  待办召回率 = 真正需要你行动的消息里，被做成待办卡的比例
  待办精确率 = 做出来的待办卡里，源消息确实需要你行动的比例
  时间准确率 = 卡片截止时间与人工标注完全一致的比例（分钟级）
  接龙准确率 = "你还没接"判断正确（含"已接"不误报）的比例

所有数字都在「按时间切分」的留出集上算，训练集不参与评测。
模型直接用 --model（默认 engine/model.json），本方案与消融共用同一份权重，评测不重训。
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from engine.pipeline import Model, Pipeline, Profile  # noqa: E402
from engine.features import is_noise  # noqa: E402
from engine.jielong import parse as parse_jl  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]

REVISION = {"supersede", "revoke"}


def load(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            r["dt"] = datetime.fromisoformat(r["ts"])
            rows.append(r)
    rows.sort(key=lambda r: (r["dt"], r["msg_id"]))
    return rows


def run_variant(rows: list[dict], model: Model, **kw) -> dict:
    p = Pipeline(model, **kw)
    for r in rows:
        p.process(r)
    return p


def corrected_deadlines(rows: list[dict], original: dict[str, str | None]) -> dict[str, str | None]:
    """金标准用「更正后」的截止时间。

    每行可能带 supersedes（本条更正了哪条 msg_id）。同一个待办被更正过时，
    以最后一次更正消息里的 todo.deadline 为准；按首发消息比会把已经改对的卡片判错。
    更正消息自身也可能被后来的消息更正，所以先顺着 supersedes 回溯到最初的待办。
    更正没写新时间（deadline 为空）时沿用上一版答案。
    """
    points_to = {r["msg_id"]: r["supersedes"] for r in rows if r.get("supersedes")}
    fixed = dict(original)
    for r in rows:                       # rows 已按时间排好，后到的更正覆盖先到的
        target = r.get("supersedes")
        if not target:
            continue
        for _ in range(8):               # 回溯更正链，防止数据里出现环
            nxt = points_to.get(target)
            if not nxt:
                break
            target = nxt
        g = (r.get("todo") or {}).get("deadline")
        if g and target in fixed:
            fixed[target] = g
    return fixed


def score(rows: list[dict], p: Pipeline) -> dict:
    # 待办口径：①类且不是接龙（接龙是独立通道，单独算）
    # 变更/撤销不是"新待办"，它应该更新已有卡片，单独统计
    todo_rows = [r for r in rows if r["label"] == "action" and not r.get("jielong")
                 and r.get("hard") not in REVISION]
    gold_todo = {r["msg_id"] for r in todo_rows}
    gold_time = corrected_deadlines(
        rows, {r["msg_id"]: (r["todo"] or {}).get("deadline") for r in todo_rows})

    todo_cards = [c for c in p.active_cards() if c.kind == "todo"]
    got = {c.source_msg_id for c in todo_cards}

    tp = len(got & gold_todo)
    recall = tp / max(1, len(gold_todo))
    precision = tp / max(1, len(got))

    # 时间准确率：只看成功做出来的那些
    hit = tot = 0
    for c in todo_cards:
        g = gold_time.get(c.source_msg_id)
        if g is None:
            continue
        tot += 1
        if c.deadline and c.deadline == datetime.fromisoformat(g):
            hit += 1
    time_acc = hit / max(1, tot)

    # 接龙：黄金 = 有接龙且我没参加的行
    jl_gold = [r for r in rows if r.get("jielong")]
    jl_cards = [c for c in p.active_cards() if c.kind == "jielong"]
    jl_src = {c.source_msg_id for c in jl_cards}
    should = {r["msg_id"] for r in jl_gold if not r["jielong"]["joined"]}
    should_not = {r["msg_id"] for r in jl_gold if r["jielong"]["joined"]}
    jl_tp = len(jl_src & should)
    jl_fp = len(jl_src & should_not)
    jl_fn = len(should - jl_src)
    jl_recall = jl_tp / max(1, len(should))
    jl_precision = jl_tp / max(1, jl_tp + jl_fp)

    # 变更处理：本该更新而不是新建的那些，卡片截止时间有没有被改对
    rev_rows = [r for r in rows if r.get("hard") in REVISION]
    rev_ok = 0
    for r in rev_rows:
        g = (r["todo"] or {}).get("deadline")
        if not g:
            continue
        hit_c = [c for c in p.cards if c.group_id == r["group_id"]]
        if any(c.deadline == datetime.fromisoformat(g) for c in hit_c):
            rev_ok += 1
    rev_total = sum(1 for r in rev_rows if (r["todo"] or {}).get("deadline"))

    return {
        "n_messages": len(rows),
        "revision_total": rev_total,
        "revision_ok": rev_ok,
        "revision_acc": round(rev_ok / rev_total, 4) if rev_total else None,
        "gold_todo": len(gold_todo),
        "cards_todo": len(todo_cards),
        "recall": round(recall, 4),
        "precision": round(precision, 4),
        "f1": round(2 * recall * precision / max(1e-9, recall + precision), 4),
        "time_acc": round(time_acc, 4),
        "time_evaluated": tot,
        "jielong_recall": round(jl_recall, 4),
        "jielong_precision": round(jl_precision, 4),
        "jielong_should": len(should),
        "jielong_missed": jl_fn,
        "jielong_false": jl_fp,
        "noise_dropped": p.n_noise,
        "level3_calls": p.n_level3,
        "burst_promoted": p.n_promoted,
    }


def rule_only(rows: list[dict]) -> dict:
    """基线 A：只保留第一级关键词规则（方案书第 9 节"只用关键词规则"）。"""
    from engine.features import is_noise
    from engine.extract import extract

    gold = {r["msg_id"] for r in rows if r["label"] == "action"}
    hit = 0
    produced = 0
    time_hit = time_tot = 0
    for r in rows:
        if is_noise(r["text"]):
            continue
        if not any(v in r["text"] for v in ("交", "提交", "报名", "接龙", "签到", "填")):
            continue
        produced += 1
        e = extract(r["text"], r["dt"])
        if r["msg_id"] in gold:
            hit += 1
            g = (r["todo"] or {}).get("deadline")
            if g:
                time_tot += 1
                if e.deadline == datetime.fromisoformat(g):
                    time_hit += 1
    recall = hit / max(1, len(gold))
    precision = hit / max(1, produced)
    return {"n_messages": len(rows), "cards_todo": produced, "recall": round(recall, 4),
            "precision": round(precision, 4),
            "f1": round(2 * recall * precision / max(1e-9, recall + precision), 4),
            "time_acc": round(time_hit / max(1, time_tot), 4), "time_evaluated": time_tot}


def unseen_todo_score(rows: list[dict], p: Pipeline, train_texts: set[str]) -> dict:
    """只看「原文没在训练集里出现过」的待办（同一次本方案运行的结果）。

    按句子剔除后"讨论""闲聊"两类在训练集里一条不剩，没法重训模型；
    所以不重训，只把评测范围收窄到留出集里模型没见过的句子。
    """
    todo_rows = [r for r in rows if r["label"] == "action" and not r.get("jielong")
                 and r.get("hard") not in REVISION]
    gold_unseen = {r["msg_id"] for r in todo_rows
                   if r["text"].strip() not in train_texts}

    cards_unseen = [c for c in p.active_cards()
                    if c.kind == "todo" and c.source_text.strip() not in train_texts]
    got = {c.source_msg_id for c in cards_unseen}
    tp = len(got & gold_unseen)

    recall = tp / max(1, len(gold_unseen))
    precision = tp / max(1, len(got))
    return {
        "gold_todo": len(gold_unseen),
        "cards_todo": len(cards_unseen),
        "cards_hit": tp,
        "recall": round(recall, 4),
        "precision": round(precision, 4),
        "f1": round(2 * recall * precision / max(1e-9, recall + precision), 4),
    }


def split_report(train_rows: list[dict], hold: list[dict]) -> tuple[dict, set[str]]:
    """按时间切分后，如实报告留出集句子在训练集里的重复率（各类分开看）。"""
    train_texts = {r["text"].strip() for r in train_rows}

    def ratio(items: list[dict]) -> float:
        if not items:
            return 0.0
        seen = sum(1 for r in items if r["text"].strip() in train_texts)
        return round(seen / len(items), 4)

    test_msgs = [r for r in hold if not is_noise(r["text"])]
    by_label = {}
    for lab in ("action", "notice", "discussion", "chatter"):
        by_label[lab] = ratio([r for r in test_msgs if r["label"] == lab])

    info = {
        "method": "按时间切分，最后 30% 为留出集",
        "train_rows": len(train_rows),
        "test_rows": len(hold),
        "test_texts_seen_in_train_ratio": ratio(test_msgs),
        "seen_ratio_by_label": by_label,
    }
    return info, train_texts


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(ROOT / "data" / "dataset.jsonl"))
    ap.add_argument("--model", default=str(ROOT / "engine" / "model.json"))
    ap.add_argument("--out", default=str(ROOT / "eval" / "report.json"))
    args = ap.parse_args()

    rows = load(Path(args.data))
    cut = int(len(rows) * 0.7)
    train_rows, hold = rows[:cut], rows[cut:]
    print(f"留出集 {len(hold)} 条（时间切分，最后 30%）\n")

    # 本方案和消融都用同一份权重（默认线上 engine/model.json），评测不重训
    model = Model(Path(args.model))

    split_info, train_texts = split_report(train_rows, hold)

    main_pipe = run_variant(hold, model)
    results: dict[str, dict] = {}
    results["本方案（三级流水线）"] = score(hold, main_pipe)
    results["本方案（只看没见过的句子）"] = unseen_todo_score(hold, main_pipe, train_texts)
    results["基线：只用关键词规则"] = rule_only(hold)
    results["消融：去掉回复潮"] = score(hold, run_variant(hold, model, use_burst=False))
    results["消融：去掉个人档案"] = score(hold, run_variant(hold, model, use_profile=False))
    results["消融：去掉第一级规则"] = score(hold, run_variant(hold, model, use_rules=False))
    results["split"] = split_info

    cols = ["recall", "precision", "f1", "time_acc", "jielong_recall",
            "jielong_precision", "revision_acc"]
    head = f"{'方案':<22}" + "".join(f"{c:>16}" for c in cols)
    print(head)
    print("-" * len(head))
    for name, r in results.items():
        if name == "split":                  # split 是切分说明，不是方案，不进表格
            continue
        line = f"{name:<22}"
        for c in cols:
            v = r.get(c)
            line += f"{(f'{v:.1%}' if isinstance(v, (int, float)) else '—'):>16}"
        print(line)

    print(f"\n切分：{split_info['method']}")
    print(f"  训练集 {split_info['train_rows']} 条，留出集 {split_info['test_rows']} 条；"
          f"留出集非噪声句子出现在训练集的比例 "
          f"{split_info['test_texts_seen_in_train_ratio']:.2%}")
    bl = split_info["seen_ratio_by_label"]
    print("  各类内部重复率：" + "  ".join(f"{k} {v:.2%}" for k, v in bl.items()))

    un = results["本方案（只看没见过的句子）"]
    print(f"\n只看没见过的句子：待办 {un['gold_todo']} 条  "
          f"出卡 {un['cards_todo']} 张（命中 {un['cards_hit']}）  "
          f"召回 {un['recall']:.1%}  精确 {un['precision']:.1%}")

    print("\n运行开销：")
    r0 = results["本方案（三级流水线）"]
    print(f"  消息 {r0['n_messages']}  噪声丢弃 {r0['noise_dropped']}  "
          f"进第三级 {r0['level3_calls']}  输出待办卡 {r0['cards_todo']}  "
          f"回复潮提升 {r0['burst_promoted']}")

    Path(args.out).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n明细已写入 {args.out}")


if __name__ == "__main__":
    main()
