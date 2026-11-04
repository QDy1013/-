package com.nopalou;

import java.io.FileInputStream;

/**
 * 真机问题的验收用例：通知来源过滤 + 群编号。
 *
 * 真机现象：NotifListener 直接拿 sbn.getPackageName() 当 groupId，于是同一个 App 里
 * 所有群被当成一个群；而且手机上所有 App 的通知都被读了进来。
 *
 * 规则（本次测试按新规则写，NotifFilter 下一步再建）：
 *   1. accept(pkg)：只收群聊类 App（微信 / QQ / TIM / 钉钉 / 飞书 / 回放器），
 *      系统界面、推送服务、音乐、本 App 自己、null、"" 一律不收；
 *   2. groupId(pkg, 群名)：同一 App 不同群名 → 不同编号；不同 App 同群名 → 不同编号；
 *      群名前后空格不影响；群名为空或 null 也不能炸。
 *
 * 只测对外行为：返回值、卡片在不在、updated 标志、deadline 数值。全程离线，时间戳固定。
 */
public class Step8Test {

    static int pass = 0, fail = 0;
    static String model;
    static long t0 = 1_766_000_000_000L;
    static int seq = 0;

    static final String PKG = "com.nopalou.replay";
    static final String PKG_WX = "com.tencent.mm";
    static final String PKG_QQ = "com.tencent.mobileqq";
    static final String GROUP_DB = "数据库系统 2026秋";
    static final String GROUP_NET = "计算机网络 2026秋";
    static final String TEACHER_WANG = "王老师";
    static final String TEACHER_LIU = "刘老师";

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
        return new Engine.Msg("m" + seq, gid, gname, sender, text, PKG, ts);
    }

    /** 先按 groupId 找，找不到再按标题关键词找，避免引擎内部实现差异导致误判。 */
    static Engine.Card card(Engine e, String gid, String titleKey) {
        for (Engine.Card c : e.all()) if (gid != null && gid.equals(c.groupId)) return c;
        for (Engine.Card c : e.all()) if (c.title != null && c.title.contains(titleKey)) return c;
        return null;
    }

    static String state(Engine e, Engine.Card c) {
        return "卡数=" + e.all().size() + "，该卡" + (c == null ? "不在了" : "还在");
    }

    public static void main(String[] args) throws Exception {
        model = args[0] + "/app/assets/model.tsv";

        // ---------- A. accept：群聊类 App 才收 ----------
        System.out.println("== A. accept 只收群聊类 App ==");
        String[] yes = {PKG_WX, PKG_QQ, "com.tencent.tim", "com.alibaba.android.rimet",
                "com.ss.android.lark", PKG};
        String[] yesName = {"微信", "QQ", "TIM", "钉钉", "飞书", "回放器"};
        for (int i = 0; i < yes.length; i++) {
            check("A accept(" + yes[i] + ") 为 true（" + yesName[i] + "）",
                    NotifFilter.accept(yes[i]), "返回 false");
        }
        String[] no = {"com.android.systemui", "com.vivo.pushservice", "com.netease.cloudmusic",
                "com.nopalou", "", null};
        String[] noName = {"系统界面", "vivo 推送服务", "网易云音乐", "本 App 自己", "空串", "null"};
        for (int i = 0; i < no.length; i++) {
            check("A accept(" + (no[i] == null ? "null" : "\"" + no[i] + "\"") + ") 为 false（" + noName[i] + "）",
                    !NotifFilter.accept(no[i]), "返回 true");
        }

        // ---------- B. groupId：同 App 分群、不同 App 分开 ----------
        System.out.println("\n== B. groupId 分得开 ==");
        check("B 同 App 同群名 → 编号相同",
                NotifFilter.groupId(PKG_WX, GROUP_DB).equals(NotifFilter.groupId(PKG_WX, GROUP_DB)),
                "两次结果不同：" + NotifFilter.groupId(PKG_WX, GROUP_DB));
        check("B 同 App 不同群名 → 编号不同",
                !NotifFilter.groupId(PKG_WX, GROUP_DB).equals(NotifFilter.groupId(PKG_WX, GROUP_NET)),
                "都算成了 " + NotifFilter.groupId(PKG_WX, GROUP_DB));
        check("B 不同 App 同群名 → 编号不同",
                !NotifFilter.groupId(PKG_WX, GROUP_DB).equals(NotifFilter.groupId(PKG_QQ, GROUP_DB)),
                "都算成了 " + NotifFilter.groupId(PKG_WX, GROUP_DB));
        check("B 群名前后空格不影响",
                NotifFilter.groupId(PKG_WX, "  " + GROUP_DB + "  ").equals(NotifFilter.groupId(PKG_WX, GROUP_DB)),
                "带空格=" + NotifFilter.groupId(PKG_WX, "  " + GROUP_DB + "  ")
                        + "，不带=" + NotifFilter.groupId(PKG_WX, GROUP_DB));

        String emptyId = null;
        String emptyErr = "";
        try { emptyId = NotifFilter.groupId(PKG_WX, ""); }
        catch (Throwable t) { emptyErr = t.getClass().getSimpleName(); }
        check("B 群名为空串不抛异常、返回非空",
                emptyErr.isEmpty() && emptyId != null && !emptyId.isEmpty(),
                emptyErr.isEmpty() ? "返回=" + emptyId : "抛了 " + emptyErr);

        String nullId = null;
        String nullErr = "";
        try { nullId = NotifFilter.groupId(PKG_WX, null); }
        catch (Throwable t) { nullErr = t.getClass().getSimpleName(); }
        check("B 群名为 null 不抛异常、返回非空",
                nullErr.isEmpty() && nullId != null && !nullId.isEmpty(),
                nullErr.isEmpty() ? "返回=" + nullId : "抛了 " + nullErr);

        // ---------- C. 真机场景：两个群同属一个 App，更正只动本群那张卡 ----------
        System.out.println("\n== C. 同 App 两个群，更正只改对得上的那张 ==");
        Engine e = engine();
        String gidDb = NotifFilter.groupId(PKG, GROUP_DB);
        String gidNet = NotifFilter.groupId(PKG, GROUP_NET);
        check("C 前提：两个群的编号不一样", !gidDb.equals(gidNet), "都算成了 " + gidDb);

        e.process(msg(gidDb, GROUP_DB, TEACHER_WANG, "明晚 8 点前交实验报告，交到学习通。", t0));
        e.process(msg(gidNet, GROUP_NET, TEACHER_LIU, "下周五 18 点前交读书报告，交到邮箱。", t0 + 60_000L));
        Engine.Card dbCard = card(e, gidDb, "实验报告");
        Engine.Card netCard = card(e, gidNet, "读书报告");
        check("C 前提：数据库群出了实验报告的卡",
                dbCard != null && dbCard.title != null && dbCard.title.contains("实验报告"),
                "卡数=" + e.all().size() + "，该卡" + (dbCard == null ? "没出" : "标题=" + dbCard.title));
        check("C 前提：计算机网络群出了读书报告的卡",
                netCard != null && netCard.title != null && netCard.title.contains("读书报告"),
                "卡数=" + e.all().size() + "，该卡" + (netCard == null ? "没出" : "标题=" + netCard.title));
        check("C 前提：两个群各出一张，没有被当成同一个群合并", e.all().size() == 2,
                "出了 " + e.all().size() + " 张");

        long netDeadlineBefore = netCard == null ? 0 : netCard.deadline;
        String netTextBefore = netCard == null ? "" : netCard.deadlineText;

        e.process(msg(gidDb, GROUP_DB, TEACHER_WANG,
                "更正一下，实验报告截止改到下周一 22 点，之前说的那个时间不作数。", t0 + 24 * 3600_000L));

        Engine.Card dbAfter = card(e, gidDb, "实验报告");
        check("C 王老师在数据库群更正实验报告截止，实验报告的卡被更新（updated=true）",
                dbAfter != null && dbAfter.updated, "updated=" + (dbAfter != null && dbAfter.updated));
        check("C 更正没有多出卡", e.all().size() == 2, "卡数=" + e.all().size());

        Engine.Card netAfter = card(e, gidNet, "读书报告");
        check("C 计算机网络群的读书报告卡没被改（updated=false）",
                netAfter != null && !netAfter.updated,
                netAfter == null ? state(e, netAfter) : "updated=" + netAfter.updated);
        check("C 读书报告的截止时间没变",
                netAfter != null && netAfter.deadline == netDeadlineBefore,
                "实际=" + (netAfter == null ? "无卡" : String.valueOf(netAfter.deadline))
                        + "，原值=" + netDeadlineBefore
                        + "，原文=" + netTextBefore);

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
