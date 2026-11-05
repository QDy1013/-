package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 自动通知的按需持久化，以及失败重试时不丢失脏状态。 */
public final class Step32Test {
    private static final long NOW = 1_800_000_000_000L;
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Engine e;
        try (InputStream weights = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            e = new Engine(weights);
        }
        e.setProfile(Engine.Profile.demo());
        check("空引擎不需要自动落盘", !e.hasStateChanges());
        e.process(new Engine.Msg("noise", "g", "数据库系统", "同学", "收到", "com.tencent.mm", NOW));
        check("收到闲聊和确认回复不触发整份卡片存档", !e.hasStateChanges() && e.nIn == 1);
        String body = "明天18点前交实验报告到学习通";
        e.process(new Engine.Msg("task", "g", "数据库系统", "王老师", body, "com.tencent.mm", NOW));
        check("自动识别出卡时需要落盘", e.hasStateChanges() && e.all().size() == 1);
        long written = e.stateRevision();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        e.saveState(bytes);
        check("序列化尚未提交时仍保留待保存状态", e.hasStateChanges());
        e.markStateSaved(written);
        check("原子提交成功后清除待保存状态", !e.hasStateChanges());
        e.process(new Engine.Msg("duplicate", "g", "数据库系统", "王老师", body, "com.tencent.mm", NOW + 1));
        check("重复通知不重复写卡片存档", !e.hasStateChanges());
        e.process(new Engine.Msg("revision", "g", "数据库系统", "王老师",
                "实验报告延期到后天20点", "com.tencent.mm", NOW + 2));
        check("自动更正会重新触发持久化", e.hasStateChanges() && e.all().get(0).updated);
        long snapshot = e.stateRevision();
        try {
            e.saveState(new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("Simulated full disk"); }
            });
            check("写盘失败确实被模拟", false);
        } catch (IOException expected) {
            check("写盘失败保持待保存，下次自动通知可重试", e.hasStateChanges());
        }
        e.process(new Engine.Msg("ack", "g", "数据库系统", "小周", "收到", "com.tencent.mm", NOW + 3));
        check("后续噪声不能清除失败后的待保存状态", e.hasStateChanges());
        e.editCard(e.all().get(0), "报告终稿", null);
        e.markStateSaved(snapshot);
        check("提交旧快照不会漏掉后来的卡片变动", e.hasStateChanges());
        e.markStateSaved(e.stateRevision());
        e.dismissCard(e.all().get(0), NOW + 4);
        check("移除卡片也需要持久化", e.hasStateChanges());
        e.markStateSaved(e.stateRevision());
        e.restoreCard(e.removedCards().get(0));
        check("恢复卡片也需要持久化", e.hasStateChanges());
        e.saveState(bytes = new ByteArrayOutputStream());
        e.loadState(new ByteArrayInputStream(bytes.toByteArray()));
        check("重读已落盘数据后无需重复写入", !e.hasStateChanges() && e.all().size() == 1);
        if (failures > 0) System.exit(1);
    }
}
