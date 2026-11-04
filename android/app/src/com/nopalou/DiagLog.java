package com.nopalou;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断日志的纯逻辑：固定容量的环形日志、按类别累计计数、以及落盘用的文本序列化。
 * 纯 Java，不依赖 android，方便在电脑上直接跑测试。
 * 通知线程和界面线程会同时访问，所有读写都在同一把锁 LOCK 上。
 */
public final class DiagLog {

    /** 日志最多保留多少条，超出的丢最老的。 */
    private static final int CAPACITY = 50;

    /** 所有 public 方法共用这一把锁，保证多线程下状态一致。 */
    private static final Object LOCK = new Object();

    /** dump 文本的第一行，用来识别格式并挡住乱码。 */
    private static final String HEADER = "NOPALOU-DIAG-1";

    /** 计数行的前缀，这一行保存各 kind 的累计条数。 */
    private static final String COUNT_TAG = "C";

    /** 日志行的前缀，这一行是一条日志。 */
    private static final String ENTRY_TAG = "E";

    /** 日志本体，下标 0 是最老的一条，最后一条是最新的。 */
    private static final List<Entry> ENTRIES = new ArrayList<Entry>();

    /** 各 kind 的累计计数，不受 CAPACITY 限制，只有 reset 会清零。 */
    private static final Map<String, Integer> COUNTS = new HashMap<String, Integer>();

    /** 工具类，不实例化。 */
    private DiagLog() {
    }

    /** 一条诊断日志。 */
    public static final class Entry {
        /** 记录时间（毫秒），由调用方给出。 */
        public final long time;
        /** 类别，例如“收到”“跳过”。 */
        public final String kind;
        /** 明细，可能含换行和制表符。 */
        public final String detail;

        /** 保存一条日志，字段对外只读。 */
        Entry(long time, String kind, String detail) {
            this.time = time;
            this.kind = kind;
            this.detail = detail;
        }
    }

    /** 清空日志和全部计数，回到刚启动的状态。 */
    public static void reset() {
        // 整个清空过程持锁，避免清到一半被别的线程读走。
        synchronized (LOCK) {
            // 丢掉所有日志。
            ENTRIES.clear();
            // 丢掉所有累计计数。
            COUNTS.clear();
        }
    }

    /** 记一条日志；kind 和 detail 传 null 时按空串保存，不抛异常。 */
    public static void record(long now, String kind, String detail) {
        // 加锁后同时改日志和计数，保证两者同步。
        synchronized (LOCK) {
            // null 的类别统一成空串，避免后面序列化出 null。
            String k = kind == null ? "" : kind;
            // null 的明细统一成空串，保证 Entry 字段永不为 null。
            String d = detail == null ? "" : detail;
            // 追加到末尾，最新的永远在最后。
            ENTRIES.add(new Entry(now, k, d));
            // 超过容量就把最老的一条移掉。
            while (ENTRIES.size() > CAPACITY) {
                ENTRIES.remove(0);
            }
            // 取出这个类别已有的累计数。
            Integer old = COUNTS.get(k);
            // 没有记录过就从 0 开始加。
            int next = (old == null ? 0 : old.intValue()) + 1;
            // 写回新的累计数，这一步不受容量影响。
            COUNTS.put(k, Integer.valueOf(next));
        }
    }

    /** 最近 n 条日志，最新的在前；n<=0 返回空列表，n 超过现有条数就全返回。 */
    public static List<Entry> recent(int n) {
        // 结果列表先建好，任何情况下都返回非 null。
        List<Entry> result = new ArrayList<Entry>();
        // 持锁拷一份快照，避免返回过程中被别的线程改动。
        synchronized (LOCK) {
            // n<=0 时一条都不给。
            if (n <= 0) return result;
            // 从最新的一条往前取，最多取 n 条。
            for (int i = ENTRIES.size() - 1; i >= 0 && result.size() < n; i--) {
                // 逐条加入结果，顺序就是最新在前。
                result.add(ENTRIES.get(i));
            }
        }
        // 返回快照。
        return result;
    }

    /** 某一类的累计条数；没记过或 kind 为 null 时返回 0。 */
    public static int count(String kind) {
        // 加锁读计数，避免读到写了一半的 map。
        synchronized (LOCK) {
            // null 类别当作没记过。
            if (kind == null) return 0;
            // 取出累计数。
            Integer v = COUNTS.get(kind);
            // 没记过就是 0。
            return v == null ? 0 : v.intValue();
        }
    }

    /** 把日志和计数序列化成文本：第一行版本，第二行计数，之后每行一条日志。 */
    public static String dump() {
        // 用 StringBuilder 逐行拼，避免频繁创建字符串。
        StringBuilder b = new StringBuilder();
        // 加锁读一遍，保证日志和计数来自同一时刻。
        synchronized (LOCK) {
            // 第一行写版本标记，load 靠它识别格式。
            b.append(HEADER);
            // 换行进入计数行。
            b.append('\n');
            // 计数行前缀。
            b.append(COUNT_TAG);
            // 每个类别写一对“类别 计数”，字段之间用制表符分隔。
            for (Map.Entry<String, Integer> e : COUNTS.entrySet()) {
                // 分隔符。
                b.append('\t');
                // 类别名做转义，防止里面有制表符之类的字符。
                b.append(escape(e.getKey()));
                // 分隔符。
                b.append('\t');
                // 累计条数。
                b.append(e.getValue().intValue());
            }
            // 从最老的开始逐条写，load 时按同样顺序追加即可还原。
            for (Entry e : ENTRIES) {
                // 每条日志占一行。
                b.append('\n');
                // 日志行前缀。
                b.append(ENTRY_TAG);
                // 分隔符。
                b.append('\t');
                // 时间，十进制毫秒。
                b.append(e.time);
                // 分隔符。
                b.append('\t');
                // 类别做转义。
                b.append(escape(e.kind));
                // 分隔符。
                b.append('\t');
                // 明细做转义，换行、制表符、反斜杠都会变成两个字符。
                b.append(escape(e.detail));
            }
        }
        // 返回拼好的文本。
        return b.toString();
    }

    /** 从 dump 的文本恢复；先清空再读，null、空串或格式损坏时结果是空日志，且不抛异常。 */
    public static void load(String s) {
        // 整个恢复过程持锁，避免别的线程看到半套数据。
        synchronized (LOCK) {
            // 先清空，失败也保持空状态，满足“load 先 reset”。
            ENTRIES.clear();
            // 计数一起清空。
            COUNTS.clear();
            // null 和空串直接按空日志处理。
            if (s == null || s.isEmpty()) return;
            // 解析过程中任何异常都吞掉，结果退回空日志。
            try {
                // 按换行切成行，只按 \n 切，\r 留给下面统一去掉。
                String[] lines = s.split("\n", -1);
                // 至少要有一行版本标记，否则是乱码。
                if (lines.length < 1) return;
                // 版本标记必须完全一致，不然当作损坏。
                if (!HEADER.equals(chomp(lines[0]))) return;
                // 第二行必须是计数行，否则格式损坏。
                if (lines.length < 2) return;
                // 取出计数行并去掉行尾回车。
                String countLine = chomp(lines[1]);
                // 计数行必须以计数前缀开头。
                if (!countLine.startsWith(COUNT_TAG)) return;
                // 按制表符切成“前缀 类别 计数 类别 计数 ...”。
                String[] parts = countLine.split("\t", -1);
                // 前缀之后必须是成对出现的字段。
                if ((parts.length - 1) % 2 != 0) return;
                // 先建一个临时 map，全部解析成功后再一次性提交。
                Map<String, Integer> newCounts = new HashMap<String, Integer>();
                // 逐对读取类别和计数。
                for (int i = 1; i + 1 < parts.length; i += 2) {
                    // 类别名反向转义还原。
                    String k = unescape(parts[i]);
                    // 计数解析成整数，失败会抛异常走到 catch。
                    int v = Integer.parseInt(parts[i + 1]);
                    // 负数属于损坏数据。
                    if (v < 0) return;
                    // 放入临时 map。
                    newCounts.put(k, Integer.valueOf(v));
                }
                // 先把计数提交，日志行会在此基础上补足缺失的类别。
                COUNTS.putAll(newCounts);
                // 剩下的行都是日志行。
                for (int i = 2; i < lines.length; i++) {
                    // 去掉行尾回车，兼容 Windows 换行。
                    String line = chomp(lines[i]);
                    // 空行跳过，末尾多一个换行也不会出错。
                    if (line.isEmpty()) continue;
                    // 每行必须以日志前缀开头。
                    if (!line.startsWith(ENTRY_TAG + "\t")) throw new IllegalArgumentException("bad line");
                    // 切成“前缀 时间 类别 明细”，-1 保留末尾空字段。
                    String[] f = line.split("\t", -1);
                    // 字段数必须正好四个。
                    if (f.length != 4) throw new IllegalArgumentException("bad fields");
                    // 时间解析，失败会抛异常。
                    long time = Long.parseLong(f[1]);
                    // 类别反向转义。
                    String kind = unescape(f[2]);
                    // 明细反向转义，换行和制表符在这里还原。
                    String detail = unescape(f[3]);
                    // 追加到末尾，保持原来的从老到新顺序。
                    ENTRIES.add(new Entry(time, kind, detail));
                    // 日志超过容量时丢最老的，和 record 行为一致。
                    while (ENTRIES.size() > CAPACITY) {
                        ENTRIES.remove(0);
                    }
                    // 计数行里没有这个类别时补一个 0，保证 count 有意义。
                    if (!COUNTS.containsKey(kind)) {
                        COUNTS.put(kind, Integer.valueOf(0));
                    }
                }
            } catch (Throwable t) {
                // 任何解析异常都按“格式损坏”处理：清空回空日志。
                ENTRIES.clear();
                COUNTS.clear();
            }
        }
    }

    /** 去掉行尾的 \r，兼容不同平台的换行。 */
    private static String chomp(String s) {
        // 只有以 \r 结尾时才截掉一个字符。
        if (s.endsWith("\r")) return s.substring(0, s.length() - 1);
        // 其他情况原样返回。
        return s;
    }

    /** 把换行、制表符、回车和反斜杠转成可打印的转义序列。 */
    private static String escape(String s) {
        // 预分配足够空间，减少扩容。
        StringBuilder b = new StringBuilder(s.length() + 8);
        // 逐个字符处理。
        for (int i = 0; i < s.length(); i++) {
            // 取出当前字符。
            char c = s.charAt(i);
            // 按字符种类选择转义写法。
            switch (c) {
                // 反斜杠必须最先转义，否则会破坏其他序列。
                case '\\':
                    b.append("\\\\");
                    break;
                // 制表符转成两个字符。
                case '\t':
                    b.append("\\t");
                    break;
                // 换行转成两个字符。
                case '\n':
                    b.append("\\n");
                    break;
                // 回车转成两个字符。
                case '\r':
                    b.append("\\r");
                    break;
                // 其他字符原样保留。
                default:
                    b.append(c);
                    break;
            }
        }
        // 返回转义后的文本。
        return b.toString();
    }

    /** escape 的逆操作；遇到不认识的转义序列就把后面的字符原样保留。 */
    private static String unescape(String s) {
        // 预分配空间。
        StringBuilder b = new StringBuilder(s.length());
        // 逐个字符扫描。
        for (int i = 0; i < s.length(); i++) {
            // 取出当前字符。
            char c = s.charAt(i);
            // 不是反斜杠就直接保留。
            if (c != '\\') {
                b.append(c);
                continue;
            }
            // 反斜杠在末尾说明内容被截断，按普通反斜杠处理。
            if (i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            // 取下一位转义标记并跳过它。
            char n = s.charAt(++i);
            // 按标记还原成真正的字符。
            switch (n) {
                // \\ 还原成反斜杠。
                case '\\':
                    b.append('\\');
                    break;
                // \t 还原成制表符。
                case 't':
                    b.append('\t');
                    break;
                // \n 还原成换行。
                case 'n':
                    b.append('\n');
                    break;
                // \r 还原成回车。
                case 'r':
                    b.append('\r');
                    break;
                // 不认识的序列：保留标记字符本身，尽量不丢信息。
                default:
                    b.append(n);
                    break;
            }
        }
        // 返回还原后的文本。
        return b.toString();
    }
}
