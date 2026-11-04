package com.nopalou;

import java.io.InputStream;

/** 一次离线演示。独立的引擎和档案仅用于处理样例，不接触真实通知或存档。 */
final class DemoScenario {
    private static final String COURSE = "数据库系统课程群";
    private static final String CLASS = "计科2301班群";
    private static final String ROSTER = "#接龙\n体测报名（明天18:00截止）\n1. 周若曦\n2. 郑浩然";
    final Engine engine;
    private final long start;
    private int sequence;
    private boolean updated;

    DemoScenario(InputStream model, long start) throws Exception {
        this.start = start;
        engine = new Engine(model);
        engine.setProfile(Engine.Profile.demo());
        send(COURSE, "王老师", "明天20:00前交实验报告，交到学习通。");
        send(COURSE, "王老师", "明天上午9点期中考试，地点：教二305，带：学生证和2B铅笔。");
        send(CLASS, "班长", ROSTER);
        send(COURSE, "周若曦", "收到");
    }

    void applyUpdates() {
        if (updated) return;
        updated = true;
        send(COURSE, "王老师", "期中考试改到后天上午10点。");
        send(COURSE, "王老师", "更正：实验报告改为后天22:00提交。");
        send(CLASS, "班长", ROSTER + "\n3. " + engine.getProfile().name);
    }

    private void send(String group, String sender, String text) {
        sequence++;
        engine.process(new Engine.Msg("demo-" + sequence, group, group, sender, text, "demo", start));
    }
}
