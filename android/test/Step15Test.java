package com.nopalou;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 读屏预览的白名单、暂存与确认导入。 */
public final class Step15Test {
    private static final long CAPTURED = 1_800_000_000_000L;
    private static final String WECHAT = "com.tencent.mm";
    private static final String HOMEWORK = "@所有人 明天18点前交实验报告到学习通。";
    private static final String EXAM = "@所有人 明天9:00期中考试\n地点：教二305\n带：学生证";
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Engine engine;
        try (InputStream model = Files.newInputStream(Paths.get(args[0], "app", "assets", "model.tsv"))) {
            engine = new Engine(model);
        }
        engine.setProfile(Engine.Profile.demo());
        List<String> nodes = Arrays.asList(" ", HOMEWORK, "  " + HOMEWORK, EXAM);
        check("系统界面和不爬楼自己的文字无法创建预览",
                ReadPreview.create("com.android.systemui", CAPTURED, nodes) == null
                        && ReadPreview.create("com.nopalou", CAPTURED, nodes) == null);
        check("只有空白节点时不创建预览",
                ReadPreview.create(WECHAT, CAPTURED, Arrays.asList(" ", "\n")) == null);

        ReadPreview preview = ReadPreview.create(WECHAT, CAPTURED, nodes);
        check("预览去除重复和空白但保留单条消息内部换行", preview.lines.equals(Arrays.asList(HOMEWORK, EXAM)));
        check("建立预览不会把任何内容送入引擎", engine.nIn == 0 && engine.all().isEmpty());
        check("空群名、无选择或选择长度不一致不会导入",
                preview.importSelection(engine, " ", new boolean[]{true, false}, CAPTURED) == 0
                        && preview.importSelection(engine, "一班", new boolean[]{false, false}, CAPTURED) == 0
                        && preview.importSelection(engine, "一班", new boolean[]{true}, CAPTURED) == 0
                        && engine.nIn == 0);
        check("五分钟过期和时钟回拨都阻止旧预览导入",
                preview.importSelection(engine, "一班", new boolean[]{true, false}, CAPTURED + 5 * 60_000L) == 0
                        && preview.importSelection(engine, "一班", new boolean[]{true, false}, CAPTURED - 1) == 0
                        && engine.nIn == 0);

        check("只选考试时，仅该考试进入引擎，原文换行和发送基准保留",
                preview.importSelection(engine, " 一班 ", new boolean[]{false, true}, CAPTURED + 1000) == 1
                        && engine.nIn == 1 && engine.all().size() == 1
                        && "exam".equals(engine.all().get(0).kind)
                        && EXAM.equals(engine.all().get(0).sourceText)
                        && engine.all().get(0).sourceTs == CAPTURED);
        check("同 App 的另一个群即使正文相同也各自出卡",
                preview.importSelection(engine, "二班", new boolean[]{false, true}, CAPTURED + 1000) == 1
                        && engine.all().size() == 2
                        && !engine.all().get(0).groupId.equals(engine.all().get(1).groupId));

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) many.add("文字节点 " + i);
        check("每次预览最多 40 项", ReadPreview.create(WECHAT, CAPTURED, many).lines.size() == 40);
        if (failures > 0) System.exit(1);
    }
}
