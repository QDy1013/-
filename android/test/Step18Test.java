package com.nopalou;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 两小时提醒窗口、改期、完成和跨进程去重策略。 */
public final class Step18Test {
    private static final long NOW = 1_800_000_000_000L;
    private static final long HOUR = 3600_000L;
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static Engine.Card card(String id, long deadline) {
        Engine.Card card = new Engine.Card();
        card.id = id;
        card.deadline = deadline;
        return card;
    }

    public static void main(String[] args) {
        Engine.Card finished = card("done", NOW + HOUR);
        finished.done = true;
        Engine.Card unsure = card("guess", NOW + HOUR);
        unsure.needsConfirm = true;
        ReminderPlan empty = ReminderPlan.create(Arrays.asList(finished, unsure,
                card("past", NOW - 1), card("now", NOW), card("unknown", 0)), Collections.emptyMap(), NOW);
        check("已完成、待确认、到点或已过期卡片不排提醒", empty.nextAt == 0 && empty.due.isEmpty());

        Engine.Card future = card("later", NOW + 3 * HOUR);
        Engine.Card due = card("soon", NOW + HOUR);
        Engine.Card edge = card("edge", NOW + ReminderPlan.LEAD_MS);
        Map<String, Long> sent = new HashMap<>();
        ReminderPlan first = ReminderPlan.create(Arrays.asList(future, due, edge), sent, NOW);
        check("进入两小时窗口的卡片合并为一批，边界包含在内",
                first.nextAt == NOW - HOUR && first.due.equals(Arrays.asList(due, edge)));
        sent.put(due.id, due.deadline);
        sent.put(edge.id, edge.deadline);
        ReminderPlan second = ReminderPlan.create(Arrays.asList(future, due, edge), sent, NOW);
        check("持久化的卡号和时刻阻止重复提醒，并安排下一个时间",
                second.nextAt == NOW + HOUR && second.due.isEmpty());
        due.deadline = NOW + 30 * 60_000L;
        ReminderPlan edited = ReminderPlan.create(Arrays.asList(future, due, edge), sent, NOW);
        check("改期后的时间可重新提醒", edited.due.equals(Collections.singletonList(due)));
        due.done = true;
        ReminderPlan completed = ReminderPlan.create(Arrays.asList(future, due), sent, NOW);
        check("改期后完成的事项不会被旧提醒重新选中",
                completed.nextAt == NOW + HOUR && completed.due.isEmpty());
        ReminderPlan late = ReminderPlan.create(Collections.singletonList(future), sent, future.deadline + 1);
        check("系统迟到超过截止点时忽略旧事项", late.nextAt == 0 && late.due.isEmpty());
        if (failures > 0) System.exit(1);
    }
}
