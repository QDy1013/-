package com.nopalou;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 和真机通知验收器相同的出卡、改期、取消序列。 */
public final class Step34Test {
    private static final long NOW = 1_800_000_000_000L;
    private static int sequence;
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static void notify(Engine e, String body) {
        for (NotifParser.Parsed p : NotifParser.parse("自动验收群", "[有全体消息]计挑赛石老师：" + body,
                null, null, null, false)) {
            e.process(new Engine.Msg("notification-" + ++sequence, "com.nopalou.replay|自动验收群", "自动验收群",
                    p.sender, p.body, "com.nopalou.replay", NOW));
        }
    }

    public static void main(String[] args) throws Exception {
        Engine e;
        try (InputStream weights = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            e = new Engine(weights);
        }
        e.setProfile(Engine.Profile.demo());
        notify(e, "明天18点前交性能验收报告，交到学习通。");
        notify(e, "明天9点性能验收考试，地点：教二305，带：学生证。");
        check("系统通知正文自动识别成两种卡片", e.all().size() == 2
                && "todo".equals(e.all().get(0).kind) && "exam".equals(e.all().get(1).kind));
        Engine.Card report = e.all().get(0);
        Engine.Card exam = e.all().get(1);
        notify(e, "性能验收报告延期到后天20点。");
        notify(e, "性能验收考试改到后天10点。");
        check("自动改期更新原卡且保留卡号", e.all().size() == 2 && e.all().contains(report) && e.all().contains(exam)
                && report.updated && exam.updated
                && report.deadline == Engine.parseDeadline("后天20点", NOW).value
                && exam.deadline == Engine.parseDeadline("后天10点", NOW).value);
        notify(e, "性能验收报告不用交了。");
        notify(e, "性能验收考试取消。");
        check("自动取消撤下两张卡片", e.all().isEmpty());
        if (failures > 0) System.exit(1);
    }
}
