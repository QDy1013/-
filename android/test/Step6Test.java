package com.nopalou;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 时间解析跨端验收（Java 侧）：读 eval/time_cases.tsv，逐条调 Engine.parseDeadline。
 *
 * 同一份用例由 eval/test_time_cases.py 在 Python 侧跑，两边必须给出同样的
 * "yyyy-MM-dd HH:mm"；对不上说明两端口径已经分叉。
 *
 * 判定口径：
 *   d.ok() == false            → 视为 NONE（应判"待确认"，不给时间）
 *   d.ok() == true             → 把 d.value 按本地时区格式化成 "yyyy-MM-dd HH:mm" 再比
 *
 * 用法（在 android 目录下）：
 *   javac -encoding UTF-8 -d build/test app/src/com/nopalou/Engine.java test/Step6Test.java
 *   java -cp build/test com.nopalou.Step6Test .
 * 参数是"项目根目录下的某个目录"，脚本按 参数/../eval/time_cases.tsv 找用例。
 */
public class Step6Test {

    static int pass = 0, fail = 0;

    static final String FMT = "yyyy-MM-dd HH:mm";
    static final String NONE = "NONE";

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name + "   " + detail); }
    }

    /** "yyyy-MM-dd HH:mm" → 毫秒（本地时区）。 */
    static long millis(String s) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat(FMT);
        f.setLenient(false);
        return f.parse(s.trim()).getTime();
    }

    /** 约定的用例路径优先；找不到时退回几个常见工作目录，避免因 cd 位置白跑。 */
    static String resolve(String arg) {
        String p = arg + "/../eval/time_cases.tsv";
        if (new File(p).isFile()) return p;
        for (String s : new String[]{"eval/time_cases.tsv", "../eval/time_cases.tsv"}) {
            if (new File(s).isFile()) return s;
        }
        return p;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("用法：java com.nopalou.Step6Test <项目根目录下的目录，例如 . 或 android>");
            System.exit(2);
        }

        String tsv = resolve(args[0]);
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(tsv), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 3) continue;
                for (int i = 0; i < 3; i++) p[i] = p[i].trim();
                if ("text".equals(p[0]) && "send".equals(p[1])) continue;   // 表头
                rows.add(p);
            }
        }

        System.out.println("== 时间解析（" + tsv + "，" + rows.size() + " 条）==");
        SimpleDateFormat out = new SimpleDateFormat(FMT);
        for (String[] r : rows) {
            String text = r[0], expect = r[2];
            String got;
            try {
                Engine.Deadline d = Engine.parseDeadline(text, millis(r[1]));
                got = d.ok() ? out.format(new Date(d.value)) : NONE;
            } catch (Exception ex) {
                check("「" + text + "」→ " + expect, false, "用例格式错误：" + ex);
                continue;
            }
            check("「" + text + "」→ " + expect, got.equals(expect), "实际 " + got);
        }

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
