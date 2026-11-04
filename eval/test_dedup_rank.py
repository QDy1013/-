# -*- coding: utf-8 -*-
"""重复推送去重 + ranked 排序验收用例（Python 版）。

仿照 eval/test_exam_edge.py 的写法：MsgSeq、check、cards_of 等辅助函数照抄进
本文件，不 import 该模块。全程离线，时间戳固定：T0 = 2026-12-20 10:00。

只写测试，不改任何业务代码。断言按任务给定的规则 / 期望书写；本环境无 Shell，
测试未实际运行，不对其通过与否下结论。

场景一（D1~D4）：同一群里，消息文字去掉空格后与该群已有某张卡的 source_text
去掉空格后完全一样，就算重复推送，不再出新卡；去重只在同群内生效。
场景二：不走 process，直接构造 Card 放进 pipeline.cards，验证 ranked(limit, now)
的标题顺序（先接龙未过期，再按时间近的未过期，再没时间的，再已过期且最近
过期的在前）。Python 版无 done 概念，不测。
"""

import sys
from datetime import datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from engine.pipeline import Pipeline, Model, Card  # noqa: E402

GROUP = "高等数学2026秋"
GID = "g-math"
GROUP2 = "大学物理课程群"
GID2 = "g-phys"
TEACHER = "王老师"

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
    """跨场景连续编号，保证 msg_id 唯一。"""

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


def brief(cards):
    return "卡数=%d，类型=[%s]" % (len(cards), ",".join(c.kind for c in cards))


def mk_card(title, kind, deadline):
    """不走 process，直接构造 Card；其余必填字段随意填，revoked 用默认值 False。"""
    return Card(
        card_id="c-" + title,
        kind=kind,
        title=title,
        deadline=deadline,
        needs_confirm=False,
        channel=None,
        place=None,
        group_id=GID,
        group_name=GROUP,
        sender=TEACHER,
        source_msg_id="s-" + title,
        source_text=title,
        sent_at=T0,
        relevance=0.5,
        relevance_reason="",
        confidence=1.0,
    )


def main():
    model = Model(ROOT / "engine" / "model.json")
    seq = MsgSeq()

    # ---------- 场景一 D1 / D4：同群原样重复推送去重 ----------
    # D1：老师发作业通知，1 分钟后原样再发一次，应共 1 张卡。
    # D4（前提）：第一次发完后必须先 check 恰好出了 1 张卡，再 check 重复后的结果。
    print("== 场景一 D1/D4. 同一群原样重复推送去重 ==")
    p1 = Pipeline(model)
    t1 = "@所有人 周五18点前交实验报告到学习通"
    p1.process(seq.msg(GID, GROUP, TEACHER, t1, T0))
    pre1 = cards_of(p1)
    check("D4 前提：第一次发完后恰好 1 张卡", len(pre1) == 1, brief(pre1))
    p1.process(seq.msg(GID, GROUP, TEACHER, t1, T0 + timedelta(minutes=1)))
    after1 = cards_of(p1)
    check("D1 原样重复推送后共 1 张卡", len(after1) == 1, brief(after1))

    # ---------- 场景一 D2：同文字在两个不同群各发一次 ----------
    # 去重只在“同一群”内生效，两个群互不干扰，应各出 1 张，共 2 张卡。
    print("\n== 场景一 D2. 同文字分别在两个不同群各发一次 ==")
    p2 = Pipeline(model)
    p2.process(seq.msg(GID, GROUP, TEACHER, t1, T0))
    p2.process(seq.msg(GID2, GROUP2, TEACHER, t1, T0))
    c2 = cards_of(p2)
    check("D2 两个不同群各出 1 张，共 2 张卡", len(c2) == 2, brief(c2))

    # ---------- 场景一 D3：考试通知原样重复，只出 1 张 exam 卡 ----------
    print("\n== 场景一 D3. 考试通知原样重复只出 1 张 exam 卡 ==")
    p3 = Pipeline(model)
    t3 = "@所有人 下周三上午9点期中考试，教三201"
    p3.process(seq.msg(GID, GROUP, TEACHER, t3, T0))
    p3.process(seq.msg(GID, GROUP, TEACHER, t3, T0 + timedelta(minutes=1)))
    e3 = exam_cards(p3)
    check("D3 原样重复后共 1 张 exam 卡", len(e3) == 1, brief(cards_of(p3)))

    # ---------- 场景二：ranked(limit, now) 排序 ----------
    # 直接构造 Card 放进 pipeline.cards，不调用 process。
    # A 未过期 +24h；B 未过期 +2h；C 已过期 -24h；E 无时间 deadline=None；
    # F 接龙 jielong 未过期 +72h；G 接龙 jielong 已过期 -1h。now = T0。
    # 规则：先接龙未过期，再按时间近的未过期，再没时间的，
    #       再已过期（最近过期的在前）。
    print("\n== 场景二. ranked 排序（直接构造 Card，不走 process） ==")
    pr = Pipeline(model)
    now = T0
    pr.cards = [
        mk_card("A", "todo", now + timedelta(hours=24)),
        mk_card("B", "todo", now + timedelta(hours=2)),
        mk_card("C", "todo", now - timedelta(hours=24)),
        mk_card("E", "todo", None),
        mk_card("F", "jielong", now + timedelta(hours=72)),
        mk_card("G", "jielong", now - timedelta(hours=1)),
    ]
    titles10 = [c.title for c in pr.ranked(10, now)]
    check("ranked(10, now) 标题顺序为 F,B,A,E,G,C",
          titles10 == ["F", "B", "A", "E", "G", "C"],
          "实际=" + ",".join(titles10))
    titles3 = [c.title for c in pr.ranked(3, now)]
    check("ranked(3, now) 取前 3 个为 F,B,A",
          titles3 == ["F", "B", "A"],
          "实际=" + ",".join(titles3))

    print("\n通过 %d 项，失败 %d 项" % (pass_count, fail_count))
    if fail_count > 0:
        sys.exit(1)


if __name__ == "__main__":
    main()
