# -*- coding: utf-8 -*-
"""第二级分类模型：训练 + 导出端侧权重（方案书 6.3）

模型是一个多分类逻辑回归（softmax）。选它不是为了刷分，而是因为：
  1. 训练几秒钟，权重几十 KB，端侧推理是纯点积，不用带一个 BERT 运行时；
  2. 权重可以导出成 JSON，Kotlin 侧照同一份特征定义复算，训练/线上不漂移；
  3. 线性模型能反查"是哪几个词把它判成待办"，答辩时能解释。

小 BERT 的接口预留在这里（train_bert 里），但当前跑的是线性版。
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

import numpy as np
from scipy import sparse
from sklearn.linear_model import LogisticRegression

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from engine.features import Stats, is_noise, vectorize, MANUAL_KEYS  # noqa: E402

LABELS = ["action", "notice", "discussion", "chatter"]
L2I = {l: i for i, l in enumerate(LABELS)}
MIN_DF = 3


def load(path: Path) -> list[dict]:
    rows = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            r["dt"] = datetime.fromisoformat(r["ts"])
            rows.append(r)
    rows.sort(key=lambda r: (r["dt"], r["msg_id"]))
    return rows


def time_split(rows: list[dict], train_ratio: float = 0.7) -> tuple[list[dict], list[dict]]:
    """按时间切分，不随机打乱——模拟"用过去训练、用以后来测"。"""
    cut = int(len(rows) * train_ratio)
    return rows[:cut], rows[cut:]


def build_matrix(rows: list[dict], vocab: dict[str, int] | None, stats: Stats,
                 fit: bool) -> tuple[sparse.csr_matrix, dict[str, int], np.ndarray]:
    feats = []
    ys = []
    for r in rows:
        v = vectorize(r["text"], r["sender"], stats, r["group_id"], r["dt"],
                       r.get("course"), allow_burst=False)
        feats.append(v)
        ys.append(L2I[r["label"]])
        stats.observe(r["group_id"], r["sender"], r["dt"], r["text"], r["label"])

    if fit:
        df = {}
        for v in feats:
            for k in v:
                if k.startswith("T:"):
                    df[k] = df.get(k, 0) + 1
        vocab = {k: i for i, (k, c) in enumerate(sorted(df.items(), key=lambda x: -x[1])) if c >= MIN_DF}
        for k in ("M:" + mk for mk in MANUAL_KEYS):
            vocab.setdefault(k, len(vocab))
        for k in ("X:time_action", "X:time_action_notifier", "X:atme_action", "X:burst_time"):
            vocab.setdefault(k, len(vocab))

    indptr, indices, data = [0], [], []
    for v in feats:
        cols = [(vocab[k], val) for k, val in v.items() if k in vocab]
        cols.sort()
        indices.extend(c for c, _ in cols)
        data.extend(val for _, val in cols)
        indptr.append(len(indices))
    X = sparse.csr_matrix((data, indices, indptr), shape=(len(feats), len(vocab)))
    return X, vocab, np.array(ys)


def train(data_path: Path, out_dir: Path) -> dict:
    rows = load(data_path)
    train_rows, hold_rows = time_split(rows)
    print(f"训练 {len(train_rows)} 条 / 留出 {len(hold_rows)} 条（按时间切分）")

    stats = Stats()
    Xtr, vocab, ytr = build_matrix(train_rows, None, stats, fit=True)
    # 留出集沿用训练结束时的统计量，并继续在线累积
    Xho, _, yho = build_matrix(hold_rows, vocab, stats, fit=False)
    print(f"特征维度 {len(vocab)}（其中字符 2-gram "
          f"{sum(1 for k in vocab if k.startswith('T:'))}）")

    clf = LogisticRegression(C=1.0, max_iter=3000, class_weight="balanced",
                             solver="lbfgs")
    clf.fit(Xtr, ytr)

    pred = clf.predict(Xho)
    acc = float((pred == yho).mean())
    print(f"留出集四分类准确率 {acc:.3f}")

    from collections import Counter
    print("  混淆矩阵（行=真实，列=预测）")
    print("        " + "".join(f"{l[:6]:>8}" for l in LABELS))
    for i, l in enumerate(LABELS):
        row = [int(((yho == i) & (pred == j)).sum()) for j in range(len(LABELS))]
        print(f"  {l[:6]:>6}" + "".join(f"{c:>8}" for c in row))

    # 导出端侧权重
    out_dir.mkdir(parents=True, exist_ok=True)
    inv = {i: k for k, i in vocab.items()}
    weights: dict[str, list[float]] = {}
    for j, l in enumerate(LABELS):
        col = clf.coef_[j]
        for idx, w in enumerate(col):
            if abs(w) < 1e-4:
                continue
            weights.setdefault(inv[idx], [0.0] * len(LABELS))[j] = round(float(w), 5)
    model = {
        "labels": LABELS,
        "manual_keys": MANUAL_KEYS,
        "intercept": [round(float(x), 5) for x in clf.intercept_],
        "weights": weights,
        "trained_on": len(train_rows),
        "holdout_acc": round(acc, 4),
    }
    mp = out_dir / "model.json"
    mp.write_text(json.dumps(model, ensure_ascii=False), encoding="utf-8")
    print(f"权重已导出 → {mp}（{len(weights)} 个特征，{mp.stat().st_size // 1024} KB）")
    return model


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=str(Path(__file__).resolve().parents[1] / "data" / "dataset.jsonl"))
    ap.add_argument("--out", default=str(Path(__file__).resolve().parents[1] / "engine"))
    args = ap.parse_args()
    train(Path(args.data), Path(args.out))


if __name__ == "__main__":
    main()
