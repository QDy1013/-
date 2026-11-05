package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 自动通知、人工修正和卡片存档的端到端 JVM 回归。 */
public final class Step13Test {
    private static final long SENT = LocalDateTime.of(2026, 11, 4, 12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    private static final String EXAM = "明天9:00期中考试\n地点：教二305\n带：学生证";
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static Engine engine(String android) throws Exception {
        try (InputStream model = Files.newInputStream(Paths.get(android, "app", "assets", "model.tsv"))) {
            Engine engine = new Engine(model);
            engine.setProfile(Engine.Profile.demo());
            return engine;
        }
    }

    public static void main(String[] args) throws Exception {
        Engine engine = engine(args[0]);
        engine.process(new Engine.Msg("unknown", "com.tencent.mm|数据库系统", "数据库系统", "群友",
                EXAM, "com.tencent.mm", SENT));
        check("自动通知缺失发送者时不冒充可信老师", engine.all().isEmpty());

        engine.process(new Engine.Msg("real", "com.tencent.mm|数据库系统", "数据库系统", "王老师",
                EXAM, "com.tencent.mm", SENT));
        engine.process(new Engine.Msg("qq", "com.tencent.mobileqq|数据库系统", "数据库系统", "王老师",
                EXAM, "com.tencent.mobileqq", SENT));
        check("不同应用的同名通知群各自保留", engine.all().size() == 2);
        Engine.Card real = engine.all().get(0);
        Engine.Card qqCard = engine.all().get(1);
        long oldTime = qqCard.deadline;
        check("自动通知保留原文换行、地点和群", qqCard.sourceText.equals(EXAM)
                && "数据库系统".equals(qqCard.groupName) && "教二305".equals(qqCard.place));

        check("空标题拒绝修改", !engine.editCard(qqCard, " ", oldTime + 3600_000L) && qqCard.deadline == oldTime);
        check("只改标题保留时间和原始识别标题", engine.editCard(qqCard, " 数据库期中 ", null)
                && "数据库期中".equals(qqCard.displayTitle()) && !qqCard.displayTitle().equals(qqCard.title)
                && qqCard.deadline == oldTime && qqCard.sourceText.equals(EXAM));
        check("考试时间是日历开始时间", qqCard.calendarStart() == oldTime);

        engine.process(new Engine.Msg("qq-update", "com.tencent.mobileqq|数据库系统", "数据库系统", "王老师",
                "期中考试改到后天上午10点。", "com.tencent.mobileqq", SENT + 1000L));
        check("QQ 自动更正只更新本群卡片，保留人工标题且不碰微信同名群",
                engine.all().size() == 2 && qqCard.updated && qqCard.deadline != oldTime
                        && real.deadline == oldTime && "数据库期中".equals(qqCard.displayTitle()));
        check("清除时间后回到待确认", engine.editCard(qqCard, qqCard.displayTitle(), 0L)
                && qqCard.deadline == 0 && qqCard.needsConfirm && qqCard.calendarStart() == 0);
        long correctedTime = oldTime + 2 * 3600_000L;
        check("人工确认新时间后可按新时间加入日历",
                engine.editCard(qqCard, qqCard.displayTitle(), correctedTime)
                        && !qqCard.needsConfirm && qqCard.calendarStart() == correctedTime);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        engine.saveState(output);
        Engine reopened = engine(args[0]);
        reopened.loadState(new ByteArrayInputStream(output.toByteArray()));
        check("重载后人工标题、时间和原文均保留", reopened.all().stream().anyMatch(card ->
                "数据库期中".equals(card.displayTitle()) && card.deadline == correctedTime && EXAM.equals(card.sourceText)));
        check("已经删除或不属于当前引擎的卡片不可改", !reopened.editCard(qqCard, "其他", null));
        if (failures > 0) System.exit(1);
    }
}
