package com.nopalou;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 往上翻聊天记录时，把一页一页读到的内容拼成按时间从早到晚的一条序列。纯逻辑，可在 JVM 上测试。
 *
 * 每一页是屏幕上从上到下的条目：时间分隔条或消息。往上翻一页后，新页的下半部分和已有内容重叠，
 * 上半部分是更早的新内容：找到新页里第一条已经见过的条目，它前面的都接到序列最前面。
 * 消息的时间取它上面最近的分隔条；最前面还没有分隔条的消息，取后面第一个分隔条的时间。
 */
public final class ChatHistory {

    public static final class Item {
        public final boolean header;
        public final long time;      // 只对分隔条有意义
        public final String sender;  // 只对消息有意义
        public final String text;
        final String key;
        public long ts;              // 拼好后给消息配上的时间

        private Item(boolean header, long time, String sender, String text) {
            this.header = header;
            this.time = time;
            this.sender = sender == null ? "" : sender;
            this.text = text == null ? "" : text;
            this.key = header ? "T|" + this.text : "M|" + this.sender + "|" + this.text;
        }

        public static Item header(String text, long time) { return new Item(true, time, null, text); }
        public static Item message(String sender, String text) { return new Item(false, 0, sender, text); }
    }

    private final List<Item> all = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();

    /** 加入新读到的一页（屏幕从上到下的顺序）。返回新增的条目数，0 说明已经翻到顶或没翻动。 */
    public int addPage(List<Item> page) {
        if (page == null || page.isEmpty()) return 0;
        int overlap = -1;
        for (int i = 0; i < page.size(); i++) {
            if (keys.contains(page.get(i).key)) { overlap = i; break; }
        }
        List<Item> fresh = new ArrayList<>();
        int end = overlap < 0 ? page.size() : overlap;
        for (int i = 0; i < end; i++) {
            Item it = page.get(i);
            // 同一页里重复出现的条目（如两条"收到"）只留一条，避免和下一页对不上。
            if (keys.add(it.key)) fresh.add(it);
        }
        if (all.isEmpty() && overlap < 0) {
            all.addAll(fresh);
        } else {
            all.addAll(0, fresh);
        }
        return fresh.size();
    }

    /** 已读到的最早一个分隔条时间；还没有分隔条返回 0。 */
    public long oldestTime() {
        for (Item it : all) if (it.header && it.time > 0) return it.time;
        return 0;
    }

    public int size() {
        int n = 0;
        for (Item it : all) if (!it.header) n++;
        return n;
    }

    /** 按时间从早到晚给出所有消息，并配好时间（ts）。fallback 用于整段都没有分隔条的情况。 */
    public List<Item> messages(long fallback) {
        long firstHeader = 0;
        for (Item it : all) if (it.header && it.time > 0) { firstHeader = it.time; break; }
        long current = firstHeader > 0 ? firstHeader : fallback;
        List<Item> out = new ArrayList<>();
        for (Item it : all) {
            if (it.header) {
                if (it.time > 0) current = it.time;
                continue;
            }
            it.ts = current;
            out.add(it);
        }
        return out;
    }
}
