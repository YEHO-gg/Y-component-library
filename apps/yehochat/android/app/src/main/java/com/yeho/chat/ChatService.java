package com.yeho.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 前台常驻服务：真正持有 wss 连接、接收消息、发通知。
 * 关键：连接归服务所有，Activity 退到后台/销毁也不断线。
 * 断线后由 2 秒定时器自动重连。
 */
public class ChatService extends Service {

    public static final String CH_SERVICE = "yeho_service";
    /** 服务器聊天地址 */
    private static final String WSS = "wss://" + BuildConfig.CHAT_HOST + ":" + BuildConfig.CHAT_WS_PORT;
    /** 重连间隔：2 秒 */
    private static final long RECONNECT_MS = 2000;

    /** Activity 观察者（弱引用，避免内存泄漏） */
    private static ChatService INSTANCE;
    private static Observer observer;

    private Prefs prefs;
    private MsgStore store;
    private OkHttpClient client;
    private WebSocket ws;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            // 2 秒一次的定时器：没连上就重连（被封禁就不连了）
            if (!bannedStop && ws == null) {
                connect();
            }
            ui.postDelayed(this, RECONNECT_MS);
        }
    };

    private volatile boolean connected = false;
    private volatile boolean started = false;
    /** 被封禁：停止一切重连，不再骚扰服务器 */
    private volatile boolean bannedStop = false;
    /** 聊天界面是否在前台：前台时不弹消息通知 */
    private static volatile boolean uiForeground = false;
    /** 前台服务通知 id（唯一要保留的通知） */
    private static final int NOTIF_ID_SERVICE = 1001;

    /** Activity 侧实现，用于刷新 UI */
    public interface Observer {
        void onStatus(String s);
        void onNewMessage();
        void onLoginLost();
        void onBanned();
        /** 服务端返回的、需要让用户看到的提示（例如撤回失败的原因） */
        void onToast(String msg);
    }

    public static void setObserver(Observer o) { observer = o; }

    /** 当前真实状态文字：供 Activity 进入时主动同步，避免一直显示"连接中" */
    public static String statusText() {
        ChatService s = INSTANCE;
        if (s == null || !s.started) return "启动中…";
        if (s.bannedStop) return "账号已被封禁";
        if (s.connected) {
            try {
                if (new Prefs(s).dndOn()) return "免打扰中 · 仍保持连接";
            } catch (Exception ignored) { }
            return "在线";
        }
        return "连接中…";
    }

    /** 供 Activity 发消息用 */
    public static boolean send(String json) {
        ChatService s = INSTANCE;
        if (s == null || s.ws == null || !s.connected) return false;
        return s.ws.send(json);
    }

    /** 聊天界面进入前台：标记前台 + 清掉除常驻外的所有通知 */
    public static void onUiResume() {
        uiForeground = true;
        ChatService s = INSTANCE;
        if (s != null) s.clearMsgNotifs();
    }

    /** 聊天界面离开前台 */
    public static void onUiPause() {
        uiForeground = false;
    }

    /** 清除所有消息通知，只保留常驻的「在线」通知（id=1001） */
    private void clearMsgNotifs() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            android.service.notification.StatusBarNotification[] arr = nm.getActiveNotifications();
            if (arr == null) return;
            for (android.service.notification.StatusBarNotification sn : arr) {
                if (sn.getId() != NOTIF_ID_SERVICE) {
                    nm.cancel(sn.getTag(), sn.getId());
                }
            }
        } catch (Exception ignored) { }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        prefs = new Prefs(this);
        store = MsgStore.get(this, prefs);
        client = buildClient();
        new Thread(() -> store.load()).start();
        startForegroundNotif();
        started = true;
        ui.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!started) {
            started = true;
            ui.post(ticker);
        }
        // 点通知按钮：切换免打扰
        if (intent != null && ACTION_DND.equals(intent.getAction())) {
            Prefs p = new Prefs(this);
            p.setDndUntil(p.dndOn() ? 0L : -1L);
            refreshNotif();
        }
        // 界面改完免打扰：只刷新显示
        if (intent != null && ACTION_DND_REFRESH.equals(intent.getAction())) {
            refreshNotif();
        }
        return START_STICKY;   // 被杀后自动重启
    }

    // ---------- 连接 ----------

    private void connect() {
        if (bannedStop) return;
        if (ws != null) return;
        if (prefs.token() == null || prefs.token().isEmpty()) return;

        Request req = new Request.Builder().url(WSS).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                webSocket.send("{\"type\":\"login\",\"token\":\"" + prefs.token() + "\"}");
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handleMsg(text);
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                ws = null;
                notifyStatus("已断开，重连中…");
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                ws = null;
                notifyStatus("已断开，重连中…");
            }
        });
    }

    private void notifyStatus(String s) {
        final Observer o = observer;
        if (o != null) ui.post(() -> o.onStatus(s));
        updateNotifText(s);
    }

    private void handleMsg(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            String type = o.optString("type");
            if ("login_ok".equals(type)) {
                connected = true;
                notifyStatus(dndText(dndOn()));
                // 登录成功后主动做一次增量同步：只拉本地缺的那一段。
                // 服务端登录时还会自动推一次 history（旧行为，为兼容保留），
                // 两边都走 addUnique/墓碑过滤，不会重复 —— 这是预期内的。
                new Thread(() -> {
                    long since = store.latestTs();
                    send("{\"type\":\"sync\",\"since\":" + since + ",\"full\":false}");
                }).start();
            } else if ("banned".equals(type)) {
                // 账号被封禁：彻底停下，不再重连
                bannedStop = true;
                connected = false;
                final Observer ob = observer;
                if (ob != null) ui.post(ob::onBanned);
                notifyStatus("账号已被封禁");
                stopSelf();
            } else if ("error".equals(type)) {
                String m = o.optString("msg");
                if (m.contains("登录")) {
                    final Observer ob = observer;
                    if (ob != null) ui.post(ob::onLoginLost);
                    prefs.logout();
                    stopSelf();
                } else if (m.contains("撤回")) {
                    // 撤回失败（超时 / 不是本人 / 参数不全）：把服务端给的原因原样弹出来
                    final Observer ob = observer;
                    if (ob != null) ui.post(() -> ob.onToast(m));
                }
            } else if ("recall".equals(type)) {
                // 有人撤回了一条消息（也可能是本机撤回后服务端回广播）。
                String from = o.optString("from");
                String text = o.optString("text");
                long ts = o.optLong("ts", 0);
                if (ts > 1_000_000_000_000L) ts = ts / 1000;
                long rUid = o.optLong("uid", 0);

                // 撤回提示：服务端也落了一条 sys 行，这里本地补的是同一条 ——
                // ts 必须用服务端广播的 tip_ts（而不是本地 now），否则两边去重键
                // (ts, from, text) 对不上，重连补推 history 时会冒出第二条一模一样的提示。
                long tipTs = o.optLong("tip_ts", 0);
                if (tipTs > 1_000_000_000_000L) tipTs = tipTs / 1000;
                if (tipTs <= 0) tipTs = System.currentTimeMillis() / 1000;

                // 摘掉原消息，并把提示**插回原消息的位置**（不是追加到末尾）——
                // A/B/C 三条里撤回 B，提示要夹在 A 和 C 之间。详见 MsgStore.replaceWithTip。
                boolean placed = store.replaceWithTip(ts, rUid, from, text,
                        MsgStore.M.recallTip(tipTs, from, rUid));
                // 留一行日志：万一以后又出现"提示出来了但消息没消失"，从这里能直接
                // 看出是没匹配上（placed=false），而不是靠猜。
                android.util.Log.i("YehoRecall",
                        "recall ts=" + ts + " uid=" + rUid + " from=" + from
                                + " text=" + text + " placed=" + placed);
                // 记一笔"这条被撤回过"：引用它的消息渲染时会显示「原消息已撤回」，
                // 而不是继续显示那份早就失效的快照。
                store.markRecalled(ts, rUid);

                new Thread(() -> {
                    store.save();
                    store.saveRecalled();
                }).start();
                final Observer ob = observer;
                if (ob != null) ui.post(ob::onNewMessage);
            } else if ("roster".equals(type)) {
                // 名册（uid -> 当前昵称）+ 已注销名单：登录时下发一次，
                // 有人改名 / 有人被删号时服务端再广播一次。收到就重画。
                boolean rosChanged = false;
                JSONObject users = o.optJSONObject("users");
                if (users != null) {
                    java.util.Iterator<String> it = users.keys();
                    while (it.hasNext()) {
                        String u = it.next();
                        long id;
                        try { id = Long.parseLong(u.trim()); } catch (Exception e2) { continue; }
                        store.putRoster(id, users.optString(u));
                    }
                    new Thread(() -> store.saveRoster()).start();
                    rosChanged = true;
                }
                // 已注销的 uid：本地缓存里那些老消息也得跟着改，
                // 否则它们会一直挂着旧 uid，永远显示不出"已注销"
                JSONArray gone = o.optJSONArray("deleted");
                if (gone != null) {
                    boolean dirty = false;
                    for (int i = 0; i < gone.length(); i++) {
                        if (store.markDeleted(gone.optLong(i, 0))) dirty = true;
                    }
                    if (dirty) {
                        rosChanged = true;
                        new Thread(() -> store.save()).start();
                    }
                }
                // 归档名单：显示效果跟 deleted 一样，但本地消息一个字都不用改
                // （服务端归档时没动 msgs，uid 还是原样），整份替换即可。
                JSONArray arch = o.optJSONArray("archived");
                if (arch != null) {
                    java.util.List<Long> ids = new java.util.ArrayList<>();
                    for (int i = 0; i < arch.length(); i++) ids.add(arch.optLong(i, 0));
                    if (store.setArchived(ids)) {
                        rosChanged = true;
                        new Thread(() -> store.saveArchived()).start();
                    }
                }
                if (rosChanged) {
                    final Observer ob = observer;
                    if (ob != null) ui.post(ob::onNewMessage);
                }
            } else if ("msg".equals(type)) {
                String from = o.optString("from");
                String fuuid = o.optString("uuid");
                String text = o.optString("text");
                long ts = o.optLong("ts", System.currentTimeMillis() / 1000);
                if (ts > 1_000_000_000_000L) ts = ts / 1000;
                boolean mine = !fuuid.isEmpty() && fuuid.equals(prefs.uuid());
                MsgStore.M m = new MsgStore.M(ts, from, text, mine,
                        o.optString("q_from"), o.optString("q_text"), o.optLong("q_ts", 0));
                m.uid = o.optLong("uid", 0);
                m.qUid = o.optLong("q_uid", 0);
                m.sys = o.optInt("sys", 0) == 1;
                // 实时消息无条件收纳，但登记去重键 —— 否则重连补推 history 时会重复
                store.addAndRemember(m);
                new Thread(() -> store.save()).start();
                final Observer ob = observer;
                if (ob != null) ui.post(ob::onNewMessage);
                if (!mine) notifyMsg(from, text);
            } else if ("history".equals(type)) {
                JSONArray arr = o.optJSONArray("items");
                if (arr != null) {
                    // full:true 是「强制同步」的返回：服务端有的覆盖本地同键条目，本地独有的不动
                    boolean full = o.optBoolean("full", false);
                    boolean changed = false;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.getJSONObject(i);
                        String from = it.optString("from");
                        String fuuidH = it.optString("uuid");
                        String text = it.optString("text");
                        long ts = it.optLong("ts", 0);
                        if (ts > 1_000_000_000_000L) ts = ts / 1000;
                        boolean mine = !fuuidH.isEmpty() && fuuidH.equals(prefs.uuid());
                        MsgStore.M m = new MsgStore.M(ts, from, text, mine,
                                it.optString("q_from"), it.optString("q_text"), it.optLong("q_ts", 0));
                        m.uid = it.optLong("uid", 0);
                        m.qUid = it.optLong("q_uid", 0);
                        m.sys = it.optInt("sys", 0) == 1;
                        boolean did = full ? store.addOrUpdate(m) : store.addUnique(m);
                        if (did) changed = true;
                    }
                    if (changed) {
                        new Thread(() -> store.save()).start();
                        final Observer ob = observer;
                        if (ob != null) ui.post(ob::onNewMessage);
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    private OkHttpClient buildClient() {
        try {
            // 信任自签证书：与 Net 的 https 共用 Tls 里那一份配置（原先两份还不一致）
            javax.net.ssl.SSLContext ctx = Tls.context();
            if (ctx == null) return new OkHttpClient();
            return new OkHttpClient.Builder()
                    .sslSocketFactory(ctx.getSocketFactory(), Tls.manager())
                    .hostnameVerifier((h, s) -> true)
                    .pingInterval(20, TimeUnit.SECONDS)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .build();
        } catch (Exception e) {
            return new OkHttpClient();
        }
    }

    // ---------- 通知 ----------

    private void startForegroundNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_SERVICE, "连接状态", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = buildNotif("正在后台收消息");
        startForeground(1001, b.build());
    }

    /** 通知按钮：切换免打扰 */
    public static final String ACTION_DND = "com.yeho.chat.DND";
    /** 界面改完免打扰后，通知服务刷新显示 */
    public static final String ACTION_DND_REFRESH = "com.yeho.chat.DND_REFRESH";
    private boolean dndOn() {
        try { return new Prefs(this).dndOn(); } catch (Exception e) { return false; }
    }
    /** 根据免打扰状态给出状态文字 */
    public static String dndText(boolean dnd) {
        return dnd ? "免打扰中 · 仍保持连接" : "在线";
    }
    private Notification.Builder buildNotif(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CH_SERVICE)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("YehoChat")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_LOW);
        // 「开启/关闭消息免打扰」按钮
        try {
            Intent act = new Intent(this, ChatService.class).setAction(ACTION_DND);
            PendingIntent api = PendingIntent.getService(this, 2, act,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String label = dndOn() ? "关闭消息免打扰" : "开启消息免打扰";
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this,
                            android.R.drawable.ic_lock_idle_alarm),
                    label, api).build());
        } catch (Exception ignored) { }
        return b;
    }
    private void refreshNotif() {
        String txt = dndText(dndOn());
        updateNotifText(txt);
        notifyStatus(txt);
    }

    private void updateNotifText(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1001, buildNotif(text).build());
        } catch (Exception ignored) { }
    }

    /** 新消息通知（另一个渠道，会响/震动） */
    private void notifyMsg(String from, String text) {
        if (dndOn()) return;        // 免打扰：消息照收，不弹通知
        if (uiForeground) return;   // 正在看聊天界面：不弹通知（免堆积）
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            String chId = "yeho_msg";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        chId, "新消息", NotificationManager.IMPORTANCE_HIGH);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(this, ChatActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, chId)
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(from)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            if (nm != null) nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(ticker);
        if (ws != null) { ws.close(1000, "bye"); ws = null; }
        INSTANCE = null;
        // 被系统杀掉时尝试重启
        Intent i = new Intent(this, ChatService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }
}