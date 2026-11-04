package com.nopalou;

import android.app.Notification;
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
        // 真机问题：手机上所有 App 的通知都被读了进来，这里先按来源过滤。
        if (sbn == null || !NotifFilter.accept(sbn.getPackageName())) return;

        // 取引擎可能失败（模型没装好等），失败就跳过这条通知，不影响系统。
        Engine eng;
        try {
            eng = EngineHolder.get(this);
        } catch (Exception e) {
            return;
        }
        // 引擎或通知本身不可用时直接返回。
        if (eng == null || sbn.getNotification() == null) return;

        // 通知对象后面要用它的 flags 和 extras。
        Notification n = sbn.getNotification();
        // 没有 extras 就没有可读内容。
        Bundle ex = n.extras;
        if (ex == null) return;

        // 系统生成的分组汇总通知只有"3条新消息"这类计数，不是真实消息。
        boolean isSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;

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
        if (parsed == null || parsed.isEmpty()) return;

        // 依次处理每条解析结果。
        for (NotifParser.Parsed p : parsed) {
            // 解析器保证 sender 和 body 不为 null。
            String sender = p.sender;
            // 正文。
            String body = p.body;
            // 优先用消息自带的发送时间，没有就退回这条通知的发布时间。
            long sentAt = p.time > 0 ? p.time : System.currentTimeMillis();
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
        // 一次通知只存一次盘，不按消息条数重复写。
        EngineHolder.save(this);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        EngineHolder.listenerConnected = true;
        EngineHolder.refreshCards();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        EngineHolder.listenerConnected = false;
        EngineHolder.refreshCards();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // 撤回/删除不作为待办来源；原文在卡片里已经留存
    }
}
