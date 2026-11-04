# -*- coding: utf-8 -*-
"""审查意见第 5 条的验收用例（Python 版）：撤销 / 更正只作用于"对得上"的那张卡。

场景 A~I 与 android/test/Step4Test.java 原样对齐：消息内容、发送者、群、时间间隔照抄。
每个场景用新的 Pipeline，先 check 前提（铺垫消息出了卡），再 check 结果。
只测对外行为：卡片还在不在、updated 标志、deadline 数值。全程离线，时间戳固定。

撤销 / 更正规则：
  1. 发送者必须是那张卡的原发送者，或名字含 老师/辅导员/班长/课代表 等；
  2. 必须能定位到具体那张卡（只在同一群里找）：
     - 原发送者本人 + 指代词"之前/上条/上面/前面"时不限时间，找此人在本群最新的一张卡；
     - 不是原发送者时，仍要求事项匹配（与标题/原文有共同关键词），或"刚才"类指代词且 30 分钟内；
     - 只有"刚才"受 30 分钟限制。
  两条都满足才生效，对不上就什么都不做。
"""

import sys
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from engine.pipeline import Pipeline, Model  # noqa: E402

GROUP = "大学物理课程群"
GROUP_B = "数据库系统课程群"
GID = "g-physics"
GID_B = "g-db"
TEACHER = "王老师"
STUDENT = "陈嘉禾"
REPORT = "@所有人 周五 18 点前交实验报告到学习通"

# Java 里 t0 = 1_766_000_000_000L，这里用固定的本地时间表示，场景内相对间隔不变。
T0 = datetime(2026, 12, 20, 10, 0)

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
    """与 Java 的 seq 一致：跨场景连续编号，保证 msg_id 唯一。"""

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
    """未撤销的卡（对应 Java 的 e.all()）。"""
    return p.active_cards()


def find_card(p, msg_id):
    """对应 Java 的 byId：按来源消息 id 找仍在的卡。"""
    if not msg_id:
        return None
    for c in p.active_cards():
        if getattr(c, "source_msg_id", None) == msg_id:
            return c
    return None


def state(p, msg_id):
    return "卡数=%d，该卡%s" % (len(p.active_cards()), "不在了" if find_card(p, msg_id) is None else "还在")


def next_week_monday_at(ts, hh, mm):
    """对应 Java 的 nextWeekMondayAt：按消息本地日期算"下周一"的 hh:mm。"""
    day = ts.replace(hour=0, minute=0, second=0, microsecond=0)
    # Python 的 weekday(): 周一=0，与 Engine 的 weekday() 同口径。
    return (day + timedelta(days=7 - ts.weekday())).replace(hour=hh, minute=mm)


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


def deadline_text(d):
    return "无卡" if d is None else str(d)


def main():
    model = Model(ROOT / "engine" / "model.json")
    seq = MsgSeq()

    # ---------- A. 同学说"取消外卖"，不该动老师的作业卡 ----------
    print("== A. 无关的人说取消 ==")
    a = Pipeline(model)
    a.process(seq.msg(GID, GROUP, TEACHER, REPORT, T0))
    check("A 前提：老师布置实验报告出了 1 张卡", len(cards_of(a)) == 1, "出了 %d 张" % len(cards_of(a)))
    aid = cards_of(a)[0].source_msg_id if cards_of(a) else ""
    a.process(seq.msg(GID, GROUP, STUDENT, "我把今晚的外卖取消了", T0 + timedelta(minutes=1)))
    check("A 同学说“我把今晚的外卖取消了”，实验报告的卡不动",
          bool(aid) and find_card(a, aid) is not None, state(a, aid))

    # ---------- B. 老师说"实验课取消"，不该误删两张无关的卡 ----------
    print("\n== B. 老师的取消对不上事项 ==")
    b = Pipeline(model)
    b.process(seq.msg("g-class", "计科 2301 班群", TEACHER,
                      "@所有人 周五 18 点前填选课意向表，教务系统里填", T0))
    b.process(seq.msg("g-class", "计科 2301 班群", TEACHER,
                      "@所有人 周日 22 点前完成课后习题第 3 章，提交到学习通", T0 + timedelta(minutes=1)))
    check("B 前提：两条通知出了 2 张卡", len(cards_of(b)) == 2, "出了 %d 张" % len(cards_of(b)))
    bc = cards_of(b)
    c1 = next((c for c in bc if "选课意向表" in (getattr(c, "title", "") or "")), None)
    c2 = next((c for c in bc if "课后习题" in (getattr(c, "title", "") or "")), None)
    b1 = getattr(c1, "source_msg_id", "") if c1 is not None else ""
    b2 = getattr(c2, "source_msg_id", "") if c2 is not None else ""
    check("B 前提：两张卡分别是选课意向表和课后习题",
          c1 is not None and c2 is not None,
          "标题=" + (getattr(c1, "title", "无") if c1 is None else c1.title) + " / "
          + (getattr(c2, "title", "无") if c2 is None else c2.title))
    b.process(seq.msg("g-class", "计科 2301 班群", TEACHER, "这周实验课取消", T0 + timedelta(minutes=2)))
    check("B 老师说“这周实验课取消”，选课意向表的卡还在",
          bool(b1) and find_card(b, b1) is not None, state(b, b1))
    check("B 老师说“这周实验课取消”，课后习题的卡还在",
          bool(b2) and find_card(b, b2) is not None, state(b, b2))

    # ---------- C. 老师 5 分钟后"刚才那条作废"：指代词 + 30 分钟内 → 生效 ----------
    print("\n== C. 原发送者 + 指代词 + 5 分钟 ==")
    c = Pipeline(model)
    c.process(seq.msg(GID, GROUP, TEACHER, REPORT, T0))
    check("C 前提：老师布置实验报告出了 1 张卡", len(cards_of(c)) == 1, "出了 %d 张" % len(cards_of(c)))
    cid = cards_of(c)[0].source_msg_id if cards_of(c) else ""
    c.process(seq.msg(GID, GROUP, TEACHER, "刚才那条作废", T0 + timedelta(minutes=5)))
    check("C 5 分钟后老师说“刚才那条作废”，实验报告的卡被删",
          bool(cid) and find_card(c, cid) is None, state(c, cid))

    # ---------- D. 同样的话但过了 2 小时、也没提事项：对不上 → 不生效 ----------
    print("\n== D. 指代词超时 ==")
    d = Pipeline(model)
    d.process(seq.msg(GID, GROUP, TEACHER, REPORT, T0))
    check("D 前提：老师布置实验报告出了 1 张卡", len(cards_of(d)) == 1, "出了 %d 张" % len(cards_of(d)))
    did = cards_of(d)[0].source_msg_id if cards_of(d) else ""
    d.process(seq.msg(GID, GROUP, TEACHER, "刚才那条作废", T0 + timedelta(hours=2)))
    check("D 2 小时后老师说“刚才那条作废”且没提事项，实验报告的卡还在",
          bool(did) and find_card(d, did) is not None, state(d, did))

    # ---------- E. 同一位老师提到事项更正截止：更新原卡 ----------
    print("\n== E. 更正截止时间 ==")
    e = Pipeline(model)
    e.process(seq.msg(GID, GROUP, TEACHER, REPORT, T0))
    check("E 前提：老师布置实验报告出了 1 张卡", len(cards_of(e)) == 1, "出了 %d 张" % len(cards_of(e)))
    eid = cards_of(e)[0].source_msg_id if cards_of(e) else ""
    t1 = T0 + timedelta(days=1)
    e.process(seq.msg(GID, GROUP, TEACHER, "更正：实验报告截止改到下周一 22 点", t1))
    ec = find_card(e, eid)
    want = next_week_monday_at(t1, 22, 0)
    check("E 第二天老师更正“实验报告”截止，原卡被更新（updated=true、没有多出卡）",
          ec is not None and bool(getattr(ec, "updated", False)) and len(cards_of(e)) == 1,
          "卡数=%d，updated=%s" % (len(cards_of(e)), bool(ec is not None and getattr(ec, "updated", False))))
    check("E 截止时间改到下周一 22:00",
          ec is not None and same_deadline(getattr(ec, "deadline", None), want),
          "实际=%s，期望=%s" % (deadline_text(getattr(ec, "deadline", None) if ec is not None else None), want))

    # ---------- F. 同学用"改成"提问：不是原发送者 → 不改 ----------
    print("\n== F. 无关的人提到事项也不改 ==")
    f = Pipeline(model)
    f.process(seq.msg(GID, GROUP, TEACHER, REPORT, T0))
    check("F 前提：老师布置实验报告出了 1 张卡", len(cards_of(f)) == 1, "出了 %d 张" % len(cards_of(f)))
    fc0 = cards_of(f)[0] if cards_of(f) else None
    fid = getattr(fc0, "source_msg_id", "") if fc0 is not None else ""
    before = getattr(fc0, "deadline", None) if fc0 is not None else None
    f.process(seq.msg(GID, GROUP, STUDENT, "实验报告改成周一交了吗", T0 + timedelta(minutes=3)))
    fc = find_card(f, fid)
    check("F 同学问“实验报告改成周一交了吗”，原卡没被改（updated=false）",
          fc is not None and not bool(getattr(fc, "updated", False)),
          "updated=%s" % bool(fc is not None and getattr(fc, "updated", False)))
    check("F 截止时间没变",
          fc is not None and same_deadline(getattr(fc, "deadline", None), before),
          "实际=%s，原值=%s" % (deadline_text(getattr(fc, "deadline", None) if fc is not None else None), before))

    # ---------- G. 群 B 的"刚才那条"不能删群 A 的卡 ----------
    print("\n== G. 只在本群找卡 ==")
    g = Pipeline(model)
    g.process(seq.msg(GID, GROUP, TEACHER, REPORT, T0))
    check("G 前提：群 A 出了 1 张实验报告的卡", len(cards_of(g)) == 1, "出了 %d 张" % len(cards_of(g)))
    gid = cards_of(g)[0].source_msg_id if cards_of(g) else ""
    g.process(seq.msg(GID_B, GROUP_B, TEACHER, "刚才那条作废", T0 + timedelta(minutes=1)))
    check("G 群 B 里说“刚才那条作废”，群 A 的卡还在",
          bool(gid) and find_card(g, gid) is not None, state(g, gid))

    # ---------- H. 24 小时后原发送者说“之前说的那个时间不作数”：不限时间 → 更正 ----------
    print("\n== H. 原发送者 + “之前”不限时间 ==")
    h = Pipeline(model)
    h.process(seq.msg("g-db", "数据库系统 2026秋", TEACHER, REPORT, T0))
    check("H 前提：老师布置实验报告出了 1 张卡", len(cards_of(h)) == 1, "出了 %d 张" % len(cards_of(h)))
    hid = cards_of(h)[0].source_msg_id if cards_of(h) else ""
    ht = T0 + timedelta(days=1)
    h.process(seq.msg("g-db", "数据库系统 2026秋", TEACHER,
                      "更正一下，作业截止明晚 6 点，之前说的那个时间不作数。", ht))
    hc = find_card(h, hid)
    want_h = (ht + timedelta(days=1)).replace(hour=18, minute=0, second=0, microsecond=0)
    check("H 第二天老师更正“之前说的那个时间不作数”，原卡被更新（updated=true、没有多出卡）",
          hc is not None and bool(getattr(hc, "updated", False)) and len(cards_of(h)) == 1,
          "卡数=%d，updated=%s" % (len(cards_of(h)), bool(hc is not None and getattr(hc, "updated", False))))
    check("H 截止时间改为更正消息次日的 18:00",
          hc is not None and same_deadline(getattr(hc, "deadline", None), want_h),
          "实际=%s，期望=%s" % (deadline_text(getattr(hc, "deadline", None) if hc is not None else None), want_h))

    # ---------- I. 第二天另一位老师同样的话：不是原发送者、也不提事项 → 不改 ----------
    print("\n== I. 非原发送者的更正不改 ==")
    i = Pipeline(model)
    i.process(seq.msg("g-db", "数据库系统 2026秋", TEACHER, REPORT, T0))
    check("I 前提：王老师布置实验报告出了 1 张卡", len(cards_of(i)) == 1, "出了 %d 张" % len(cards_of(i)))
    ic0 = cards_of(i)[0] if cards_of(i) else None
    iid = getattr(ic0, "source_msg_id", "") if ic0 is not None else ""
    before_i = getattr(ic0, "deadline", None) if ic0 is not None else None
    i.process(seq.msg("g-db", "数据库系统 2026秋", "张老师",
                      "更正一下，作业截止明晚 6 点，之前说的那个时间不作数。", T0 + timedelta(days=1)))
    ic = find_card(i, iid)
    check("I 第二天张老师同样说“之前说的那个时间不作数”，原卡没被改（updated=false）",
          ic is not None and not bool(getattr(ic, "updated", False)) and len(cards_of(i)) == 1,
          "卡数=%d，updated=%s" % (len(cards_of(i)), bool(ic is not None and getattr(ic, "updated", False))))
    check("I 截止时间没变",
          ic is not None and same_deadline(getattr(ic, "deadline", None), before_i),
          "实际=%s，原值=%s" % (deadline_text(getattr(ic, "deadline", None) if ic is not None else None), before_i))

    print("\n通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
