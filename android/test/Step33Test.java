package com.nopalou;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** QQ 全体提醒前缀不遮住真正发送者，通知可直接自动出卡。 */
public final class Step33Test {
    private static int failures;
    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }
    public static void main(String[] args) throws Exception {
        List<NotifParser.Parsed> parsed = NotifParser.parse("数据库系统",
                "[3条][有全体消息]计挑赛石老师:明天18点前交实验报告", null, null, null, false);
        check("通知元信息前缀后仍能读到长发送者名", parsed.size() == 1
                && "计挑赛石老师".equals(parsed.get(0).sender));
        Engine e;
        try (InputStream weights = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            e = new Engine(weights);
        }
        e.setProfile(Engine.Profile.demo());
        for (NotifParser.Parsed p : parsed) {
            e.process(new Engine.Msg("automatic", "com.tencent.mobileqq|数据库系统", "数据库系统", p.sender,
                    p.body, "com.tencent.mobileqq", 1_800_000_000_000L));
        }
        check("直接自动生成干净标题的待办", e.all().size() == 1 && "交实验报告".equals(e.all().get(0).displayTitle()));
        check("原文保留全体提醒的元信息", e.all().size() == 1 && e.all().get(0).sourceText.contains("[有全体消息]"));
        check("只含全体标记和计数的通知不生成正文", NotifParser.parse("群",
                "[有全体消息]3条新消息", null, null, null, false).isEmpty());
        if (failures > 0) System.exit(1);
    }
}
