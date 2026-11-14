package com.yeho.chat;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 「设置」Tab：免打扰开关、后台保活引导、清理本地缓存、关于。
 *
 * 免打扰与缓存清理两块的选项、调用、落盘键名全部沿用原来 ChatActivity 里的实现。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String[] CLEAN_MODES = {
            "按条数（最多 1000）", "按大小（1MB~3GB）", "按天数（最多 60 天）", "无上限", "清空聊天记录"
    };

    private Prefs prefs;
    private MsgStore store;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout rowDnd, rowCleanMode, rowNight;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (!prefs.loggedIn()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_settings);

        store = MsgStore.get(this, prefs);

        TabBar.attach(this, TabBar.TAB_SETTINGS);
        buildRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.loggedIn()) {
            finish();
            return;
        }
        refreshValues();
    }

    private void buildRows() {
        LinearLayout cardNotify = findViewById(R.id.cardNotify);
        LinearLayout cardKeep = findViewById(R.id.cardKeepAlive);
        LinearLayout cardCache = findViewById(R.id.cardCache);
        LinearLayout cardAppearance = findViewById(R.id.cardAppearance);
        LinearLayout cardAbout = findViewById(R.id.cardAbout);

        rowDnd = Rows.add(this, cardNotify, "消息免打扰", null, this::showDndMenu);

        Rows.add(this, cardKeep, "后台保活引导", "自启 / 通知 / 省电",
                () -> BgGuide.show(this, 0));

        rowCleanMode = Rows.add(this, cardCache, "清理方式", null, this::showCleanSettings);
        Rows.add(this, cardCache, "强制同步所有数据", "覆盖本地记录", this::confirmForceSync);
        Rows.add(this, cardCache, "清空聊天记录", null, this::confirmClear, R.color.danger);

        rowNight = Rows.add(this, cardAppearance, "夜间模式", null, this::showNightMenu);

        Rows.add(this, cardAbout, "版本", "v" + appVersion(), this::checkUpdate);
        Rows.add(this, cardAbout, "服务器", BuildConfig.CHAT_HOST, null);
        Rows.add(this, cardAbout, "包名", getPackageName(), null);

        ((android.widget.TextView) findViewById(R.id.tvCacheHint)).setText(
                "本地缓存每 10 秒按上面的规则自动清理一次。\n"
                        + "服务器只保留最近 30 天 / 最多 50MB，重新连接时会拉回近期消息。");
    }

    /** 「版本」这一行点一下 = 手动检查更新（自动检查在聊天页启动时静默跑一次） */
    private void checkUpdate() {
        Ui.toast(this, "正在检查更新…");
        UpdateChecker.checkAndPrompt(this, true);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }

    private void refreshValues() {
        Rows.setValue(rowDnd, dndSummary());
        int cur = prefs.cleanMode();
        Rows.setValue(rowCleanMode, cur >= 0 && cur < CLEAN_MODES.length ? CLEAN_MODES[cur] : "");
        Rows.setValue(rowNight, nightSummary());
    }

    // ---------- 夜间模式 ----------

    /** 三档的显示文案，跟 showNightMenu 里的选项顺序一一对应 */
    private static final String[] NIGHT_LABELS = {"跟随系统", "关闭", "开启"};
    /** 对应的 AppCompatDelegate 常量值 */
    private static final int[] NIGHT_VALUES = {
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO,
            androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES,
    };

    private String nightSummary() {
        int m = prefs.nightMode();
        for (int i = 0; i < NIGHT_VALUES.length; i++) {
            if (NIGHT_VALUES[i] == m) return NIGHT_LABELS[i];
        }
        return NIGHT_LABELS[0];
    }

    /**
     * 夜间模式菜单。
     *
     * 改完调 Ui.applyNightMode 就会让所有存活的 Activity 重建一次 ——
     * 当前这个设置页自己也会重建，所以不用手动 refreshValues，
     * 重建后 onCreate 会再跑一遍。这里的 toast 会跟着一起断，所以放最后。
     */
    private void showNightMenu() {
        new AlertDialog.Builder(this)
                .setTitle("夜间模式 · 当前：" + nightSummary())
                .setItems(NIGHT_LABELS, (d, w) -> {
                    prefs.setNightMode(NIGHT_VALUES[w]);
                    Ui.applyNightMode(this);
                    Ui.toast(this, "夜间模式：" + NIGHT_LABELS[w]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String dndSummary() {
        if (!prefs.dndOn()) return "已关闭";
        long left = prefs.dndLeft();
        return (left == -1) ? "一直开启" : ("剩余 " + fmtLeft(left));
    }

    // ---------- 免打扰 ----------
    /** 免打扰菜单（选项与文案沿用原 ChatActivity.showDndMenu） */
    private void showDndMenu() {
        final boolean on = prefs.dndOn();
        String title;
        if (!on) {
            title = "消息免打扰 · 当前已关闭";
        } else {
            long left = prefs.dndLeft();
            title = (left == -1) ? "消息免打扰 · 一直开启"
                    : ("消息免打扰 · 剩余 " + fmtLeft(left));
        }
        String[] items = on
                ? new String[]{"关闭消息免打扰", "改为 1 小时后退出", "改为 2 小时后退出", "改为一直开启"}
                : new String[]{"开启（一直）", "开启 1 小时后退出", "开启 2 小时后退出"};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (d, w) -> {
                    long now = System.currentTimeMillis() / 1000L;
                    if (!on) {
                        if (w == 0) prefs.setDndUntil(-1L);
                        else if (w == 1) prefs.setDndUntil(now + 3600);
                        else prefs.setDndUntil(now + 7200);
                    } else {
                        if (w == 0) prefs.setDndUntil(0L);
                        else if (w == 1) prefs.setDndUntil(now + 3600);
                        else if (w == 2) prefs.setDndUntil(now + 7200);
                        else prefs.setDndUntil(-1L);
                    }
                    // 让常驻通知上的按钮文案与状态跟着变
                    try {
                        Intent i = new Intent(this, ChatService.class);
                        i.setAction(ChatService.ACTION_DND_REFRESH);
                        startService(i);
                    } catch (Exception ignored) { }
                    refreshValues();
                    Ui.toast(this, prefs.dndOn() ? "已开启消息免打扰" : "已关闭消息免打扰");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 剩余秒数 -> 人话 */
    private String fmtLeft(long sec) {
        if (sec <= 0) return "即将结束";
        if (sec < 60) return sec + " 秒";
        if (sec < 3600) return (sec / 60) + " 分钟";
        return (sec / 3600) + " 小时 " + ((sec % 3600) / 60) + " 分钟";
    }

    // ---------- 清理设置 ----------
    private void showCleanSettings() {
        new AlertDialog.Builder(this)
                .setTitle("消息缓存清理（当前：" + CLEAN_MODES[prefs.cleanMode()] + "）")
                .setItems(CLEAN_MODES, (d, which) -> {
                    if (which == 4) { confirmClear(); return; }
                    prefs.setCleanMode(which);
                    if (which == 0) {
                        inputNumber("最多保留条数（50~1000）", prefs.maxCount(), 50, 1000,
                                v -> prefs.setMaxCount((int) v));
                    } else if (which == 1) {
                        inputNumber("最大大小 MB（1~3072，可小数）", prefs.maxMB(), 1, 3072,
                                v -> prefs.setMaxMB(v));
                    } else if (which == 2) {
                        inputNumber("最多保留天数（1~60）", prefs.maxDays(), 1, 60,
                                v -> prefs.setMaxDays((int) v));
                    } else {
                        prefs.setCleanMode(3);
                    }
                    refreshValues();
                    Ui.toast(this, "设置已保存");
                })
                .show();
    }

    /**
     * 强制同步所有数据（full:true）。
     * 服务端把缓存的全部条目推回来；合并规则是「服务端有的覆盖本地同键条目，本地独有的保持不变」，
     * 不是清空重来 —— 所以不会丢本地消息。
     *
     * 注意：这条路径**不看墓碑**。服务器缓存里还有的，哪怕你本地单独删过，也会被原样写回来，
     * 连那条"已经删掉"的记号一并撤销。这是「强制同步 = 服务器说了算」的应有之义，
     * 想让删除生效就别点这个。
     */
    private void confirmForceSync() {
        new AlertDialog.Builder(this)
                .setTitle("强制同步所有数据（覆盖本地记录）")
                .setMessage("将从服务器重新拉取全部缓存（服务端上限：最近 30 天 / 最多 50MB）。\n\n"
                        + "· 服务器上有的同一条消息，会用服务器的内容覆盖本地\n"
                        + "· 本地独有的消息保持不变，不会被清空\n"
                        + "· 服务器缓存里还有的消息一律覆盖回来 —— 包括你本地单独删过的那几条\n"
                        + "  （会连同删除记号一起撤销，这是「强制同步 = 服务器说了算」）\n\n"
                        + "本地记录显示不全、或怀疑漏消息时可以点这个。")
                .setPositiveButton("开始同步", (d, w) -> {
                    boolean ok = ChatService.send("{\"type\":\"sync\",\"full\":true}");
                    Ui.toast(this, ok ? "已请求强制同步，稍后自动刷新"
                                      : "还没连上服务器，请稍后再试");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 立即清空聊天记录（本地缓存） */
    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空聊天记录")
                .setMessage("将删除本机全部消息缓存，无法恢复。\n"
                        + "（服务器只保留最近 30 天/最多 50MB，重连后仍会拉回近期消息）")
                .setPositiveButton("清空", (d, w) -> {
                    new Thread(() -> {
                        store.clearAll();
                        // 聊天页靠 MsgStore.generation() 感知到"被清过"，回到聊天 Tab 会自己重画
                        ui.post(() -> Ui.toast(SettingsActivity.this, "已清空聊天记录"));
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface OnNum { void got(float v); }

    private void inputNumber(String title, float cur, float min, float max, OnNum cb) {
        String initial = cur == (int) cur ? String.valueOf((int) cur) : String.valueOf(cur);
        // 不加 inputType 限制：和原实现一致（大小那一项允许填小数）
        Dlg.input(this, title, "", initial, false, text -> {
            try {
                float v = Float.parseFloat(text);
                if (v < min) v = min;
                if (v > max) v = max;
                cb.got(v);
                refreshValues();
            } catch (Exception ignored) { }
        });
    }
}
