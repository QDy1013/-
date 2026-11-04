# -*- coding: utf-8 -*-
"""考试/日程卡规则（docs/考试卡规则.md）的验收用例（Python 版）。

只测对外行为：出不出卡、kind、title / deadline / place / bring、改时间与取消。
每个场景用新的 Pipeline，先 check 前提（时间能解析、铺垫消息出了卡），再 check 结果。
期望时间不手算，统一调用引擎里同一个 parse_deadline 解析同一句文字。
全程离线，时间戳固定；T0 = 2026-12-20 10:00（周日）。

新字段 bring 与 kind = "exam" 尚未落到旧引擎里，读取时用 getattr / 字符串比较，
这样测试文件本身不依赖未实现的接口。
"""

import sys
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from engine.pipeline import Pipeline, Model  # noqa: E402
from engine.timeparse import parse_deadline  # noqa: E402

GROUP = "高等数学2026秋"
GROUP_B = "大学物理课程群"
GID = "g-math"
GID_B = "g-physics"
TEACHER = "王老师"
MONITOR = "李班长"
STUDENT = "陈嘉禾"

T0 = datetime(2026, 12, 20, 10, 0)

A_TEXT = "@所有人 期中考试定在下周三上午9点，地点教三201，带学生证和2B铅笔"
F_TEXT = "期中考试改到下周四上午10点"

pass_count = 0
fail_count = 0


def check(name, ok, detail=""):
    global pass_count, fail_count
    if ok:
        pass_count += 1
        print("  ✓ " + name)
    else:
        fail_count += 1
        print("  ✗ " + name + "   " + detail)


class MsgSeq(object):
    """与 test_revoke_rules 一致：跨场景连续编号，保证 msg_id 唯一。"""

    def __init__(self):
        self.n = 0

    def msg(self, gid, gname, sender, text, ts):
        self.n += 1
        return {
            "msg_id": "m%d" % self.n,
            "group_id": gid,
            "group_name": gname,
            "sender": sender,
            "ts": ts,
            "text": text,
        }


def cards_of(p):
    """未撤销的卡，对应规则里的“活动卡”。"""
    return p.active_cards()


def exam_cards(p):
    return [c for c in p.active_cards() if c.kind == "exam"]


def expected_deadline(text, ts):
    """期望时间不手算：用引擎自己的时间解析函数解析同一句文字。"""
    return parse_deadline(text, ts).value


def same_deadline(actual, expected):
    """容忍 naive/aware 表示的差异，按本地墙上时间比较。"""
    if actual is None or expected is None:
        return actual is expected
    if actual == expected:
        return True
    try:
        return actual.strftime("%Y-%m-%d %H:%M") == expected.strftime("%Y-%m-%d %H:%M")
    except (AttributeError, ValueError):
        return False


def bring_of(card):
    return getattr(card, "bring", None) or ""


def title_of(card):
    return getattr(card, "title", None) or ""


def place_of(card):
    return getattr(card, "place", None) or ""


def brief(cards):
    return "卡数=%d，类型=[%s]" % (len(cards), ",".join(c.kind for c in cards))


def main():
    model = Model(ROOT / "engine" / "model.json")
    seq = MsgSeq()

    # ---------- A. 老师发完整的期中考试通知：只出一张 exam 卡，字段齐全 ----------
    print("== A. 老师发期中考试通知 ==")
    a = Pipeline(model)
    a_expect = expected_deadline(A_TEXT, T0)
    check("A 前提：考试时间能解析", a_expect is not None, "parsed=None")
    check("A 前提：发送者是老师", "老师" in TEACHER)
    a.process(seq.msg(GID, GROUP, TEACHER, A_TEXT, T0))
    ac = cards_of(a)
    check("A 恰好出 1 张卡", len(ac) == 1, brief(ac))
    check("A 卡片 kind 为 exam", len(ac) == 1 and ac[0].kind == "exam", brief(ac))
    check("A title 含“期中”", len(ac) == 1 and "期中" in title_of(ac[0]), title_of(ac[0]) if ac else "无卡")
    check("A deadline 等于解析结果",
          len(ac) == 1 and same_deadline(ac[0].deadline, a_expect),
          "actual=%s expected=%s" % (ac[0].deadline if ac else None, a_expect))
    check("A place 含“教三”", len(ac) == 1 and "教三" in place_of(ac[0]),
          place_of(ac[0]) if ac else "无卡")
    a_bring = bring_of(ac[0]) if ac else ""
    check("A bring 含“学生证”和“铅笔”",
          "学生证" in a_bring and "铅笔" in a_bring, "bring=" + a_bring)
    check("A 不另出 todo 卡", all(c.kind != "todo" for c in ac), brief(ac))
    a_card_id = ac[0].card_id if ac else ""
    a_source_id = ac[0].source_msg_id if ac else ""

    # ---------- B. 班长发小测：班长的“班长”二字也算可信发送者 ----------
    print("\n== B. 班长发线性代数小测 ==")
    b = Pipeline(model)
    b_text = "周五下午3点线性代数小测，闭卷"
    b_ts = T0 + timedelta(minutes=1)
    b_expect = expected_deadline(b_text, b_ts)
    check("B 前提：小测时间能解析", b_expect is not None, "parsed=None")
    check("B 前提：发送者名字含“班长”", "班长" in MONITOR)
    b.process(seq.msg(GID, GROUP, MONITOR, b_text, b_ts))
    bc = cards_of(b)
    check("B 恰好出 1 张 exam 卡",
          len(bc) == 1 and bc[0].kind == "exam", brief(bc))
    check("B deadline 等于解析结果",
          len(bc) == 1 and same_deadline(bc[0].deadline, b_expect),
          "actual=%s expected=%s" % (bc[0].deadline if bc else None, b_expect))

    # ---------- C. 答辩：绝对日期 + 时刻，title 要带“答辩” ----------
    print("\n== C. 老师发毕业设计答辩 ==")
    c = Pipeline(model)
    c_text = "毕业设计答辩 12月20日 14:00 在实验楼305"
    c_ts = T0 + timedelta(minutes=2)
    c_expect = expected_deadline(c_text, c_ts)
    check("C 前提：答辩时间能解析", c_expect is not None, "parsed=None")
    c.process(seq.msg(GID, GROUP, TEACHER, c_text, c_ts))
    cc = cards_of(c)
    check("C 恰好出 1 张 exam 卡",
          len(cc) == 1 and cc[0].kind == "exam", brief(cc))
    check("C title 含“答辩”", len(cc) == 1 and "答辩" in title_of(cc[0]),
          title_of(cc[0]) if cc else "无卡")
    check("C deadline 等于解析结果",
          len(cc) == 1 and same_deadline(cc[0].deadline, c_expect),
          "actual=%s expected=%s" % (cc[0].deadline if cc else None, c_expect))

    # ---------- D. 提问 / 第一人称 / 不可信 / 无具体时间：一条都不出卡 ----------
    print("\n== D. 不该出卡的句子 ==")
    d_cases = [
        (STUDENT, "周五考什么", "同学提问“考什么”"),
        (STUDENT, "期末考试几点啊？", "同学问“几点”"),
        (STUDENT, "我周五上午9点考试", "同学第一人称“我…”"),
        (STUDENT, "听说周三下午2点考试", "同学不可信"),
        (TEACHER, "期末考试时间另行通知", "老师但时间不具体"),
    ]
    for i, (sender, text, note) in enumerate(d_cases):
        d = Pipeline(model)
        d.process(seq.msg(GID, GROUP, sender, text, T0 + timedelta(minutes=3 + i)))
        dc = cards_of(d)
        check("D “%s” 不出卡（%s）" % (text, note), len(dc) == 0, brief(dc))

    # ---------- E. “期末大作业……提交”仍是待办，不是考试卡 ----------
    print("\n== E. 期末大作业带提交动词 ==")
    e = Pipeline(model)
    e_text = "期末大作业周五18点前提交到学习通"
    e_ts = T0 + timedelta(minutes=8)
    e_expect = expected_deadline(e_text, e_ts)
    check("E 前提：提交时间能解析", e_expect is not None, "parsed=None")
    check("E 前提：消息带提交动词“提交”", "提交" in e_text)
    e.process(seq.msg(GID, GROUP, TEACHER, e_text, e_ts))
    ec = cards_of(e)
    check("E 有卡", len(ec) >= 1, brief(ec))
    check("E 有卡且没有 exam 卡", len(ec) >= 1 and not exam_cards(e), brief(ec))

    # ---------- F. 老师改时间：只更新原卡，不新建 ----------
    print("\n== F. 老师把期中考试改到下周四 ==")
    f = Pipeline(model)
    f.process(seq.msg(GID, GROUP, TEACHER, A_TEXT, T0))
    fc = cards_of(f)
    check("F 前提：原通知出了 1 张 exam 卡",
          len(fc) == 1 and fc[0].kind == "exam", brief(fc))
    # 主控修正：本场景的原通知是新发的一条，msg_id 与场景 A 不同，要和它自己比
    a_card_id = fc[0].card_id if fc else ""
    a_source_id = fc[0].source_msg_id if fc else ""
    f_ts = T0 + timedelta(minutes=1)
    f_expect = expected_deadline(F_TEXT, f_ts)
    check("F 前提：新时间能解析", f_expect is not None, "parsed=None")
    f.process(seq.msg(GID, GROUP, TEACHER, F_TEXT, f_ts))
    fc = cards_of(f)
    check("F 改时间后仍只有 1 张卡", len(fc) == 1, brief(fc))
    check("F 还是原来那张卡（card_id 不变）",
          len(fc) == 1 and fc[0].card_id == a_card_id,
          "actual=%s expected=%s" % (fc[0].card_id if fc else None, a_card_id))
    check("F 来源消息还是原通知",
          len(fc) == 1 and fc[0].source_msg_id == a_source_id,
          "actual=%s expected=%s" % (fc[0].source_msg_id if fc else None, a_source_id))
    check("F updated 为真",
          len(fc) == 1 and getattr(fc[0], "updated", None) is True,
          "updated=%s" % (getattr(fc[0], "updated", None) if fc else None))
    check("F deadline 等于新时间的解析结果",
          len(fc) == 1 and same_deadline(fc[0].deadline, f_expect),
          "actual=%s expected=%s" % (fc[0].deadline if fc else None, f_expect))

    # ---------- G. 该改动但条件不满足：原卡不动，也不出新卡 ----------
    print("\n== G. 不该改时间的三种情况 ==")

    # G1 学生用问句问“改了吗”，不是指令
    g1 = Pipeline(model)
    g1.process(seq.msg(GID, GROUP, TEACHER, A_TEXT, T0))
    g1c = cards_of(g1)
    check("G1 前提：原通知出了 1 张 exam 卡",
          len(g1c) == 1 and g1c[0].kind == "exam", brief(g1c))
    g1_ts = T0 + timedelta(minutes=1)
    g1.process(seq.msg(GID, GROUP, STUDENT, "考试改到下周四了吗？", g1_ts))
    g1c = cards_of(g1)
    check("G1 学生问句：仍只有 1 张卡", len(g1c) == 1, brief(g1c))
    check("G1 学生问句：deadline 不变",
          len(g1c) == 1 and same_deadline(g1c[0].deadline, a_expect),
          "actual=%s expected=%s" % (g1c[0].deadline if g1c else None, a_expect))
    check("G1 学生问句：不是 updated",
          len(g1c) == 1 and getattr(g1c[0], "updated", None) is not True,
          "updated=%s" % (getattr(g1c[0], "updated", None) if g1c else None))

    # G2 学生用陈述句发同样的改时间内容，发送者不可信
    g2 = Pipeline(model)
    g2.process(seq.msg(GID, GROUP, TEACHER, A_TEXT, T0))
    g2c = cards_of(g2)
    check("G2 前提：原通知出了 1 张 exam 卡",
          len(g2c) == 1 and g2c[0].kind == "exam", brief(g2c))
    g2_ts = T0 + timedelta(minutes=1)
    g2.process(seq.msg(GID, GROUP, STUDENT, F_TEXT, g2_ts))
    g2c = cards_of(g2)
    check("G2 学生陈述句：仍只有 1 张卡", len(g2c) == 1, brief(g2c))
    check("G2 学生陈述句：deadline 不变",
          len(g2c) == 1 and same_deadline(g2c[0].deadline, a_expect),
          "actual=%s expected=%s" % (g2c[0].deadline if g2c else None, a_expect))
    check("G2 学生陈述句：不是 updated",
          len(g2c) == 1 and getattr(g2c[0], "updated", None) is not True,
          "updated=%s" % (getattr(g2c[0], "updated", None) if g2c else None))

    # G3 老师在另一个群发改时间：本群原卡不动，另一群也不出 exam 卡
    g3 = Pipeline(model)
    g3.process(seq.msg(GID, GROUP, TEACHER, A_TEXT, T0))
    g3c = cards_of(g3)
    check("G3 前提：原通知出了 1 张 exam 卡",
          len(g3c) == 1 and g3c[0].kind == "exam", brief(g3c))
    g3_ts = T0 + timedelta(minutes=1)
    g3.process(seq.msg(GID_B, GROUP_B, TEACHER, F_TEXT, g3_ts))
    ga = [c for c in g3.active_cards() if c.group_id == GID]
    gb = [c for c in g3.active_cards() if c.group_id == GID_B and c.kind == "exam"]
    check("G3 本群仍只有 1 张卡", len(ga) == 1, brief(ga))
    check("G3 本群 deadline 不变",
          len(ga) == 1 and same_deadline(ga[0].deadline, a_expect),
          "actual=%s expected=%s" % (ga[0].deadline if ga else None, a_expect))
    check("G3 本群不是 updated",
          len(ga) == 1 and getattr(ga[0], "updated", None) is not True,
          "updated=%s" % (getattr(ga[0], "updated", None) if ga else None))
    check("G3 另一群不出 exam 卡", len(gb) == 0, brief(gb))

    # ---------- H. 老师取消考试：原卡被删掉 ----------
    print("\n== H. 老师取消期中考试 ==")
    h = Pipeline(model)
    h.process(seq.msg(GID, GROUP, TEACHER, A_TEXT, T0))
    hc = cards_of(h)
    check("H 前提：原通知出了 1 张 exam 卡",
          len(hc) == 1 and hc[0].kind == "exam", brief(hc))
    h_ts = T0 + timedelta(minutes=1)
    h.process(seq.msg(GID, GROUP, TEACHER, "期中考试取消", h_ts))
    hc = cards_of(h)
    check("H 取消后没有活动卡", len(hc) == 0, brief(hc))

    print("\n通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
