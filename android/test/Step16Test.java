package com.nopalou;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 多场考试改期及确认回复的群边界。 */
public final class Step16Test {
    private static final long START = 1_800_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static Engine engine(String android) throws Exception {
        try (InputStream model = Files.newInputStream(Paths.get(android, "app", "assets", "model.tsv"))) {
            Engine e = new Engine(model);
            e.setProfile(Engine.Profile.demo());
            return e;
        }
    }

    private static void send(Engine e, String id, String group, String sender, String text, long time) {
        e.process(new Engine.Msg(id, group, "数据库系统", sender, text, "test", time));
    }

    private static Engine.Card card(Engine e, String id) {
        return e.all().stream().filter(c -> id.equals(c.sourceId)).findFirst().orElse(null);
    }

    private static void acks(Engine e, String group, long first) {
        for (int i = 0; i < 5; i++) send(e, group + "-ack" + i, group, "同学" + i, "收到", first + i * 1000);
    }

    public static void main(String[] args) throws Exception {
        Engine e = engine(args[0]);
        send(e, "db", "A", "王老师", "数据库期中考试明天9点，地点：教一101", START);
        send(e, "os", "A", "王老师", "操作系统期中考试后天14点，地点：教二305", START + MINUTE);
        Engine.Card db = card(e, "db"), os = card(e, "os");
        check("前提：同一群已有两个不同科目的考试", db != null && os != null
                && "exam".equals(db.kind) && "exam".equals(os.kind));
        if (db == null || os == null) System.exit(1);
        long dbBefore = db.deadline, osBefore = os.deadline;
        send(e, "reschedule", "A", "王老师", "更正：数据库期中考试改到后天上午10点。", START + 2 * MINUTE);
        check("按明确考试名称更新较早一张卡", db.updated && db.deadline != dbBefore && os.deadline == osBefore);
        ByteArrayOutputStream state = new ByteArrayOutputStream();
        e.saveState(state);
        Engine reloaded = engine(args[0]);
        reloaded.loadState(new ByteArrayInputStream(state.toByteArray()));
        Engine.Card saved = card(reloaded, "db");
        check("改期原文随卡片保存，保留首次通知",
                saved != null && saved.sourceText.contains("明天9点")
                        && "更正：数据库期中考试改到后天上午10点。".equals(saved.lastRevisionText));
        long dbAfter = db.deadline;
        send(e, "ambiguous", "A", "王老师", "期中考试改到后天下午3点。", START + 3 * MINUTE);
        check("同名类型多场时跳过指向不清的改期", db.deadline == dbAfter && os.deadline == osBefore);
        send(e, "unknown", "A", "王老师", "高等数学期中考试改到后天下午4点。", START + 4 * MINUTE);
        check("未找到明确名称时不误改或新建", db.deadline == dbAfter && os.deadline == osBefore && e.all().size() == 2);
        send(e, "amb-cancel", "A", "王老师", "期中考试取消。", START + 5 * MINUTE);
        check("不明确的取消保留两场考试", e.all().contains(db) && e.all().contains(os));
        send(e, "cancel-db", "A", "王老师", "取消数据库期中考试。", START + 6 * MINUTE);
        check("明确取消只移除所指考试", !e.all().contains(db) && e.all().contains(os));
        String newTime = "后天下午5点";
        send(e, "single", "A", "王老师", "考试原定后天下午2点，改为" + newTime + "。", START + 7 * MINUTE);
        check("只剩一场时简写仍可改期", os.updated && os.deadline != osBefore && e.all().size() == 1);
        check("同时出现旧时间和新时间时取改期词之后的时间",
                os.deadline == Engine.parseDeadline(newTime, START + 7 * MINUTE).value);

        Engine burst = engine(args[0]);
        send(burst, "candidate", "A", "小周", "实验报告明天交对吧", START);
        check("前提：候选消息首次没有直接出卡", burst.all().isEmpty());
        acks(burst, "B", START + MINUTE);
        check("B 群确认回复不会把 A 群候选提升为待办", burst.nPromoted == 0 && burst.all().isEmpty());
        acks(burst, "A", START + 2 * MINUTE);
        check("同群在窗口内的五条回复仍触发一次提升",
                burst.nPromoted == 1 && burst.all().size() == 1 && card(burst, "candidate") != null);

        Engine late = engine(args[0]);
        send(late, "candidate", "A", "小周", "实验报告明天交对吧", START);
        acks(late, "A", START - MINUTE);
        acks(late, "A", START + 11 * MINUTE);
        check("早于候选或晚于十分钟的回复不能提升", late.nPromoted == 0 && late.all().isEmpty());
        if (failures > 0) System.exit(1);
    }
}
