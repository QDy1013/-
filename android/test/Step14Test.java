package com.nopalou;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** 卡片状态筛选、关键词匹配及长列表。 */
public final class Step14Test {
    private static final long NOW = 1_800_000_000_000L;
    private static int failures;
    private static Method queryCards;
    private static Class<?> scopeType;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "✓ " : "✗ ") + name);
        if (!ok) failures++;
    }

    private static Engine.Card card(Engine e, String title, String kind, long deadline, boolean done) {
        Engine.Card c = new Engine.Card();
        c.title = title;
        c.kind = kind;
        c.deadline = deadline;
        c.done = done;
        e.all().add(c);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<Engine.Card> query(Engine e, String keywords, String scope, int limit) throws Exception {
        return (List<Engine.Card>) queryCards.invoke(e, keywords, scopeType.getField(scope).get(null), limit, NOW);
    }

    public static void main(String[] args) throws Exception {
        Engine e;
        try (InputStream model = Files.newInputStream(Paths.get(args[0], "app", "assets", "model.tsv"))) {
            e = new Engine(model);
        }
        Engine.Card done = card(e, "周报", "todo", NOW - 10, true);
        Engine.Card late = card(e, "作业", "todo", NOW - 1, false);
        Engine.Card jl = card(e, "接龙", "jielong", NOW + 300, false);
        Engine.Card equal = card(e, "到点", "todo", NOW, false);
        Engine.Card unknown = card(e, "读书", "todo", 0, false);
        Engine.Card exam = card(e, "期末考试", "exam", NOW + 150, false);
        exam.groupName = "数据库课程";
        exam.sender = "王老师";
        exam.channel = "腾讯会议";
        exam.place = "教学楼A101";
        exam.sourceText = "这个词只出现在原文 zprivate";
        e.editCard(exam, "DB 期末", null);
        List<Engine.Card> rank = Arrays.asList(jl, equal, exam, unknown, late, done);
        check("现有排序提供六张完整测试卡", e.ranked(30, NOW).equals(rank));
        try {
            scopeType = Class.forName("com.nopalou.Engine$CardScope");
            queryCards = Engine.class.getMethod("queryCards", String.class, scopeType, int.class, long.class);
        } catch (ReflectiveOperationException ex) {
            check("存在状态筛选与搜索入口", false);
            System.exit(1);
        }
        check("全部状态保留现有优先顺序", query(e, "", "ALL", 30).equals(rank));
        check("未完成包含过期卡但排除已完成", query(e, "", "OPEN", 30)
                .equals(Arrays.asList(jl, equal, exam, unknown, late)));
        check("逾期只包含未完成且时间严格早于当前的卡",
                query(e, "", "OVERDUE", 30).equals(Arrays.asList(late)));
        check("已完成独立筛选", query(e, "", "COMPLETED", 30).equals(Arrays.asList(done)));
        check("搜索忽略大小写与首尾空白，多个词可跨字段匹配",
                query(e, "  db   王老师 A101  ", "ALL", 30).equals(Arrays.asList(exam)));
        check("原识别标题、群名和渠道仍可搜索",
                query(e, "期末考试 数据库课程 腾讯会议", "ALL", 30).equals(Arrays.asList(exam)));
        check("关键词与状态同时生效", query(e, "DB", "COMPLETED", 30).isEmpty());
        check("原消息正文不参加搜索，空字段不会报错", query(e, "zprivate", "ALL", 30).isEmpty());
        check("非正的显示上限返回空列表", query(e, "", "ALL", 0).isEmpty()
                && query(e, "", "ALL", -1).isEmpty());

        for (int i = 0; i < 35; i++) card(e, "日常作业 " + i, "todo", NOW + 2 + i, false);
        check("查找超过首页上限的卡时先过滤再取前几项",
                !e.ranked(30, NOW).contains(exam) && query(e, "DB", "ALL", 1).equals(Arrays.asList(exam)));
        check("加载更多时按同一个顺序扩展前缀",
                query(e, "", "ALL", 40).subList(0, 30).equals(query(e, "", "ALL", 30)));
        if (failures > 0) System.exit(1);
    }
}
