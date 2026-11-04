package com.nopalou;

import java.io.FileInputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;

/** 审查用探针：直接驱动 APK 里的 Engine，看几种真实场景下的行为。 */
public class Probe {

    static Engine fresh(String assets) throws Exception {
        return new Engine(new FileInputStream(assets + "/model.tsv"));
    }

    static void dump(String tag, Engine e) {
        System.out.println("  [" + tag + "] 卡片 " + e.all().size() + " 张");
        for (Engine.Card c : e.all())
            System.out.println("     · [" + c.kind + "] " + c.title + " | " + c.deadlineText + " | " + c.reason);
    }

    public static void main(String[] a) throws Exception {
        String assets = a[0];
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Calendar s = Calendar.getInstance();
        s.set(2026, Calendar.DECEMBER, 20, 10, 0, 0);
        s.set(Calendar.MILLISECOND, 0);
        long send = s.getTimeInMillis();

        System.out.println("== E. Java parseDeadline（发送时刻 2026-12-20 周日 10:00）==");
        String[] exprs = {"明晚八点前交实验报告", "明天下午3点半前交", "周五晚上8点30分前提交",
                "1月10日前交期末报告", "作业要求2-3页，周五前交", "明早8点交，晚了不收"};
        for (String x : exprs) {
            Engine.Deadline d = Engine.parseDeadline(x, send);
            System.out.println("  " + x + " → " + (d.value == 0 ? "无" : f.format(d.value)) + "  [" + d.conf + "]");
        }

        long now = System.currentTimeMillis();

        System.out.println("\n== F. 老师发带编号的作业清单 ==");
        Engine e = fresh(assets);
        e.process(new Engine.Msg("1", "g", "计科 2301 班群", "王老师",
                "本周作业：\n1. 完成习题 3.1\n2. 预习第四章\n3. 周五 18 点前提交到学习通", "t", now));
        dump("编号清单", e);

        System.out.println("\n== G. 先出一张作业卡，随后有同学聊天提到“取消” ==");
        e = fresh(assets);
        e.process(new Engine.Msg("1", "g", "计科 2301 班群", "王老师", "@所有人 周五 18 点前交实验报告到学习通", "t", now));
        dump("通知后", e);
        e.process(new Engine.Msg("2", "g", "计科 2301 班群", "陈嘉禾", "我把今晚的外卖取消了", "t", now + 60_000));
        dump("聊天后", e);

        System.out.println("\n== H. 真实用户（不是李承宇）的课程群 ==");
        e = fresh(assets);
        e.process(new Engine.Msg("1", "g", "高等数学课程群", "张老师", "@所有人 周五 18 点前交第三章作业到学习通", "t", now));
        dump("高数群", e);

        System.out.println("\n== I. 接龙被大家一条条往下接（微信接龙每人接一次就重发整条） ==");
        e = fresh(assets);
        String head = "#接龙\n体测报名（周五18点截止）\n";
        e.process(new Engine.Msg("1", "g", "计科 2301 班群", "班长", head + "1. 张三", "t", now));
        e.process(new Engine.Msg("2", "g", "计科 2301 班群", "李四", head + "1. 张三\n2. 李四", "t", now + 1000));
        e.process(new Engine.Msg("3", "g", "计科 2301 班群", "王五", head + "1. 张三\n2. 李四\n3. 王五", "t", now + 2000));
        dump("三次接龙后", e);
        e.process(new Engine.Msg("4", "g", "计科 2301 班群", "李承宇", head + "1. 张三\n2. 李四\n3. 王五\n4. 李承宇", "t", now + 3000));
        dump("“我”也接了以后", e);

        System.out.println("\n== J. NotifListener 拆发送者的逻辑（照抄源码） ==");
        String[] texts = {"王老师：周五18:00前交实验报告", "王老师: 周五前交实验报告", "[3条]王老师: 周五前交"};
        for (String text : texts) {
            String sender = "群友", body = text;
            int cut = Math.max(text.indexOf('：'), text.indexOf(':'));
            if (cut > 0 && cut <= 12) { sender = text.substring(0, cut); body = text.substring(cut + 1).trim(); }
            System.out.println("  「" + text + "」 → sender=「" + sender + "」 body=「" + body + "」");
        }
    }
}
