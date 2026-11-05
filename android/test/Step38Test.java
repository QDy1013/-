package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;

/**
 * 学习平台通知（学习通 / 知到 / 雨课堂）：放行、出卡、去重、改期、撤回、非待办过滤、持久化。
 * 样本是按平台常见写法构造的，拿到真机原文后应补充真实样本。
 */
public final class Step38Test {
    static int count;
    static final String XXT = "com.chaoxing.mobile";
    static final String ZD = "com.able.wisdomtree";
    static final String YKT = "com.xuetangx.ykt";

    static void check(String name, boolean ok, String detail) {
        if (!ok) throw new AssertionError(name + "：" + detail);
        count++;
        System.out.println("PASS " + name);
    }

    static long at(String s) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(s).getTime();
    }

    static Engine.Card find(Engine e, String pkg) {
        for (Engine.Card c : e.all()) if (pkg.equals(c.sourcePackage)) return c;
        return null;
    }

    static boolean feed(Engine e, String pkg, String title, String text, long ts) {
        String app = Engine.PlatformNotice.appName(pkg);
        return e.processPlatform(new Engine.Msg("p" + (title + text + ts).hashCode(), pkg + "|" + title, title, app, text, pkg, ts));
    }

    public static void main(String[] args) throws Exception {
        long now = at("2026-11-06 10:00");

        check("放行学习通", NotifFilter.accept(XXT), "false");
        check("放行知到", NotifFilter.accept(ZD), "false");
        check("放行雨课堂", NotifFilter.accept(YKT), "false");
        check("聊天软件照常放行", NotifFilter.accept("com.tencent.mobileqq"), "false");
        check("其他 App 仍然拦下", !NotifFilter.accept("com.netease.cloudmusic"), "true");

        Engine e;
        try (InputStream w = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            e = new Engine(w);
        }
        e.setProfile(Engine.Profile.demo());

        // ① 学习通发布作业
        feed(e, XXT, "作业通知", "【概率论与数理统计】第三次作业《第三章习题》已发布，截止时间：2026-11-12 23:59", now);
        Engine.Card c = find(e, XXT);
        check("学习通作业出卡", c != null && e.all().size() == 1, "卡数 " + e.all().size());
        check("标题取书名号里的事项名", "第三章习题".equals(c.title), c.title);
        check("课程名取【】", "概率论与数理统计".equals(c.groupName), c.groupName);
        check("截止时间", c.deadline == at("2026-11-12 23:59") && !c.needsConfirm, Engine.humanDeadline(c.deadline));
        check("来源显示学习通", "学习通".equals(c.sourceAppName()) && "学习通".equals(c.channel), c.sourceAppName());
        check("作业卡类型", "todo".equals(c.kind), c.kind);

        // ② 重复推送不出第二张
        feed(e, XXT, "作业通知", "【概率论与数理统计】第三次作业《第三章习题》已发布，截止时间：2026-11-12 23:59", now + 60_000);
        check("重复推送只留一张", e.all().size() == 1, "卡数 " + e.all().size());

        // ③ 延期：改原卡
        feed(e, XXT, "作业通知", "【概率论与数理统计】作业《第三章习题》截止时间延长至2026-11-14 23:59", now + 3600_000);
        c = find(e, XXT);
        check("延期改原卡", e.all().size() == 1 && c.deadline == at("2026-11-14 23:59") && c.updated,
                Engine.humanDeadline(c.deadline));

        // ④ 知到考试
        feed(e, ZD, "考试提醒", "《大学物理》期中考试将于2026-11-20 14:00开始，请准时参加", now);
        Engine.Card exam = find(e, ZD);
        check("知到考试出考试卡", exam != null && "exam".equals(exam.kind), exam == null ? "无卡" : exam.kind);
        check("考试时间", exam.deadline == at("2026-11-20 14:00"), Engine.humanDeadline(exam.deadline));
        check("考试标题含课程", exam.title.contains("大学物理") && exam.title.contains("考试"), exam.title);

        int before = e.all().size();
        // ⑤ 成绩 / 批改 / 签到 / 互动消息不出卡
        feed(e, XXT, "作业通知", "【概率论与数理统计】第二次作业已批改，得分92", now);
        feed(e, YKT, "雨课堂", "数据结构 老师发起了签到，请在2分钟内完成", now);
        feed(e, XXT, "学习通", "张三回复了你在讨论区的帖子", now);
        feed(e, ZD, "知到", "你的课程《大学物理》有新的学习资料", now);
        check("成绩、签到、互动、资料不出卡", e.all().size() == before, "卡数 " + before + "→" + e.all().size());

        // ⑥ 已过截止时间的不出卡
        feed(e, XXT, "作业通知", "【线性代数】作业《矩阵运算》截止时间：2026-11-01 23:59", now);
        check("截止已过不出卡", e.all().size() == before, "卡数 " + e.all().size());

        // ⑦ 雨课堂没写截止时间：出卡但标待确认
        feed(e, YKT, "雨课堂", "数据结构 老师布置了新作业：链表练习", now);
        Engine.Card ykt = find(e, YKT);
        check("雨课堂作业出卡", ykt != null, "无卡");
        check("冒号后的事项名", "链表练习".equals(ykt.title), ykt.title);
        check("没写时间标待确认", ykt.deadline == 0 && ykt.needsConfirm && ykt.reason.contains("雨课堂"), ykt.reason);

        // ⑧ 相对时间
        feed(e, XXT, "作业提醒", "【线性代数】作业《行列式计算》还有2小时截止", now);
        Engine.Card rel = null;
        // 事项名里没有“作业/习题”等字样时，标题前补上类型，卡片上一眼看出是什么。
        for (Engine.Card x : e.all()) if ("作业：行列式计算".equals(x.title)) rel = x;
        check("相对时间推算", rel != null && rel.deadline == now + 2 * 3600_000L && rel.needsConfirm,
                rel == null ? "无卡" : Engine.humanDeadline(rel.deadline));

        // ⑨ 持久化保留平台键：重启后重复推送仍去重
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        e.saveState(out);
        Engine e2;
        try (InputStream w = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            e2 = new Engine(w);
        }
        e2.loadState(new ByteArrayInputStream(out.toByteArray()));
        int n2 = e2.all().size();
        feed(e2, XXT, "作业通知", "【概率论与数理统计】第三次作业《第三章习题》已发布，截止时间：2026-11-12 23:59", now + 7200_000);
        check("重启后仍去重", e2.all().size() == n2, n2 + "→" + e2.all().size());

        // ⑩ 撤回
        feed(e2, XXT, "作业通知", "【概率论与数理统计】作业《第三章习题》已被老师撤回", now + 9000_000);
        boolean gone = true;
        for (Engine.Card x : e2.all()) if ("第三章习题".equals(x.title)) gone = false;
        check("撤回删卡", gone && e2.all().size() == n2 - 1, "卡数 " + e2.all().size());

        // ⑪ 移除后平台重推不顶回首页
        Engine.Card ex2 = find(e2, ZD);
        e2.dismissCard(ex2, now);
        int n3 = e2.all().size();
        feed(e2, ZD, "考试提醒", "《大学物理》期中考试将于2026-11-20 14:00开始，请准时参加", now + 60_000);
        check("移除后重推不回来", e2.all().size() == n3, n3 + "→" + e2.all().size());

        // ⑫ 聊天软件不走平台通道
        check("聊天消息不走平台解析", !e2.processPlatform(new Engine.Msg("x", "g", "群", "王老师", "明天交作业", "com.tencent.mobileqq", now)), "true");

        System.out.println("PASS " + count);
    }
}
