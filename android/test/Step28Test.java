package com.nopalou;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/** 从自动通知入口验证误删恢复、重复推送与重启存档。 */
public final class Step28Test {
    private static final long NOW = 1_800_000_000_000L;
    private static int failures;
    private static String android;
    private static Method dismiss, restore, removed, clearRemoved;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static Engine engine() throws Exception {
        try (InputStream model = Files.newInputStream(Paths.get(android, "app/assets/model.tsv"))) {
            Engine e = new Engine(model);
            e.setProfile(Engine.Profile.demo());
            return e;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Engine.Card> removed(Engine e) throws Exception {
        return (List<Engine.Card>) removed.invoke(e);
    }

    private static boolean dismiss(Engine e, Engine.Card card, long now) throws Exception {
        return (Boolean) dismiss.invoke(e, card, now);
    }

    private static boolean restore(Engine e, Engine.Card card) throws Exception {
        return (Boolean) restore.invoke(e, card);
    }

    private static int messageSequence;

    private static boolean push(Engine e, String group, String sender, String body, long sentAt) {
        e.process(new Engine.Msg("notification-" + ++messageSequence,
                NotifFilter.groupId("com.tencent.mm", group), group, sender, body, "com.tencent.mm", sentAt));
        return true;
    }

    private static Engine roundTrip(Engine e) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        e.saveState(bytes);
        Engine copy = engine();
        copy.loadState(new ByteArrayInputStream(bytes.toByteArray()));
        return copy;
    }

    public static void main(String[] args) throws Exception {
        android = args[0];
        Engine e = engine();
        String body = "明天18点前交实验报告到学习通";
        check("正常入口能生成待办", push(e, "数据库系统", "王老师", body, NOW)
                && e.all().size() == 1);
        try {
            dismiss = Engine.class.getMethod("dismissCard", Engine.Card.class, long.class);
            restore = Engine.class.getMethod("restoreCard", Engine.Card.class);
            removed = Engine.class.getMethod("removedCards");
            clearRemoved = Engine.class.getMethod("clearRemovedCards");
        } catch (NoSuchMethodException missing) {
            check("存在可恢复的移除操作", false);
            System.exit(1);
        }
        Engine.Card original = e.all().get(0);
        e.editCard(original, "报告终稿\n复核\t✓", NOW + 3600_000L);
        int[] changes = {0};
        e.listener = () -> changes[0]++;
        check("移除后从首页与搜索消失", dismiss(e, original, NOW)
                && e.all().isEmpty() && e.queryCards("", Engine.CardScope.ALL, 100, NOW).isEmpty());
        check("移除后不再安排到期提醒", ReminderPlan.create(e.all(), Collections.emptyMap(), NOW).nextAt == 0);
        check("同一张卡重复移除、空卡、外来卡和非法时刻均无副作用",
                !dismiss(e, original, NOW) && !dismiss(e, null, NOW)
                && !dismiss(e, new Engine.Card(), NOW) && changes[0] == 1);
        removed(e).clear();
        check("已移除列表是独立快照", removed(e).size() == 1);
        e = roundTrip(e);
        check("重启后仍在已移除列表", e.all().isEmpty() && removed(e).size() == 1);
        Engine.Card archived = removed(e).get(0);
        check("原文、人工修正和卡号完整保留", original.id.equals(archived.id)
                && original.sourceText.equals(archived.sourceText)
                && original.displayTitle().equals(archived.displayTitle()) && original.deadline == archived.deadline);
        push(e, "数据库系统", "王老师", body, NOW + 60_000L);
        check("重启后的重复通知不会把已移除卡顶回来", e.all().isEmpty() && removed(e).size() == 1);
        check("恢复后重新参加提醒", restore(e, archived) && removed(e).isEmpty()
                && e.all().size() == 1 && ReminderPlan.create(e.all(), Collections.emptyMap(), NOW).due.size() == 1);
        check("重复恢复不会复制卡片", !restore(e, archived) && !restore(e, null) && e.all().size() == 1);
        Engine completed = roundTrip(e);
        Engine.Card done = completed.all().get(0);
        done.done = true;
        dismiss(completed, done, NOW);
        completed = roundTrip(completed);
        check("完成状态经过移除、重启和恢复仍保持", restore(completed, removed(completed).get(0))
                && completed.all().get(0).done
                && ReminderPlan.create(completed.all(), Collections.emptyMap(), NOW).nextAt == 0);
        check("非法时间不移除已有卡", !dismiss(e, archived, 0) && e.all().size() == 1);
        dismiss(e, archived, NOW + 10);
        push(e, "数据库系统", "王老师", body, NOW + 86400_000L);
        check("隔天同文仍是新事项", e.all().size() == 1 && removed(e).size() == 1);
        check("恢复旧卡与隔天事项不冲突", restore(e, archived) && e.all().size() == 2);
        Engine.Card newer = e.all().get(0);
        dismiss(e, archived, NOW + 20);
        dismiss(e, newer, NOW + 30);
        check("最近移除排在前面", removed(e).get(0) == newer);
        Engine two = roundTrip(e);
        push(two, "计算机网络", "王老师", "明天20点前交读书报告到邮箱", NOW);
        check("存档只有已移除卡时，新卡号也不会碰撞", two.all().size() == 1
                && !two.all().get(0).id.equals(archived.id) && !two.all().get(0).id.equals(newer.id));
        clearRemoved.invoke(two);
        check("清空已移除保留日常卡片，重启后仍为空", removed(two).isEmpty()
                && two.all().size() == 1 && removed(roundTrip(two)).isEmpty());
        two.clearLocalData();
        check("清空全部数据同时清理已移除卡", two.all().isEmpty() && removed(two).isEmpty());
        String legacy = "NPL1\nSEQ\t99\nN\t1\nCARD\nid\tc99\nkind\ttodo\ntitle\t旧卡\nEND\n";
        e.loadState(new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8)));
        check("旧版本存档仍按正常卡片加载且替换旧回收站", e.all().size() == 1 && removed(e).isEmpty());
        Engine j = engine();
        String join = "#接龙 周末聚餐，明天18点前报名\n1. 张三";
        push(j, "班级群", "班长", join, NOW);
        check("接龙前提成立", j.all().size() == 1 && "jielong".equals(j.all().get(0).kind));
        Engine.Card oldJoin = j.all().get(0);
        dismiss(j, oldJoin, NOW);
        push(j, "班级群", "班长", join + "\n2. 李四", NOW + 60_000L);
        check("已有更新的同题接龙时不恢复旧名单卡", j.all().size() == 1 && !restore(j, oldJoin));
        Engine split = engine();
        String twoItems = "周三前交实验报告，周五前交读书笔记";
        push(split, "数据库系统", "王老师", twoItems, NOW);
        Engine.Card first = split.all().get(0);
        dismiss(split, first, NOW);
        split = roundTrip(split);
        push(split, "数据库系统", "王老师", twoItems, NOW + 1);
        check("拆分事项移除后，重复整条通知不会重新出卡", split.all().size() == 1 && removed(split).size() == 1);
        push(split, "计算机网络", "王老师", twoItems, NOW + 2);
        check("同文的另一群不受已移除卡影响", split.all().size() == 3 && removed(split).size() == 1);
        Engine old = engine();
        String oldJoins = "NPL1\nSEQ\t2\nN\t2\nCARD\nid\tc1\nkind\tjielong\n"
                + "title\t体测报名\ngroupId\tg\nEND\nCARD\nid\tc2\nkind\tjielong\n"
                + "title\t聚餐报名\ngroupId\tg\nEND\n";
        old.loadState(new ByteArrayInputStream(oldJoins.getBytes(StandardCharsets.UTF_8)));
        Engine.Card oldCard = old.all().get(0);
        dismiss(old, oldCard, NOW);
        check("旧存档缺少接龙键时不把两个不同标题当作同一接龙", restore(old, oldCard) && old.all().size() == 2);
        if (failures > 0) System.exit(1);
    }
}
