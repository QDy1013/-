package com.nopalou;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 防止普通报名通知被错分为接龙，并覆盖通知正文中的冒号和换行。 */
public final class Step12Test {
    private static final long SENT = LocalDateTime.of(2027, 3, 22, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    private static final long DUE = LocalDateTime.of(2027, 3, 23, 22, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static InputStream model(String android) throws Exception {
        return Files.newInputStream(Paths.get(android, "app", "assets", "model.tsv"));
    }

    public static void main(String[] args) throws Exception {
        Engine engine;
        try (InputStream model = model(args[0])) {
            engine = new Engine(model);
        }
        engine.setProfile(Engine.Profile.demo());
        engine.process(new Engine.Msg("date", "g", "班级群", "班长",
                "3.23 22:00 前报名体测（班级接龙）。", "com.tencent.mm", SENT));
        check("日期开头的报名提醒走普通待办，并保留截止和渠道",
                engine.all().stream().anyMatch(c -> "date".equals(c.sourceId)
                        && "todo".equals(c.kind) && c.deadline == DUE && "班级接龙".equals(c.channel)));
        check("日期不充当接龙序号",
                engine.all().stream().noneMatch(c -> "jielong".equals(c.kind)));

        String roster = "体测报名接龙（明晚22:00截止）\r\n1.周若曦";
        engine.process(new Engine.Msg("roster", "g", "班级群", "班长", roster, "com.tencent.mm", SENT));
        check("没有井号、带真实编号的接龙仍出提醒",
                engine.all().stream().anyMatch(c -> "roster".equals(c.sourceId) && "jielong".equals(c.kind)));
        engine.process(new Engine.Msg("joined", "g", "班级群", "班长",
                roster + "\r\n2.20230137", "com.tencent.mm", SENT + 1000));
        check("名单新增本人的学号后撤掉接龙卡",
                engine.all().stream().noneMatch(c -> "jielong".equals(c.kind)));

        String notification = "王老师：明天9:00期中考试\n地点：教二305\n带：学生证";
        int cut = NotifFilter.senderSeparator(notification);
        check("混用中英文冒号时保留发送者、时间和全部正文换行", cut == 3
                && "王老师".equals(notification.substring(0, cut))
                && notification.substring(cut + 1).equals("明天9:00期中考试\n地点：教二305\n带：学生证"));
        check("半角发送者分隔符仍可用", NotifFilter.senderSeparator("王老师:明天考试") == 3);
        check("无发送者时不把钟点冒号当分隔符", NotifFilter.senderSeparator("9:00前提交实验报告") == -1);
        check("正文第二行的冒号不会变成发送者分隔符", NotifFilter.senderSeparator("考试通知\n地点：教二305") == -1);
        engine.process(new Engine.Msg("exam", "course", "数据库系统课程群", notification.substring(0, cut),
                notification.substring(cut + 1), "com.tencent.mm", SENT));
        check("真实入口格式经过解析后能生成带地点和物品的考试卡",
                engine.all().stream().anyMatch(c -> "exam".equals(c.kind) && "exam".equals(c.sourceId)
                        && "教二305".equals(c.place) && "学生证".equals(c.bring)));

        DemoScenario demo;
        try (InputStream model = model(args[0])) {
            demo = new DemoScenario(model, SENT);
        }
        check("离线样例恰好生成作业、考试和接龙三类卡片", demo.engine.all().size() == 3
                && demo.engine.all().stream().anyMatch(c -> "todo".equals(c.kind))
                && demo.engine.all().stream().anyMatch(c -> "exam".equals(c.kind))
                && demo.engine.all().stream().anyMatch(c -> "jielong".equals(c.kind)));
        demo.applyUpdates();
        check("演示更新只修改两张已有卡片，并撤掉已接的接龙卡",
                demo.engine.all().size() == 2 && demo.engine.all().stream().allMatch(c -> c.updated)
                && demo.engine.all().stream().noneMatch(c -> "jielong".equals(c.kind)));
        demo.applyUpdates();
        check("重复点击更新不重复处理，且样例从未写入真实引擎",
                demo.engine.nIn == 7 && engine.all().stream().noneMatch(c -> c.sourceId.startsWith("demo-")));

        if (failures > 0) System.exit(1);
    }
}
