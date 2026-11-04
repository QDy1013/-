package com.nopalou;

import java.io.FileInputStream;

/**
 * 审查意见第 6 条的验收用例：接龙去重 / 接过就撤卡 / 不把作业清单当接龙。
 *
 * 规则（本次测试按新规则写，Engine 下一步再改）：
 *   1. 同一群里同一接龙标题（#接龙 下面第一行）只保留一张卡，重发只更新这张卡；
 *   2. 某次重发的名单里出现我（档案姓名 / 别名 / 学号），这个接龙的卡就删掉；
 *   3. 只有消息含 "#接龙" 或 "接龙" 二字才算接龙，仅靠编号行不算。
 *
 * 只测对外行为：卡片数量、kind、标题。全程离线，时间戳固定，每条间隔 1 分钟。
 */
public class Step5Test {

    static int pass = 0, fail = 0;
    static String model;
    static long t0 = 1_766_000_000_000L;
    static int seq = 0;

    static final String GROUP = "计科2301体育群";
    static final String GROUP_B = "计科2301班服群";
    static final String GID = "g-pe";
    static final String GID_B = "g-cloth";
    static final String STUDENT = "陈嘉禾";
    static final String TEACHER = "王老师";

    static final String JL1 = "#接龙\n体测报名（周五18点截止）\n1. 周若曦";
    static final String JL2 = "#接龙\n体测报名（周五18点截止）\n1. 周若曦\n2. 郑浩然";
    static final String JL3 = "#接龙\n体测报名（周五18点截止）\n1. 周若曦\n2. 郑浩然\n3. 孙语桐";
    static final String JL4 = "#接龙\n体测报名（周五18点截止）\n1. 周若曦\n2. 郑浩然\n3. 孙语桐\n4. 李承宇";

    static final String CLOTH = "#接龙\n班服尺码统计\n1. 周若曦";

    /** 老师的作业清单：有编号行，但不是接龙。 */
    static final String HOMEWORK = "本周作业：\n1. 完成习题 3.1\n2. 预习第四章\n3. 周五 18 点前提交";

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

    static int countJL(Engine e, String gid) {
        int n = 0;
        for (Engine.Card c : e.all()) {
            if ("jielong".equals(c.kind) && gid.equals(c.groupId)) n++;
        }
        return n;
    }

    static int countJL(Engine e) {
        int n = 0;
        for (Engine.Card c : e.all()) if ("jielong".equals(c.kind)) n++;
        return n;
    }

    static Engine.Card firstJL(Engine e, String gid) {
        for (Engine.Card c : e.all()) {
            if ("jielong".equals(c.kind) && gid.equals(c.groupId)) return c;
        }
        return null;
    }

    static boolean hasId(Engine e, String id) {
        for (Engine.Card c : e.all()) if (id.equals(c.id)) return true;
        return false;
    }

    static String detail(Engine e, String gid) {
        StringBuilder b = new StringBuilder("卡数=" + e.all().size() + "，本群接龙卡=" + countJL(e, gid)
                + "，标题=[");
        for (Engine.Card c : e.all()) {
            if ("jielong".equals(c.kind) && gid.equals(c.groupId)) b.append(c.title).append(" | ");
        }
        return b.append("]").toString();
    }

    /** 演示档案里的"我"叫这个名字，B / E 依赖它。 */
    static final String ME = Engine.Profile.demo().name;

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";

        // ---------- A. 同标题重发三次：只该有 1 张接龙卡 ----------
        System.out.println("== A. 同一接龙重发不重复出卡 ==");
        Engine a = engine();
        a.process(msg(GID, GROUP, STUDENT, JL1, t0));
        check("A 前提：第一条接龙出了 1 张接龙卡", countJL(a, GID) == 1, detail(a, GID));
        Engine.Card ac1 = firstJL(a, GID);
        check("A 前提：卡 kind=jielong", ac1 != null && "jielong".equals(ac1.kind),
                "kind=" + (ac1 == null ? "无卡" : ac1.kind));
        check("A 前提：标题含“还没接”", ac1 != null && ac1.title != null && ac1.title.contains("还没接"),
                "标题=" + (ac1 == null ? "无卡" : ac1.title));
        String aid = ac1 == null ? "" : ac1.id;

        a.process(msg(GID, GROUP, STUDENT, JL2, t0 + 60_000L));
        check("A 第 2 个人接上后，本群仍只有 1 张接龙卡", countJL(a, GID) == 1, detail(a, GID));
        a.process(msg(GID, GROUP, STUDENT, JL3, t0 + 2 * 60_000L));
        check("A 第 3 个人接上后，本群仍只有 1 张接龙卡", countJL(a, GID) == 1, detail(a, GID));
        Engine.Card ac3 = firstJL(a, GID);
        check("A 三次重发后那张卡 kind=jielong 且标题仍含“还没接”",
                ac3 != null && "jielong".equals(ac3.kind)
                        && ac3.title != null && ac3.title.contains("还没接"),
                "标题=" + (ac3 == null ? "无卡" : ac3.title));

        // ---------- B. 名单里出现我：这张接龙卡删掉 ----------
        System.out.println("\n== B. 重发名单里出现我 ==");
        Engine b = engine();
        b.process(msg(GID, GROUP, STUDENT, JL1, t0));
        b.process(msg(GID, GROUP, STUDENT, JL2, t0 + 60_000L));
        b.process(msg(GID, GROUP, STUDENT, JL3, t0 + 2 * 60_000L));
        check("B 前提：接到 3 人时本群还有 1 张接龙卡", countJL(b, GID) == 1, detail(b, GID));
        String bid = firstJL(b, GID) == null ? "" : firstJL(b, GID).id;
        b.process(msg(GID, GROUP, STUDENT, JL4, t0 + 3 * 60_000L));
        check("B 名单里加上我（" + ME + "）后，本群接龙卡为 0 张",
                countJL(b, GID) == 0, detail(b, GID));
        check("B 原来那张接龙卡（id=" + bid + "）确实被删了",
                !bid.isEmpty() && !hasId(b, bid), detail(b, GID));

        // ---------- C. 去重按“群 + 标题”，不是按条数 ----------
        System.out.println("\n== C. 不同标题 / 不同群各算各的 ==");
        Engine c1 = engine();
        c1.process(msg(GID, GROUP, STUDENT, JL3, t0));
        c1.process(msg(GID, GROUP, STUDENT, CLOTH, t0 + 60_000L));
        check("C 同群两个不同标题的接龙 → 2 张接龙卡",
                countJL(c1, GID) == 2, detail(c1, GID));

        Engine c2 = engine();
        c2.process(msg(GID, GROUP, STUDENT, JL3, t0));
        c2.process(msg(GID_B, GROUP_B, STUDENT, JL3, t0 + 60_000L));
        check("C 两个群里同标题的接龙 → 各 1 张",
                countJL(c2, GID) == 1 && countJL(c2, GID_B) == 1 && countJL(c2) == 2,
                "A 群=" + countJL(c2, GID) + "，B 群=" + countJL(c2, GID_B) + "，总数=" + countJL(c2));

        // ---------- D. 老师的作业清单：只有编号行，不是接龙 ----------
        System.out.println("\n== D. 作业清单不算接龙 ==");
        Engine d = engine();
        check("D 前提：这条消息确实带编号行",
                HOMEWORK.contains("\n1. ") && HOMEWORK.contains("\n3. "), "文本=" + HOMEWORK);
        d.process(msg(GID, GROUP, TEACHER, HOMEWORK, t0));
        check("D 老师的“本周作业”清单没有 kind=jielong 的卡",
                countJL(d) == 0, detail(d, GID));

        // ---------- E. 一开始名单里就有我：不出接龙卡 ----------
        System.out.println("\n== E. 名单里一开始就有我 ==");
        Engine e = engine();
        check("E 前提：演示档案里的我是“" + ME + "”", "李承宇".equals(ME), "档案姓名=" + ME);
        e.process(msg(GID, GROUP, STUDENT, JL4, t0));
        check("E 名单里一开始就有我，接龙卡为 0 张", countJL(e) == 0, detail(e, GID));

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
