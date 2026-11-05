package com.nopalou;

import android.app.Notification;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据源一：通知监听（方案书第 5 节主数据源）。
 * 用户授权后可以读到其他 App 发出的通知，不需要打开群。
 *
 * 限制（方案书里写明）：免打扰群不发通知；同一会话的通知会合并替换；
 * 用户关掉"显示消息详情"后没有正文。所以还要配无障碍读屏兜底。
 *
 * 这里只负责从通知里把字段取出来，具体怎么切分发送者和正文交给 NotifParser。
 */
public class NotifListener extends NotificationListenerService {

    /** 会话标题的键。API 28+ 才有 EXTRA_CONVERSATION_TITLE 常量，这里直接用字符串，避免版本问题。 */
    private static final String KEY_CONVERSATION_TITLE = "android.conversationTitle";

    /** MessagingStyle 多条消息数组的键。 */
    private static final String KEY_MESSAGES = "android.messages";

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 整个方法体都包在 try/catch(Throwable) 里：这里一抛异常，系统就会解绑服务，之后就再也收不到消息。
        try {
            // 空通知理论上不会出现，保护一下，避免下面取包名时空指针。
            if (sbn == null) {
                // 极少数异常路径，记一条跳过方便排查。
                EngineHolder.diag(this, "跳过", "通知对象为空");
                // 没有通知对象，直接结束。
                return;
            }
            // 真机问题：手机上所有 App 的通知都被读了进来，这里先按来源过滤。
            // 来源不在白名单时不记日志，否则别的 App 的通知会把诊断日志刷屏。
            if (!NotifFilter.accept(sbn.getPackageName())) return;

            // 取引擎可能失败（模型没装好等），失败就跳过这条通知，不影响系统。
            Engine eng;
            try {
                // 引擎是单例，第一次调用会加载模型和卡片存档。
                eng = EngineHolder.get(this);
            } catch (Throwable e) {
                // 建引擎过程中任何异常都当"引擎不可用"处理，绝不外抛。
                EngineHolder.diag(this, "跳过", "引擎不可用");
                // 没有引擎就处理不了消息，直接结束。
                return;
            }
            // 引擎为空说明模型没加载成功，这条通知只能放弃。
            if (eng == null) {
                // 记下跳过原因，界面上能看出是引擎的问题。
                EngineHolder.diag(this, "跳过", "引擎不可用");
                // 结束本次处理。
                return;
            }
            // 通知对象本身缺失时也没有任何可读内容。
            if (sbn.getNotification() == null) {
                // 记一条跳过，说明是通知对象缺失而不是来源被过滤。
                EngineHolder.diag(this, "跳过", "没有通知对象");
                // 结束本次处理。
                return;
            }

            // 通知对象后面要用它的 flags 和 extras。
            Notification n = sbn.getNotification();
            // 没有 extras 就没有可读内容。
            Bundle ex = n.extras;
            if (ex == null) {
                // 记下跳过原因，这种情况常见于系统占位通知。
                EngineHolder.diag(this, "跳过", "没有 extras");
                // 没有 extras 什么都解析不出来。
                return;
            }

            // 系统生成的分组汇总通知只有"3条新消息"这类计数，不是真实消息。
            boolean isSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;

            // 学习平台自己发的通知：不是群聊，不拆“发送者：正文”，整条交给平台解析器。
            if (Engine.PlatformNotice.isPlatform(sbn.getPackageName())) {
                handlePlatform(eng, sbn, ex, isSummary);
                return;
            }

            // 标题优先取会话标题：群名通常放在这里，而不是 EXTRA_TITLE。
            CharSequence titleCs = ex.getCharSequence(KEY_CONVERSATION_TITLE);
            // 会话标题为空或只有空白时，退回普通标题。
            if (titleCs == null || titleCs.toString().trim().isEmpty()) {
                titleCs = ex.getCharSequence(Notification.EXTRA_TITLE);
            }
            // 统一转成去掉首尾空白的字符串，null 当空串。
            String title = titleCs == null ? "" : titleCs.toString().trim();

            // 正文优先取长文本（展开后的完整内容）。
            CharSequence textCs = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            // 没有长文本再用短文本。
            if (textCs == null) textCs = ex.getCharSequence(Notification.EXTRA_TEXT);
            // 正文可能整段缺失，这里只做 null 保护，空白由解析器清洗。
            String text = textCs == null ? "" : textCs.toString();

            // 多个联系人的多条消息（MessagingStyle），三个数组保持同长。
            String[] msgSenders = null;
            String[] msgTexts = null;
            long[] msgTimes = null;

            // 解析成组消息时任何字段异常都当作"没有多条消息"，不能影响单条解析。
            try {
                // 取通知里的消息数组；老系统或非会话通知会拿到 null。
                Parcelable[] raw = ex.getParcelableArray(KEY_MESSAGES);
                // 数组存在且非空时才逐条读。
                if (raw != null && raw.length > 0) {
                    // 先用列表收集，避免数组里有非 Bundle 元素时出现空洞。
                    List<String> senders = new ArrayList<String>();
                    List<String> texts = new ArrayList<String>();
                    List<Long> times = new ArrayList<Long>();
                    // 按通知里的原始顺序遍历。
                    for (Parcelable p : raw) {
                        // 只处理真正是 Bundle 的元素，其余跳过。
                        if (!(p instanceof Bundle)) continue;
                        // 每一条消息是一个 Bundle。
                        Bundle b = (Bundle) p;
                        // 正文取 CharSequence，缺失时留 null 交给解析器清洗。
                        CharSequence t = b.getCharSequence("text");
                        // 发送者可能不存在，取不到就留 null。
                        CharSequence s = b.getCharSequence("sender");
                        // 时间缺失时 getLong 返回 0，正好表示未知。
                        long tm = b.getLong("time");
                        // 三个列表同步追加，保证下标一一对应。
                        senders.add(s == null ? null : s.toString());
                        texts.add(t == null ? null : t.toString());
                        times.add(tm);
                    }
                    // 收集到内容才转成数组。
                    if (!texts.isEmpty()) {
                        // 发送者数组与正文数组等长。
                        msgSenders = senders.toArray(new String[senders.size()]);
                        // 正文数组。
                        msgTexts = texts.toArray(new String[texts.size()]);
                        // 时间数组与正文数组等长。
                        msgTimes = new long[times.size()];
                        // 逐个拷贝时间，避免拆箱时出现空指针。
                        for (int i = 0; i < times.size(); i++) {
                            msgTimes[i] = times.get(i);
                        }
                    }
                }
            } catch (Exception e) {
                // 解析多条消息出错就当没有多条消息，继续走单条逻辑。
                msgSenders = null;
                msgTexts = null;
                msgTimes = null;
            }

            // 把取到的字段交给纯逻辑解析器，返回可能为空的消息列表。
            List<NotifParser.Parsed> parsed = NotifParser.parse(title, text,
                    msgSenders, msgTexts, msgTimes, isSummary);

            // 群名：标题为空说明通知没给群名，用包名兜底。
            String group = title.isEmpty() ? sbn.getPackageName() : title;
            // 没有可用消息就什么都不做（也不刷新"最近一次自动收到"）。
            if (parsed == null || parsed.isEmpty()) {
                // 汇总通知只有计数没有正文，单独记一种原因，方便区分。
                if (isSummary) {
                    EngineHolder.diag(this, "跳过", "汇总通知");
                } else {
                    // 图片/表情/纯计数等通知解析不出文字，归到同一类原因。
                    EngineHolder.diag(this, "跳过", "没有可用消息（图片/表情/计数/空）");
                }
                // 没有可用消息，结束本次处理。
                return;
            }

            // 依次处理每条解析结果。
            for (NotifParser.Parsed p : parsed) {
                // 解析器保证 sender 和 body 不为 null。
                String sender = p.sender;
                // 正文。
                String body = p.body;
                // 优先用消息自带的发送时间，没有就退回这条通知的发布时间。
                long sentAt = p.time > 0 ? p.time : sbn.getPostTime();
                if (sentAt <= 0) sentAt = System.currentTimeMillis();
                // 消息编号：同一群同一发送者同一正文同一时间视为同一条，避免重复出卡。
                String id = "n" + group.hashCode() + "_"
                        + (p.sender + "|" + p.body).hashCode() + "_"
                        + (p.time > 0 ? p.time : sbn.getPostTime());
                // 交给引擎，参数顺序与原来一致：编号、群 ID、群名、发送者、正文、包名、时间。
                eng.process(new Engine.Msg(id, NotifFilter.groupId(sbn.getPackageName(), group),
                        group, sender, body, sbn.getPackageName(), sentAt));
            }

            // 至少处理了一条消息，才记录"最近一次自动收到"。
            EngineHolder.lastReceivedAt = System.currentTimeMillis();
            // 记录这条通知的群名。
            EngineHolder.lastReceivedGroup = group;
            // 立刻把这两个值落盘，App 被系统杀掉重启后界面仍能显示"最近一次自动收到"。
            EngineHolder.persistLast(this);
            // 一次通知只存一次盘，不按消息条数重复写。
            if (!EngineHolder.saveIfChanged(this)) {
                EngineHolder.diag(this, "出错", "卡片保存失败，等待下次通知重试");
            }

            // 全部处理成功才记"收到"，detail 写群名和条数，方便肉眼核对。
            EngineHolder.diag(this, "收到", group + " · " + parsed.size() + "条");
        } catch (Throwable t) {
            // 任何没被上面接住的异常都在这里落地，只记诊断日志，绝不向外抛。
            EngineHolder.diag(this, "出错", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * 学习平台通知：标题 + 正文（以及收件箱样式里的多行）逐条交给引擎。
     * 每条原文都记进诊断日志（类别“平台”），用来收集真实样本、校准解析规则；只存本机。
     */
    private void handlePlatform(Engine eng, StatusBarNotification sbn, Bundle ex, boolean isSummary) {
        String pkg = sbn.getPackageName();
        String app = Engine.PlatformNotice.appName(pkg);
        if (isSummary) {
            EngineHolder.diag(this, "跳过", app + " 汇总通知");
            return;
        }
        CharSequence titleCs = ex.getCharSequence(Notification.EXTRA_TITLE);
        String title = titleCs == null ? "" : titleCs.toString().trim();
        List<String> bodies = new ArrayList<String>();
        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 1) {
            for (CharSequence l : lines) {
                if (l != null && !l.toString().trim().isEmpty()) bodies.add(l.toString().trim());
            }
        }
        if (bodies.isEmpty()) {
            CharSequence textCs = ex.getCharSequence(Notification.EXTRA_BIG_TEXT);
            if (textCs == null) textCs = ex.getCharSequence(Notification.EXTRA_TEXT);
            String text = textCs == null ? "" : textCs.toString().trim();
            if (!text.isEmpty() || !title.isEmpty()) bodies.add(text);
        }
        if (bodies.isEmpty()) {
            EngineHolder.diag(this, "跳过", app + " 通知没有文字");
            return;
        }
        int before = eng.all().size();
        for (int i = 0; i < bodies.size(); i++) {
            String body = bodies.get(i);
            EngineHolder.diag(this, "平台", app + "｜" + title + "｜" + body);
            String id = "p" + pkg.hashCode() + "_" + (title + "|" + body).hashCode() + "_" + sbn.getPostTime() + "_" + i;
            long at = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
            eng.processPlatform(new Engine.Msg(id, NotifFilter.groupId(pkg, title), title, app, body, pkg, at));
        }
        EngineHolder.lastReceivedAt = System.currentTimeMillis();
        EngineHolder.lastReceivedGroup = app;
        EngineHolder.persistLast(this);
        if (!EngineHolder.saveIfChanged(this)) {
            EngineHolder.diag(this, "出错", "卡片保存失败，等待下次通知重试");
        }
        EngineHolder.diag(this, "收到", app + " · " + bodies.size() + "条 · 卡片 " + before + "→" + eng.all().size());
    }

    @Override
    public void onListenerConnected() {
        // 先交给父类，保持系统原有行为。
        super.onListenerConnected();
        // 标记服务已连上，主界面的状态显示靠它。
        EngineHolder.listenerConnected = true;
        // 连接状态变了，让界面重画一次。
        EngineHolder.refreshCards();
        // 记一条连接日志，确认服务真的被系统绑上了。
        EngineHolder.diag(this, "连接", "通知服务已连接");
    }

    @Override
    public void onListenerDisconnected() {
        // 先交给父类，保持系统原有行为。
        super.onListenerDisconnected();
        // 标记服务已断开，界面会提示正在自动重连。
        EngineHolder.listenerConnected = false;
        // 断开状态立刻反映到界面上。
        EngineHolder.refreshCards();
        // 记一条断开日志，便于对照后面的重连是否生效。
        EngineHolder.diag(this, "断开", "通知服务已断开");
        // 系统解绑后不会自己回来，这里主动请求重新绑定。
        try {
            // 请求系统重新绑定本服务，多数情况下能立刻恢复接收通知。
            requestRebind(new ComponentName(this, NotifListener.class));
            // 请求已经发出，记一条重连日志。
            EngineHolder.diag(this, "重连", "断开后自动请求重新绑定");
        } catch (Throwable t) {
            // 重绑失败（系统限制等）只记日志，不能让服务再崩一次。
            EngineHolder.diag(this, "出错", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 撤回/删除不作为待办来源；原文在卡片里已经留存
    }
}
