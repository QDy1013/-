package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 考试标题、非法钟点和清空会话回归。 */
public final class Step17Test {
    private static final long START = 1_800_000_000_000L;
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Engine e;
        try (InputStream model = Files.newInputStream(Paths.get(args[0], "app", "assets", "model.tsv"))) {
            e = new Engine(model);
        }
        Engine.Profile profile = Engine.Profile.demo();
        e.setProfile(profile);
        e.process(new Engine.Msg("a", "g", "班级群", "王老师",
                "明天上午9点进行数据库期中考试，地点：教二305", "test", START));
        check("考试卡标题保留科目，时间独立显示", e.all().size() == 1
                && "数据库期中考试".equals(e.all().get(0).displayTitle())
                && e.all().get(0).deadline > 0);
        String legacy = "NPL1\nSEQ\t1\nN\t1\nCARD\nid\tc1\nkind\texam\n"
                + "title\t操作系统期中考试明晚8点\ntitleOverride\t复习清单\n"
                + "sourceText\t操作系统期中考试明晚8点\nEND\n";
        e.loadState(new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8)));
        check("重读旧档会去掉自动标题的旧时间，尊重人工标题",
                "操作系统期中考试".equals(e.all().get(0).title)
                        && "复习清单".equals(e.all().get(0).displayTitle()));
        for (String text : new String[]{"明天09:99考试", "明天25:00考试", "明天18点90分考试"}) {
            check("非法时刻待确认：" + text, !Engine.parseDeadline(text, START).ok());
        }
        check("正常带时段的时间仍可解析", Engine.parseDeadline("明天上午9:00考试", START).ok());
        e.process(new Engine.Msg("old", "g", "班级群", "小周", "实验报告明天交对吧", "test", START));
        check("清空前有近期文字和待确认候选", !e.recent.isEmpty() && !e.candidates.isEmpty());
        try {
            Engine.class.getMethod("clearLocalData").invoke(e);
        } catch (NoSuchMethodException ex) {
            check("具备清空本机待办及近期消息的操作", false);
            System.exit(1);
        }
        check("清空同时移除卡片、近期文字、候选和计数", e.all().isEmpty()
                && e.recent.isEmpty() && e.candidates.isEmpty() && e.senderStat.isEmpty() && e.nIn == 0);
        check("清空待办后仍使用原有个人档案", e.getProfile() == profile);
        if (failures > 0) System.exit(1);
    }
}
