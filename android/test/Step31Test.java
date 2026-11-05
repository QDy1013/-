package com.nopalou;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** 搜索优化与原拼接语义独立对照，涵盖 Unicode、人工标题、筛选和分页。 */
public final class Step31Test {
    private static final long NOW = 1_800_000_000_000L;
    private static int failures;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static boolean matches(Engine.Card c, String query) {
        StringBuilder old = new StringBuilder();
        for (String field : new String[]{c.displayTitle(), c.title, c.groupName, c.sender, c.channel, c.place}) {
            if (field != null) old.append(field).append('\n');
        }
        String text = old.toString().toLowerCase(Locale.ROOT);
        for (String term : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!text.contains(term)) return false;
        }
        return true;
    }

    private static List<Engine.Card> expected(Engine engine, String query, Engine.CardScope scope, int limit) {
        List<Engine.Card> result = new ArrayList<>();
        for (Engine.Card c : engine.ranked(Integer.MAX_VALUE, NOW)) {
            if (scope == Engine.CardScope.OPEN && c.done) continue;
            if (scope == Engine.CardScope.COMPLETED && !c.done) continue;
            if (scope == Engine.CardScope.OVERDUE && (c.done || c.deadline <= 0 || c.deadline >= NOW)) continue;
            if (matches(c, query)) result.add(c);
            if (result.size() == limit) break;
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        Engine engine;
        try (InputStream weights = Files.newInputStream(Paths.get(args[0], "app/assets/model.tsv"))) {
            engine = new Engine(weights);
        }
        Random random = new Random(931);
        for (int i = 0; i < 500; i++) {
            Engine.Card c = new Engine.Card();
            c.id = "c" + i;
            c.kind = i % 11 == 0 ? "jielong" : "todo";
            c.title = i % 3 == 0 ? "DB 报告 İΣ " + i : "实验 作业 " + i;
            c.groupName = i % 2 == 0 ? "数据库系统" : "计算机网络";
            c.sender = i % 5 == 0 ? null : "王老师";
            c.channel = i % 7 == 0 ? "EMAIL" : null;
            c.place = i % 8 == 0 ? "A101" : null;
            c.sourceText = "只在原文出现 privateword";
            c.deadline = i % 13 == 0 ? 0 : NOW + random.nextInt(5) - 2;
            c.done = i % 4 == 0;
            c.confidence = random.nextDouble();
            engine.all().add(c);
            if (i % 6 == 0) engine.editCard(c, "人工修改 " + i, null);
        }
        for (String query : new String[]{"", "DB", "报告 数据库", "王老师 EMAIL", "A101", "人工修改",
                "İ", "i\u0307", "σ", "ς", "privateword", "  实验\t作业\n王老师  "}) {
            boolean same = true;
            for (Engine.CardScope scope : Engine.CardScope.values()) {
                for (int limit : new int[]{1, 30, 500}) {
                    same &= expected(engine, query, scope, limit).equals(engine.queryCards(query, scope, limit, NOW));
                }
            }
            check("各状态与分页保持搜索原义 " + query.trim().replaceAll("\\s+", " "), same);
        }
        if (failures > 0) System.exit(1);
    }
}
