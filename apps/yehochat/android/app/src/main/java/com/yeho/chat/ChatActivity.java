package com.yeho.chat;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.List;

/**
 * 聊天页（底部「聊天」Tab）：wss 连接由 ChatService 持有，这里只做渲染与发送。
 *
 * 本页不再承载账号操作 / 缓存设置 —— 那些搬到了「我的」「设置」两个 Tab。
 * 但 onResume/onPause 里的 ChatService.onUiResume()/onUiPause() 必须保留：
 * 它们负责"正在看聊天时不弹通知"。
 */
public class ChatActivity extends AppCompatActivity implements ChatService.Observer {

    private LinearLayout msgList;
    private ScrollView scroll;
    private EditText etInput;
    private TextView tvTitle, tvStatus;

    /** 引用条（长按气泡→引用后出现） */
    private View quoteBar;
    private TextView quoteName, quoteText;

    /** 待发送的引用；qTs == 0 表示当前没有引用 */
    private String pendingQFrom = "";
    private String pendingQText = "";
    private long pendingQTs = 0;
    /** 被引用者的 uid：服务端存下来，别人渲染引用块时才知道该显示谁现在的名字 */
    private long pendingQUid = 0;

    /** 撤回时限（秒）：与服务端一致 */
    private static final long RECALL_LIMIT_SEC = 120;

    private Prefs prefs;
    private MsgStore store;

    /**
     * 渲染索引：与 msgList 里"非系统提示"的气泡一一对应（同一时刻、同一下标）。
     * "点引用跳转"要按 (ts, from) 找气泡，靠它定位。每次 renderAll 整体重建。
     */
    private final java.util.List<MsgStore.M> renderedMsgs = new java.util.ArrayList<>();
    private final java.util.List<View> renderedBoxes = new java.util.ArrayList<>();

    /** buildBubble() 顺带把内层气泡记在这里 —— 它的返回值是最外层的 row，闪烁要的是内层 box */
    private View lastBubbleBox;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Handler cleaner = new Handler(Looper.getMainLooper());

    /** 上次渲染时 MsgStore 的清空次数：在别的 Tab 清过记录，回到本页要重画 */
    private int renderedGen = -1;

    /** 右下角「回到最底部 / N 条新消息」浮层 */
    private TextView btnBottom;
    /**
     * 上一次"人贴在底部"时的消息总数。
     * 未读数用差值算（当前总数 - 这个数），而不是来了就 ++ —— 撤回会把总数减一，
     * 差值算法天然不会算成负数，也不会把撤回误报成新消息。
     */
    private int lastBottomCount = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        prefs = new Prefs(this);
        if (!prefs.loggedIn()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_chat);

        msgList = findViewById(R.id.msgList);
        scroll = findViewById(R.id.scroll);
        etInput = findViewById(R.id.etInput);
        tvTitle = findViewById(R.id.tvTitle);
        tvStatus = findViewById(R.id.tvStatus);

        tvTitle.setText("YehoChat");
        findViewById(R.id.btnSend).setOnClickListener(v -> sendMsg());

        // 引用条
        quoteBar = findViewById(R.id.quoteBar);
        quoteName = findViewById(R.id.quoteName);
        quoteText = findViewById(R.id.quoteText);
        findViewById(R.id.btnQuoteCancel).setOnClickListener(v -> clearQuote());

        // 右下角「回到最底部」浮层
        btnBottom = findViewById(R.id.btnBottom);
        btnBottom.setOnClickListener(v -> jumpToBottom());
        scroll.setOnScrollChangeListener((v, sx, sy, osx, osy) -> updateJumpBtn());

        // 底部三 Tab
        TabBar.attach(this, TabBar.TAB_CHAT);

        store = MsgStore.get(this, prefs);
        new Thread(() -> {
            store.load();
            ui.post(this::renderAll);
        }).start();

        // 通知权限 + 启动前台服务（后台保活）
        BgGuide.askNotiPerm(this);
        BgGuide.startChatService(this);
        if (!prefs.bgGuided()) {
            prefs.setBgGuided(true);
            ui.postDelayed(this::showFirstRunTips, 1200);
        }

        // 连接由 ChatService 持有（后台也不断线），这里只做 UI 观察
        ChatService.setObserver(this);
        // 关键：主动同步一次真实状态（原先只在"状态变化"时才回调，
        // 导致已连上时进来还挂着 xml 默认的"连接中…"）
        setStatus(ChatService.statusText());
        ui.postDelayed(this::syncStatusFromService, 300);
        ui.postDelayed(this::syncStatusFromService, 1000);
        ui.postDelayed(this::syncStatusFromService, 2500);

        // 10 秒循环清理（避免卡顿：放到子线程）
        cleaner.post(new Runnable() {
            @Override public void run() {
                new Thread(() -> store.clean()).start();
                cleaner.postDelayed(this, 10000);
            }
        });

        // 版本检测：启动 3 秒后静默问一次更新接口，有新版本就在应用内弹窗。
        // 延迟是为了别跟首屏渲染、登录补历史抢网络；没有更新的话全程不打扰。
        ui.postDelayed(() -> UpdateChecker.checkAndPrompt(this, false), 3000);
    }

    // ---------- 发送 ----------
    private void sendMsg() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        try {
            JSONObject o = new JSONObject();
            o.put("type", "msg");
            o.put("text", text);
            // 引用：带上被引用那条的快照，服务端入库后透传给所有人
            if (pendingQTs > 0) {
                o.put("q_from", pendingQFrom);
                o.put("q_text", pendingQText);
                o.put("q_ts", pendingQTs);
                o.put("q_uid", pendingQUid);
            }
            if (!ChatService.send(o.toString())) {
                Ui.toast(this, "还没连上，稍等");
                return;
            }
            etInput.setText("");
            clearQuote();   // 发完清空引用条
        } catch (Exception e) {
            Ui.toast(this, "发送失败");
        }
    }

    // ---------- 长按菜单：引用 / 删除 / 撤回 ----------

    /** 长按气泡：弹出菜单。引用、删除所有消息都有；撤回只有自己的、且 2 分钟内的才有 */
    private void showMsgMenu(MsgStore.M m) {
        final java.util.List<String> items = new java.util.ArrayList<>();
        items.add("引用");
        items.add("删除");
        boolean canRecall = m.mine
                && (System.currentTimeMillis() / 1000L - m.ts) <= RECALL_LIMIT_SEC;
        if (canRecall) items.add("撤回");

        new AlertDialog.Builder(this)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    String pick = items.get(w);
                    if ("引用".equals(pick)) startQuote(m);
                    else if ("删除".equals(pick)) deleteLocal(m);
                    else if ("撤回".equals(pick)) recall(m);
                })
                .show();
    }

    /** 点击「引用」：填好引用条。发出去之后服务端会把 q_* 透传给所有人 */
    private void startQuote(MsgStore.M m) {
        pendingQFrom = store.nameOf(m);                // 当前昵称（快照，只当兜底）
        pendingQUid = m.uid;                           // 真正管用的是这个
        pendingQText = m.text == null ? "" : m.text;   // 存全文，渲染时再截断
        pendingQTs = m.ts;
        quoteName.setText(m.mine ? ("我 · " + pendingQFrom) : pendingQFrom);
        quoteText.setText(truncate(pendingQText, 60));
        quoteBar.setVisibility(View.VISIBLE);
    }

    private void clearQuote() {
        pendingQFrom = "";
        pendingQUid = 0;
        pendingQText = "";
        pendingQTs = 0;
        if (quoteBar != null) quoteBar.setVisibility(View.GONE);
    }

    /** 本地删除：走 MsgStore.delete —— 从缓存移除 + 记墓碑 + 落盘，之后同步也不会把它拉回来 */
    private void deleteLocal(MsgStore.M m) {
        new Thread(() -> {
            store.delete(m);
            ui.post(this::renderAll);
        }).start();
    }

    /**
     * 撤回：把指令发给服务端，剩下的等服务端的 recall 广播回来统一处理。
     *
     * 这里**故意不做**本机的乐观删除：撤回提示要插在原消息的位置上
     * （见 MsgStore.replaceWithTip），要是本机先把那条删了，等广播回来时就找不到
     * 位置，只能把提示追加到末尾 —— 撤回条会跑到下一条消息底下去。
     * 服务端就在旁边，这点往返基本感觉不到；而且服务端拒绝撤回（超时/不是本人）时，
     * 消息也不会被误删。
     */
    private void recall(MsgStore.M m) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "recall");
            o.put("ts", m.ts);
            o.put("text", m.text);
            if (!ChatService.send(o.toString())) {
                Ui.toast(this, "还没连上，稍等");
            }
        } catch (Exception e) {
            Ui.toast(this, "撤回失败");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String one = s.replace("\n", " ");
        return one.length() <= max ? one : (one.substring(0, max) + "…");
    }

    // ---------- 渲染 ----------

    /**
     * 重画整屏。两条规则：
     *  · 时间分隔：只在首条、或与上一条消息间隔 > 5 分钟时插一条（原来每条都挂时间戳）
     *  · 连续合并：同一人连发多条时只在首条显示名字，后续气泡收紧
     */
    private void renderAll() {
        // 必须先判定再清空：重建视图会改变内容高度，之后再算就不准了。
        // 原先这里无条件 fullScroll 到底 —— 用户翻上去看历史时会被每来一条新消息
        // 硬拽回底部，等于那个「回到最底部」按钮永远没机会出现。
        boolean wasNearBottom = nearBottom();
        List<MsgStore.M> list = store.all();
        msgList.removeAllViews();
        renderedMsgs.clear();
        renderedBoxes.clear();

        long prevTs = 0;
        String prevFrom = null;
        boolean prevMine = false;
        boolean first = true;

        for (MsgStore.M m : list) {
            // 系统提示（"某某 撤回了一条消息"）：居中灰字，打断连续合并 ——
            // 它后面那条消息要重新显示发送人，否则会跟撤回前的人名糊在一起。
            if (m.sys) {
                if (first || (m.ts - prevTs) > MsgStore.TIME_GAP_SEC) {
                    msgList.addView(buildTimeSep(m.ts));
                }
                msgList.addView(buildSystemTip(m));
                prevTs = m.ts;
                prevFrom = null;
                prevMine = false;
                first = false;
                continue;
            }

            boolean showTime = first || (m.ts - prevTs) > MsgStore.TIME_GAP_SEC;
            boolean sameAsPrev = !first && !showTime
                    && m.mine == prevMine
                    && eq(m.from, prevFrom);

            if (showTime) msgList.addView(buildTimeSep(m.ts));
            msgList.addView(buildBubble(m, !sameAsPrev, sameAsPrev));
            renderedMsgs.add(m);
            renderedBoxes.add(lastBubbleBox);

            prevTs = m.ts;
            prevFrom = m.from;
            prevMine = m.mine;
            first = false;
        }
        renderedGen = store.generation();
        if (wasNearBottom) {
            lastBottomCount = list.size();   // 人在底部：未读归零
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
        scroll.post(this::updateJumpBtn);
    }

    // ---------- 回到最底部 ----------

    /** 距列表底部还有多少像素（<= 0 表示已经到底） */
    private int bottomGap() {
        return msgList.getHeight() - scroll.getHeight() - scroll.getScrollY();
    }

    /** 是否贴在底部：决定"来新消息要不要自动跟下去" */
    private boolean nearBottom() {
        return bottomGap() <= Ui.dp(this, 80);
    }

    /**
     * 是否往上翻得够多了。阈值取"一屏"和 500dp 里的大者：
     * 小屏上也至少得翻过半屏才提示，免得手指稍微一动按钮就冒出来。
     */
    private boolean farFromBottom() {
        return bottomGap() > Math.max(scroll.getHeight(), Ui.dp(this, 500));
    }

    /** 刷新浮层：不在底部就显示，并带上"翻上去这段时间里新到了几条" */
    private void updateJumpBtn() {
        if (btnBottom == null || store == null) return;
        // 首帧还没测量出高度，此时 bottomGap 是个假值，直接不动
        if (scroll.getHeight() <= 0 || msgList.getHeight() <= 0) return;
        if (!farFromBottom()) {
            lastBottomCount = store.all().size();   // 人就在底部：未读清零，按钮收起
            if (btnBottom.getVisibility() != View.GONE) btnBottom.setVisibility(View.GONE);
            return;
        }
        int unread = store.all().size() - lastBottomCount;
        btnBottom.setText(unread > 0 ? (unread + " 条新消息 ↓") : "回到最底部 ↓");
        if (btnBottom.getVisibility() != View.VISIBLE) btnBottom.setVisibility(View.VISIBLE);
    }

    /** 点浮层：滚到底 + 未读清零 */
    private void jumpToBottom() {
        lastBottomCount = store.all().size();
        btnBottom.setVisibility(View.GONE);
        scroll.smoothScrollTo(0, msgList.getHeight());
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** 居中的弱化时间分隔条 */
    private View buildTimeSep(long ts) {
        TextView tv = new TextView(this);
        tv.setText(MsgStore.fmtTime(ts));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.bubble_time));
        tv.setTextColor(Ui.color(this, R.color.text_muted));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 6));
        return tv;
    }

    /**
     * 一条消息。
     * @param showName  是否显示发送人（同一人连发的后续条不显示）
     * @param tight     是否是"连续消息的后续条"（顶部间距收紧）
     */
    private View buildBubble(MsgStore.M m, boolean showName, boolean tight) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(0, Ui.dp(this, tight ? 2 : 8), 0, 0);

        // 发送人（同一人连发时只在首条出现）
        if (showName) {
            TextView tvF = new TextView(this);
            // 名字一律走名册解析，不用消息里那份快照 —— 改了名这里才会跟着变
            String who = store.nameOf(m);
            tvF.setText(m.mine ? ("我 · " + who) : who);
            tvF.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimension(R.dimen.bubble_name));
            tvF.setTextColor(Ui.color(this, R.color.text_primary));
            tvF.setGravity(m.mine ? Gravity.END : Gravity.START);
            tvF.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 3));
            row.addView(tvF);
        }

        // 内容气泡：浅蓝(自己) / 白+描边(对方)，两种底色上都是深色字。
        // 引用区（如果有）与正文同在一个气泡里 —— 底色/圆角画在 box 上，正文不再单独带背景。
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(m.mine ? R.drawable.bubble_mine : R.drawable.bubble_other);
        final int padH = getResources().getDimensionPixelSize(R.dimen.bubble_pad_h);
        final int padV = getResources().getDimensionPixelSize(R.dimen.bubble_pad_v);
        box.setPadding(padH, padV, padH, padV);

        View quoteView = null;
        if (m.hasQuote()) {
            quoteView = buildQuoteBlock(m);
            box.addView(quoteView);
        }

        TextView tv = new TextView(this);
        tv.setText(m.text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.bubble_text));
        tv.setTextColor(Ui.color(this, R.color.text_body));
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        box.addView(tv);

        // ---- 气泡宽度：自己量，不靠嵌套的 WRAP_CONTENT ----
        //
        // 踩过的坑：这里原先完全交给 WRAP_CONTENT 去撑宽度。只要消息带引用，气泡宽度就会被
        // 「引用块的自然宽度」带偏 —— 引用一短（比如就几个字），气泡就窄，正文 TextView 跟着
        // 被压在一行里裁掉，整条正文只剩开头几个字，看起来像消息内容丢了。
        // 引用越短裁得越狠，所以症状是「引用短的时候正文显示不全」。
        //
        // 现在显式量：正文按最大可用宽度量出真实需要的宽度（会自动折行），引用块同样量一次，
        // 取较大者把气泡宽度钉死。这样正文永远是决定气泡宽度的那个。
        final int maxContent = Math.max(
                Ui.dp(this, 120),
                (int) (getResources().getDisplayMetrics().widthPixels * 0.78f) - 2 * padH);
        final int specW = android.view.View.MeasureSpec.AT_MOST;
        final int specH = android.view.View.MeasureSpec.UNSPECIFIED;
        tv.setMaxWidth(maxContent);
        tv.measure(android.view.View.MeasureSpec.makeMeasureSpec(maxContent, specW),
                android.view.View.MeasureSpec.makeMeasureSpec(0, specH));
        int wantW = tv.getMeasuredWidth();
        if (quoteView != null) {
            quoteView.measure(android.view.View.MeasureSpec.makeMeasureSpec(maxContent, specW),
                    android.view.View.MeasureSpec.makeMeasureSpec(0, specH));
            wantW = Math.max(wantW, quoteView.getMeasuredWidth());
        }

        // 长按气泡弹菜单（引用 / 删除 / 撤回）
        box.setOnLongClickListener(v -> {
            showMsgMenu(m);
            return true;
        });

        LinearLayout holder = new LinearLayout(this);
        holder.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holder.setGravity(m.mine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                wantW + 2 * padH, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 10);
        lp.leftMargin = Ui.dp(this, 10);
        holder.addView(box, lp);
        row.addView(holder);

        lastBubbleBox = box;   // 供 renderAll 建索引（返回的是 row，闪烁要的是 box）
        return row;
    }

    /** 系统提示：居中、灰、小字，不参与长按菜单 */
    private View buildSystemTip(MsgStore.M m) {
        TextView tv = new TextView(this);
        // 文案现算：服务端那条 sys 行的 text 是空的，只存了"是谁撤的"（uuid），
        // 名字在渲染这一刻才拼进去 —— 这样改名后旧的撤回提示也会跟着换名字。
        String who = store.nameOf(m);
        String tip = m.text == null ? "" : m.text;
        if (tip.isEmpty()) {
            tip = (who.isEmpty() ? "有人" : who) + " 撤回了一条消息";
        }
        tv.setText(tip);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.bubble_time));
        tv.setTextColor(Ui.color(this, R.color.text_muted));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 4));
        return tv;
    }

    /**
     * 气泡上方那一小块引用区：左侧竖线 + 浅灰底 + 两行（昵称、内容截断）。
     */
    private View buildQuoteBlock(MsgStore.M m) {
        LinearLayout q = new LinearLayout(this);
        q.setOrientation(LinearLayout.HORIZONTAL);
        q.setBackgroundResource(R.drawable.quote_bg);

        View bar = new View(this);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dp(this, 3), ViewGroup.LayoutParams.MATCH_PARENT));
        bar.setBackgroundColor(Ui.color(this, R.color.text_primary));
        q.addView(bar);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 8), Ui.dp(this, 5));

        TextView tvN = new TextView(this);
        String qwho = store.quoteNameOf(m);
        tvN.setText(qwho.isEmpty() ? "未知" : qwho);
        tvN.setTextSize(12f);
        tvN.setTextColor(Ui.color(this, R.color.text_primary));
        tvN.setSingleLine(true);
        tvN.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tvN);

        // 引用正文：最多两行，用满气泡能给到的宽度。
        //
        // 这里原先压着三层硬限制：maxWidth(200dp) + singleLine + 先按 60 字预截断。
        // 结果是不管气泡多宽、内容多短，都只能显示一行约 15 个中文字 —— 引用稍长就被
        // 截得只剩几个字。删掉 maxWidth 上限、放宽到两行之后，引用块才真的能用上气泡宽度。
        // （WRAP_CONTENT 链上的多行 TextView 会在父级可用宽度处自动折行，不用手动算宽度）
        // 引用区存的是**发送那一刻的快照**。被引用的原消息一旦撤回，这份快照就失效了 ——
        // 继续显示原内容会让用户以为那条消息还在。查一下撤回标记，改成明确的「原消息已撤回」。
        boolean qGone = store.isQuoteRecalled(m.qTs, m.qUid);
        TextView tvT = new TextView(this);
        tvT.setText(qGone ? "原消息已撤回" : truncate(m.qText, 120));
        tvT.setTextSize(13f);
        tvT.setTextColor(Ui.color(this, qGone ? R.color.text_muted : R.color.text_sub));
        if (qGone) tvT.setTypeface(null, android.graphics.Typeface.ITALIC);
        tvT.setMaxLines(2);
        tvT.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(tvT);

        q.addView(col);

        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.bottomMargin = Ui.dp(this, 6);
        q.setLayoutParams(qlp);

        // 点引用块 -> 跳到被引用的那条原消息（居中 + 闪一下）
        q.setClickable(true);
        q.setOnClickListener(v -> jumpToQuote(m));
        return q;
    }

    // ---------- 引用跳转 ----------

    /**
     * 点引用块：滚到被引用的原消息并高亮闪一下。
     *
     * 匹配优先用 (ts, from)：q_ts 是原样的秒级时间戳，但 q_from 可能被服务端截到
     * 20 个字符，截断后对不上同一秒的多条消息，就退化成只按 ts 找（取最早那条）。
     */
    private void jumpToQuote(MsgStore.M m) {
        if (m.qTs <= 0) return;
        // 原消息被撤回了就说清楚，别让用户对着"已不在本地记录里"发懵
        if (store.isQuoteRecalled(m.qTs, m.qUid)) {
            Ui.toast(this, "原消息已撤回");
            return;
        }
        int hit = -1, tsOnly = -1;
        for (int i = 0; i < renderedMsgs.size(); i++) {
            MsgStore.M r = renderedMsgs.get(i);
            if (r.ts != m.qTs) continue;
            if (tsOnly < 0) tsOnly = i;
            if (eq(r.from, m.qFrom)) { hit = i; break; }
        }
        if (hit < 0) hit = tsOnly;
        if (hit < 0) {
            Ui.toast(this, "原消息已不在本地记录里");
            return;
        }
        centerOn(renderedBoxes.get(hit));
    }

    /**
     * 把某个气泡滚到可视区正中。
     * 气泡比可视区还高时（长消息），offset 自动变成 0 —— 也就是停在"消息头"，
     * 而不是把它中间的一段塞到屏幕中央。
     */
    private void centerOn(final View box) {
        scroll.post(() -> {
            int offset = Math.max(0, (scroll.getHeight() - box.getHeight()) / 2);
            scroll.smoothScrollTo(0, Math.max(0, topInList(box) - offset));
            scroll.postDelayed(() -> flash(box), 320);
        });
    }

    /** 目标 View 相对 msgList 顶部的 y（气泡外面还套了 holder / row 两层，逐层累加） */
    private int topInList(View v) {
        int y = 0;
        View cur = v;
        while (cur != null && cur != msgList) {
            y += cur.getTop();
            android.view.ViewParent p = cur.getParent();
            cur = (p instanceof View) ? (View) p : null;
        }
        return y;
    }

    /** 高亮闪三下：高亮层挂在气泡的 foreground 上（foreground 画在子视图之上），放完摘掉 */
    private void flash(final View box) {
        try {
            android.graphics.drawable.Drawable fg =
                    getResources().getDrawable(R.drawable.highlight_flash, getTheme());
            box.setForeground(fg);
            android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofInt(
                    fg, "alpha", 0, 255, 0, 255, 0, 255, 0);
            a.setDuration(900);
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    box.setForeground(null);
                }
            });
            a.start();
        } catch (Exception ignored) { }
    }

    private void setStatus(String s) {
        String show = s;
        try {
            if (new Prefs(this).dndOn() && !s.contains("免打扰")) {
                show = s + " · 🌙免打扰";
            }
        } catch (Exception ignored) { }
        tvStatus.setText(show);
        int c;
        if (show.contains("免打扰")) c = R.color.text_primary;
        else if (show.contains("在线")) c = R.color.ok;
        else if (show.contains("封禁")) c = R.color.danger;
        else c = R.color.text_muted;
        tvStatus.setTextColor(Ui.color(this, c));
    }

    /**
     * 进入前台：标记「正在看聊天」并清掉已堆积的消息通知。
     *
     * 此前 ChatService.onUiResume()/onUiPause() 是 public static 但无人调用，
     * 导致 ChatService.uiForeground 恒为 false —— notifyMsg() 里那句
     * 「正在看聊天界面：不弹通知」形同虚设，用户开着聊天页照样被弹通知。
     * 在这里接上即可（方法本身早已写好，只是从没被调用过）。
     *
     * 另外：本页被 Tab 切换"重新提到前台"时不会重建，所以在这里补一次状态同步，
     * 以及"在设置页清空过记录"的重画。
     */
    @Override
    protected void onResume() {
        super.onResume();
        ChatService.onUiResume();
        if (!prefs.loggedIn()) {   // 在别的 Tab 退出登录后回到本页
            finish();
            return;
        }
        try {
            setStatus(ChatService.statusText());
        } catch (Exception ignored) { }
        if (store != null && renderedGen != store.generation()) {
            new Thread(() -> {
                store.load();
                ui.post(this::renderAll);
            }).start();
        }
    }

    /** 离开前台：恢复正常弹通知。 */
    @Override
    protected void onPause() {
        super.onPause();
        ChatService.onUiPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ChatService.setObserver(null);
        cleaner.removeCallbacksAndMessages(null);
        if (store != null) new Thread(() -> store.save()).start();
    }

    // ---------- ChatService 观察者 ----------
    /** 从服务拉一次真实状态并刷新（补上"进入时无人通知"的缺口） */
    private void syncStatusFromService() {
        try {
            String s = ChatService.statusText();
            String cur = tvStatus.getText() == null ? "" : tvStatus.getText().toString();
            if (!s.equals(cur)) setStatus(s);
        } catch (Exception ignored) { }
    }
    @Override public void onStatus(String s) {
        ui.post(() -> setStatus(s));
    }

    @Override public void onNewMessage() {
        ui.post(this::renderAll);
    }

    /** 服务端提示（目前主要是撤回失败的原因） */
    @Override public void onToast(String msg) {
        ui.post(() -> Ui.toast(this, msg));
    }

    @Override public void onLoginLost() {
        ui.post(() -> {
            Ui.toast(this, "登录已失效，请重新登录");
            finish();
        });
    }

    @Override
    public void onBanned() {
        ui.post(() -> {
            new AlertDialog.Builder(this)
                    .setTitle("账号已被封禁")
                    .setMessage("该账号已被管理员封禁，无法继续使用。\n如有疑问请联系管理员。")
                    .setCancelable(false)
                    .setPositiveButton("退出登录", (d, w) -> {
                        try { new Prefs(this).logout(); } catch (Exception ignored) { }
                        finish();
                    })
                    .show();
        });
    }

    /** 首次安装登录后的提示：让用户去底部「我的 / 设置」里开权限 */
    private void showFirstRunTips() {
        new AlertDialog.Builder(this)
                .setTitle("👋 欢迎使用 YehoChat")
                .setMessage("为保证消息能及时收到，\n"
                        + "建议先开启后台相关权限。\n\n"
                        + "请点击底部「设置」，\n"
                        + "进入「后台保活引导」，\n"
                        + "按提示逐项开启即可。\n\n"
                        + "（当前先打开引导吗？）")
                .setPositiveButton("现在去开", (d, w) -> BgGuide.show(this, 0))
                .setNegativeButton("稍后再说", null)
                .show();
    }
}
