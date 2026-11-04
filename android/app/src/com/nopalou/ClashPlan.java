package com.nopalou;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 撞车提醒的纯逻辑（规则见 docs/撞车提醒规则.md）。
 * 只依赖 java.util，不碰 Android，也不修改任何卡片。
 */
public final class ClashPlan {
    /** 一小时的毫秒数，用来算 3 小时容差。 */
    private static final long HOUR_MS = 3600_000L;
    /** 一天的毫秒数，用来算 7 天窗口。 */
    private static final long DAY_MS = 24 * HOUR_MS;
    /** 参与窗口：从现在起 7 天，按毫秒算。 */
    private static final long WINDOW_MS = 7 * DAY_MS;
    /** 相邻两张卡的容差：3 小时，正好 3 小时也算挤在一起。 */
    private static final long GAP_MS = 3 * HOUR_MS;
    /** 一句话说明里最多列几个标题。 */
    private static final int MAX_TITLES = 3;
    /** 组内至少几张卡才算撞车。 */
    private static final int MIN_GROUP = 2;

    /** 工具类，不实例化。 */
    private ClashPlan() {
    }

    /** 一个撞车组：组内最早的截止时间、最晚的截止时间，以及组内的卡片。 */
    public static final class Clash {
        /** 组内最早的截止时间。 */
        public final long start;
        /** 组内最晚的截止时间。 */
        public final long end;
        /** 组内卡片，按卡片编号排列，start/end 由组内最早的卡和最晚的卡决定。 */
        public final List<Engine.Card> cards;

        /** 只在 find 内部构造，三个字段一次定好，之后不再变化。 */
        Clash(long start, long end, List<Engine.Card> cards) {
            this.start = start;
            this.end = end;
            this.cards = cards;
        }

        /** 一句话说明：什么时候、几件事、都是什么。 */
        public String text() {
            // 用组内最早的时刻作为这句话的起点。
            StringBuilder b = new StringBuilder();
            b.append(Engine.humanDeadline(start));
            // 接上件数与固定说法，件数用组内实际卡片数量。
            b.append("前后有 ").append(cards.size()).append(" 件事挤在一起：");
            // 最多列 3 个标题，其余用“等”带过。
            int shown = Math.min(cards.size(), MAX_TITLES);
            for (int i = 0; i < shown; i++) {
                // 标题之间用顿号连接，第一个标题前不加分隔符。
                if (i > 0) b.append("、");
                // 标题取卡片当前显示用的标题，用户手动改过名的也认。
                b.append(cards.get(i).displayTitle());
            }
            // 超过 3 张时补一个“等”字。
            if (cards.size() > MAX_TITLES) b.append("等");
            return b.toString();
        }
    }

    /**
     * 找出所有撞车组，按组内最早截止时间从早到晚排列。
     * 参与条件：未完成、不需要确认、deadline 在 now 之后且不超过 now+7 天。
     */
    static List<Clash> find(List<Engine.Card> cards, long now) {
        // 结果按组的先后顺序往这里放。
        List<Clash> groups = new ArrayList<>();
        // 参与计算的卡片先收集到一个新列表，避免动到调用方的列表。
        List<Engine.Card> pool = new ArrayList<>();
        // 传进来的列表可能为 null，按“没有卡”处理。
        if (cards != null) {
            for (Engine.Card card : cards) {
                // 空元素直接跳过。
                if (card == null) continue;
                // 已完成的卡不提醒。
                if (card.done) continue;
                // 时间还没确认的卡不参与。
                if (card.needsConfirm) continue;
                // 已过期或正好到点的卡不参与，deadline 为 0 的也在这里被挡掉。
                if (card.deadline <= now) continue;
                // 超过 7 天的卡不参与，正好 7 天整仍参与。
                if (card.deadline > now + WINDOW_MS) continue;
                // 通过全部条件才收进来，考试卡、待办卡、接龙卡一视同仁。
                pool.add(card);
            }
        }
        // 按截止时间从早到晚排序，时间相同的保持输入顺序。
        Collections.sort(pool, (a, b) -> Long.compare(a.deadline, b.deadline));
        // 从最早的一张开始扫，每次划出一个 3 小时链式相连的组。
        int i = 0;
        while (i < pool.size()) {
            // 组的开头下标。
            int begin = i;
            // 组内目前最后一张卡的下标。
            int last = i;
            // 只要下一张和“前一张”相差不超过 3 小时，就并进同一组；
            // 判的是相邻两张，所以链式相隔都能连起来。
            while (last + 1 < pool.size()
                    && pool.get(last + 1).deadline - pool.get(last).deadline <= GAP_MS) {
                last++;
            }
            // 两张及以上才算撞车，单张不提醒。
            if (last - begin + 1 >= MIN_GROUP) {
                // 组内卡片复制一份，组外的人改不动这里。
                List<Engine.Card> members = new ArrayList<>(pool.subList(begin, last + 1));
                // 组内固定按卡片编号排列，列表位置不会因为重复计算而跳动。
                Collections.sort(members, (a, b) -> {
                    String ia = a.id == null ? "" : a.id;
                    String ib = b.id == null ? "" : b.id;
                    return ia.compareTo(ib);
                });
                // 边界时间取截止时间最早和最晚的那两张卡。
                groups.add(new Clash(pool.get(begin).deadline, pool.get(last).deadline, members));
            }
            // 从这一组之后继续找下一组。
            i = last + 1;
        }
        // 组是按最早截止时间从早到晚扫出来的，顺序天然符合要求。
        return groups;
    }

    /** 最早的那一组撞车，没有就返回 null。 */
    static Clash nearest(List<Engine.Card> cards, long now) {
        // find 已经按组内最早截止时间排好序，取第一组即可。
        List<Clash> groups = find(cards, now);
        // 一组都没有说明不存在撞车。
        return groups.isEmpty() ? null : groups.get(0);
    }
}
