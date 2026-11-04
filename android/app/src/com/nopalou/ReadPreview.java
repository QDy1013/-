package com.nopalou;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 屏幕文字在预览中暂存，只有用户选中的条目才进入引擎。 */
final class ReadPreview {
    private static final int MAX_LINES = 40;
    private static final int MAX_LINE_LENGTH = 500;
    private static final long MAX_AGE_MS = 5 * 60_000L;

    private final String pkg;
    private final long capturedAt;
    final List<String> lines;

    private ReadPreview(String pkg, long capturedAt, Set<String> lines) {
        this.pkg = pkg;
        this.capturedAt = capturedAt;
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
    }

    static ReadPreview create(String pkg, long capturedAt, List<String> nodes) {
        if (!NotifFilter.accept(pkg)) return null;
        Set<String> cleaned = new LinkedHashSet<>();
        for (String node : nodes) {
            String line = node.trim();
            if (!line.isEmpty() && line.length() <= MAX_LINE_LENGTH) cleaned.add(line);
            if (cleaned.size() == MAX_LINES) break;
        }
        return cleaned.isEmpty() ? null : new ReadPreview(pkg, capturedAt, cleaned);
    }

    boolean isExpired(long now) {
        return now < capturedAt || now - capturedAt >= MAX_AGE_MS;
    }

    int importSelection(Engine engine, String group, boolean[] selection, long now) {
        String name = group.trim();
        if (name.isEmpty() || selection.length != lines.size() || isExpired(now)) return 0;
        String groupId = "screen|" + NotifFilter.groupId(pkg, name);
        int count = 0;
        for (int i = 0; i < selection.length; i++) {
            if (!selection[i]) continue;
            engine.process(new Engine.Msg("screen-" + UUID.randomUUID(), groupId, name, "读屏",
                    lines.get(i), pkg, capturedAt));
            count++;
        }
        return count;
    }
}
