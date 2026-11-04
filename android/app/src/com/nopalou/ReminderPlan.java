package com.nopalou;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只根据当前卡片、已提醒记录与时钟计算下一次提醒。 */
final class ReminderPlan {
    static final long LEAD_MS = 2 * 60 * 60_000L;

    final long nextAt;
    final List<Engine.Card> due;

    private ReminderPlan(long nextAt, List<Engine.Card> due) {
        this.nextAt = nextAt;
        this.due = due;
    }

    static ReminderPlan create(List<Engine.Card> cards, Map<String, ?> delivered, long now) {
        long next = 0L;
        List<Engine.Card> due = new ArrayList<>();
        for (Engine.Card card : cards) {
            if (card.id == null || card.done || card.needsConfirm || card.deadline <= now) continue;
            if (wasDelivered(card, delivered)) continue;
            long trigger = Math.max(1L, card.deadline - LEAD_MS);
            if (next == 0L || trigger < next) next = trigger;
            if (trigger <= now) due.add(card);
        }
        return new ReminderPlan(next, due);
    }

    static boolean wasDelivered(Engine.Card card, Map<String, ?> delivered) {
        return Long.valueOf(card.deadline).equals(delivered.get(card.id));
    }
}
