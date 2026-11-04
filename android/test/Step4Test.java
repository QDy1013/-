package com.nopalou;

import java.io.FileInputStream;
import java.util.Calendar;

/**
 * 审查意见第 5 条的验收用例：撤销 / 更正只作用于"对得上"的那张卡。
 *
 * 规则（本次测试按新规则写，Engine 下一步再改）：
 *   1. 发送者必须是那张卡的原发送者，或名字含 老师/辅导员/班长/课代表 等；
 *   2. 必须能定位到具体那张卡（只在同一群里找）：
 *      - 原发送者本人 + 指代词"之前/上条/上面/前面"时不限时间，找此人在本群最新的一张卡；
 *      - 不是原发送者时，仍要求事项匹配（与标题/原文有共同关键词），或"刚才"类指代词且 30 分钟内；
 *      - 只有"刚才"受 30 分钟限制。
 *   两条都满足才生效，对不上就什么都不做。
 *
 * 只测对外行为：卡片还在不在、updated 标志、deadline 数值。全程离线，时间戳固定。
 */
public class Step4Test {

    static int pass = 0, fail = 0;
    static String model;
    static long t0 = 1_766_000_000_000L;
    static int seq = 0;

    static final String GROUP = "大学物理课程群";
    static final String GROUP_B = "数据库系统课程群";
    static final String GID = "g-physics";
    static final String GID_B = "g-db";
    static final String TEACHER = "王老师";
    static final String STUDENT = "陈嘉禾";
    static final String REPORT = "@所有人 周五 18 点前交实验报告到学习通";

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name + "   " + detail); }
    }

    static Engine engine() throws Exception {
        Engine e = new Engine(new FileInputStream(model));
        e.setProfile(Engine.Profile.demo());
        return e;
    }

    static Engine.Msg msg(String gid, String gname, String sender, String text, long ts) {
        seq++;
        return new Engine.Msg("m" + seq, gid, gname, sender, text, "test", ts);
    }

    static Engine.Card byId(Engine e, String id) {
        for (Engine.Card c : e.all()) if (id.equals(c.id)) return c;
        return null;
    }

    static String state(Engine e, String id) {
        return "卡数=" + e.all().size() + "，该卡" + (byId(e, id) == null ? "不在了" : "还在");
    }

    static long dayStart(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 周一=0，与 Engine 的 weekday() 同口径。 */
    static int weekdayMon0(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    /** "下周一" 的 hh:mm（本周之后再下一周的周一），按消息发出的本地日期算。 */
    static long nextWeekMondayAt(long ts, int hh, int mm) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dayStart(ts));
        c.add(Calendar.DAY_OF_MONTH, 7 - weekdayMon0(ts));
        c.set(Calendar.HOUR_OF_DAY, hh);
        c.set(Calendar.MINUTE, mm);
        return c.getTimeInMillis();
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";

        // ---------- A. 同学说"取消外卖"，不该动老师的作业卡 ----------
        System.out.println("== A. 无关的人说取消 ==");
        Engine a = engine();
        a.process(msg(GID, GROUP, TEACHER, REPORT, t0));
        check("A 前提：老师布置实验报告出了 1 张卡", a.all().size() == 1, "出了 " + a.all().size() + " 张");
        String aid = a.all().isEmpty() ? "" : a.all().get(0).id;
        a.process(msg(GID, GROUP, STUDENT, "我把今晚的外卖取消了", t0 + 60_000L));
        Engine.Card ac = byId(a, aid);
        check("A 同学说“我把今晚的外卖取消了”，实验报告的卡不动", !aid.isEmpty() && ac != null, state(a, aid));

        // ---------- B. 老师说"实验课取消"，不该误删两张无关的卡 ----------
        System.out.println("\n== B. 老师的取消对不上事项 ==");
        Engine b = engine();
        b.process(msg("g-class", "计科 2301 班群", TEACHER,
                "@所有人 周五 18 点前填选课意向表，教务系统里填", t0));
        b.process(msg("g-class", "计科 2301 班群", TEACHER,
                "@所有人 周日 22 点前完成课后习题第 3 章，提交到学习通", t0 + 60_000L));
        check("B 前提：两条通知出了 2 张卡", b.all().size() == 2, "出了 " + b.all().size() + " 张");
        String b1 = b.all().size() > 0 ? b.all().get(0).id : "";
        String b2 = b.all().size() > 1 ? b.all().get(1).id : "";
        Engine.Card c1 = byId(b, b1), c2 = byId(b, b2);
        check("B 前提：两张卡分别是选课意向表和课后习题",
                c1 != null && c2 != null && c1.title.contains("选课意向表") && c2.title.contains("课后习题"),
                "标题=" + (c1 == null ? "无" : c1.title) + " / " + (c2 == null ? "无" : c2.title));
        b.process(msg("g-class", "计科 2301 班群", TEACHER, "这周实验课取消", t0 + 120_000L));
        check("B 老师说“这周实验课取消”，选课意向表的卡还在", !b1.isEmpty() && byId(b, b1) != null, state(b, b1));
        check("B 老师说“这周实验课取消”，课后习题的卡还在", !b2.isEmpty() && byId(b, b2) != null, state(b, b2));

        // ---------- C. 老师 5 分钟后"刚才那条作废"：指代词 + 30 分钟内 → 生效 ----------
        System.out.println("\n== C. 原发送者 + 指代词 + 5 分钟 ==");
        Engine c = engine();
        c.process(msg(GID, GROUP, TEACHER, REPORT, t0));
        check("C 前提：老师布置实验报告出了 1 张卡", c.all().size() == 1, "出了 " + c.all().size() + " 张");
        String cid = c.all().isEmpty() ? "" : c.all().get(0).id;
        c.process(msg(GID, GROUP, TEACHER, "刚才那条作废", t0 + 5 * 60_000L));
        check("C 5 分钟后老师说“刚才那条作废”，实验报告的卡被删",
                !cid.isEmpty() && byId(c, cid) == null, state(c, cid));

        // ---------- D. 同样的话但过了 2 小时、也没提事项：对不上 → 不生效 ----------
        System.out.println("\n== D. 指代词超时 ==");
        Engine d = engine();
        d.process(msg(GID, GROUP, TEACHER, REPORT, t0));
        check("D 前提：老师布置实验报告出了 1 张卡", d.all().size() == 1, "出了 " + d.all().size() + " 张");
        String did = d.all().isEmpty() ? "" : d.all().get(0).id;
        d.process(msg(GID, GROUP, TEACHER, "刚才那条作废", t0 + 2 * 3600_000L));
        check("D 2 小时后老师说“刚才那条作废”且没提事项，实验报告的卡还在",
                !did.isEmpty() && byId(d, did) != null, state(d, did));

        // ---------- E. 同一位老师提到事项更正截止：更新原卡 ----------
        System.out.println("\n== E. 更正截止时间 ==");
        Engine e = engine();
        e.process(msg(GID, GROUP, TEACHER, REPORT, t0));
        check("E 前提：老师布置实验报告出了 1 张卡", e.all().size() == 1, "出了 " + e.all().size() + " 张");
        String eid = e.all().isEmpty() ? "" : e.all().get(0).id;
        long t1 = t0 + 24 * 3600_000L;
        e.process(msg(GID, GROUP, TEACHER, "更正：实验报告截止改到下周一 22 点", t1));
        Engine.Card ec = byId(e, eid);
        long want = nextWeekMondayAt(t1, 22, 0);
        check("E 第二天老师更正“实验报告”截止，原卡被更新（updated=true、没有多出卡）",
                ec != null && ec.updated && e.all().size() == 1,
                "卡数=" + e.all().size() + "，updated=" + (ec != null && ec.updated));
        check("E 截止时间改到下周一 22:00",
                ec != null && ec.deadline == want,
                "实际=" + (ec == null ? "无卡" : ec.deadline + "（" + Engine.humanDeadline(ec.deadline) + "）")
                        + "，期望=" + want);

        // ---------- F. 同学用"改成"提问：不是原发送者 → 不改 ----------
        System.out.println("\n== F. 无关的人提到事项也不改 ==");
        Engine f = engine();
        f.process(msg(GID, GROUP, TEACHER, REPORT, t0));
        check("F 前提：老师布置实验报告出了 1 张卡", f.all().size() == 1, "出了 " + f.all().size() + " 张");
        String fid = f.all().isEmpty() ? "" : f.all().get(0).id;
        long before = f.all().isEmpty() ? 0 : f.all().get(0).deadline;
        f.process(msg(GID, GROUP, STUDENT, "实验报告改成周一交了吗", t0 + 3 * 60_000L));
        Engine.Card fc = byId(f, fid);
        check("F 同学问“实验报告改成周一交了吗”，原卡没被改（updated=false）",
                fc != null && !fc.updated, "updated=" + (fc != null && fc.updated));
        check("F 截止时间没变", fc != null && fc.deadline == before,
                "实际=" + (fc == null ? "无卡" : String.valueOf(fc.deadline)) + "，原值=" + before);

        // ---------- G. 群 B 的"刚才那条"不能删群 A 的卡 ----------
        System.out.println("\n== G. 只在本群找卡 ==");
        Engine g = engine();
        g.process(msg(GID, GROUP, TEACHER, REPORT, t0));
        check("G 前提：群 A 出了 1 张实验报告的卡", g.all().size() == 1, "出了 " + g.all().size() + " 张");
        String gid = g.all().isEmpty() ? "" : g.all().get(0).id;
        g.process(msg(GID_B, GROUP_B, TEACHER, "刚才那条作废", t0 + 60_000L));
        check("G 群 B 里说“刚才那条作废”，群 A 的卡还在",
                !gid.isEmpty() && byId(g, gid) != null, state(g, gid));

        // ---------- H. 24 小时后原发送者说“之前说的那个时间不作数”：不限时间 → 更正 ----------
        System.out.println("\n== H. 原发送者 + “之前”不限时间 ==");
        Engine h = engine();
        h.process(msg("g-db", "数据库系统 2026秋", TEACHER, REPORT, t0));
        check("H 前提：老师布置实验报告出了 1 张卡", h.all().size() == 1, "出了 " + h.all().size() + " 张");
        String hid = h.all().isEmpty() ? "" : h.all().get(0).id;
        long ht = t0 + 24 * 3600_000L;
        h.process(msg("g-db", "数据库系统 2026秋", TEACHER,
                "更正一下，作业截止明晚 6 点，之前说的那个时间不作数。", ht));
        Engine.Card hc = byId(h, hid);
        Calendar hcal = Calendar.getInstance();
        hcal.setTimeInMillis(ht);
        hcal.add(Calendar.DAY_OF_MONTH, 1);
        hcal.set(Calendar.HOUR_OF_DAY, 18);
        hcal.set(Calendar.MINUTE, 0);
        hcal.set(Calendar.SECOND, 0);
        hcal.set(Calendar.MILLISECOND, 0);
        long wantH = hcal.getTimeInMillis();
        check("H 第二天老师更正“之前说的那个时间不作数”，原卡被更新（updated=true、没有多出卡）",
                hc != null && hc.updated && h.all().size() == 1,
                "卡数=" + h.all().size() + "，updated=" + (hc != null && hc.updated));
        check("H 截止时间改为更正消息次日的 18:00",
                hc != null && hc.deadline == wantH,
                "实际=" + (hc == null ? "无卡" : hc.deadline + "（" + Engine.humanDeadline(hc.deadline) + "）")
                        + "，期望=" + wantH);

        // ---------- I. 第二天另一位老师同样的话：不是原发送者、也不提事项 → 不改 ----------
        System.out.println("\n== I. 非原发送者的更正不改 ==");
        Engine i = engine();
        i.process(msg("g-db", "数据库系统 2026秋", TEACHER, REPORT, t0));
        check("I 前提：王老师布置实验报告出了 1 张卡", i.all().size() == 1, "出了 " + i.all().size() + " 张");
        String iid = i.all().isEmpty() ? "" : i.all().get(0).id;
        Engine.Card ibefore = byId(i, iid);
        long ibeforeDl = ibefore == null ? 0 : ibefore.deadline;
        i.process(msg("g-db", "数据库系统 2026秋", "张老师",
                "更正一下，作业截止明晚 6 点，之前说的那个时间不作数。", t0 + 24 * 3600_000L));
        Engine.Card ic = byId(i, iid);
        check("I 第二天张老师同样说“之前说的那个时间不作数”，原卡没被改（updated=false）",
                ic != null && !ic.updated && i.all().size() == 1,
                "卡数=" + i.all().size() + "，updated=" + (ic != null && ic.updated));
        check("I 截止时间没变", ic != null && ic.deadline == ibeforeDl,
                "实际=" + (ic == null ? "无卡" : String.valueOf(ic.deadline)) + "，原值=" + ibeforeDl);

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
