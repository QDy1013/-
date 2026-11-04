package com.nopalou.replay;

/** 回放器通知编号：同时挂着的通知不超过 MAX_LIVE 条（安卓每个 App 上限约 50 条，超出直接丢弃）。 */
public final class NotifIds {
    public static final int MAX_LIVE = 20;
    private NotifIds() { }
    /** 第 index 条消息用的通知编号。 */
    public static int idFor(int index) { return index + 1; }
    /** 发完第 index 条后要撤掉的编号；没有返回 -1。 */
    public static int cancelFor(int index) { return index >= MAX_LIVE ? idFor(index - MAX_LIVE) : -1; }
}
