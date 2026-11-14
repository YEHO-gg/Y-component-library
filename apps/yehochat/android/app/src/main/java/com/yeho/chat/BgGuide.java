package com.yeho.chat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

/**
 * 后台保活引导 / 通知权限 / 前台服务拉起。
 *
 * 原先是 ChatActivity 里的一坨私有方法。现在「我的」和「设置」两个 Tab
 * 都要用到（聊天页首次运行的欢迎弹窗也要用），所以抽到这里，
 * 弹窗文案与跳转顺序 **一字未改**。
 */
public final class BgGuide {

    private BgGuide() { }

    private static final Handler UI = new Handler(Looper.getMainLooper());

    // ---------- 通知权限 / 前台服务 ----------

    /** 请求通知权限（Android 13+ 必须手动要） */
    public static void askNotiPerm(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (a.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED) {
                    a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2001);
                }
            }
        } catch (Exception ignored) { }
    }

    /** 启动前台常驻服务 */
    public static void startChatService(Context ctx) {
        try {
            Intent i = new Intent(ctx, ChatService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) { }
    }

    // ---------- 三步引导 ----------

    /** 第 step 项引导（0自启动 1通知 2省电 3完成） */
    public static void show(final Activity a, final int step) {
        if (step == 0) {
            new AlertDialog.Builder(a)
                    .setTitle("① 自启动管理（1/3）")
                    .setMessage("系统默认禁止 App 开机自启，\n"
                            + "重启后可能收不到消息。\n\n"
                            + "点「去设置」会尝试跳到自启动页面，\n"
                            + "（若跳不到，请在系统设置里找\n"
                            + "「自启动 / 开机自启动 / 自动启动管理」）\n\n"
                            + "找到 YehoChat → 打开开关。")
                    .setPositiveButton("去设置", (d, w) -> {
                        openAutoStart(a);
                        UI.postDelayed(() -> show(a, 1), 1500);
                    })
                    .setNeutralButton("跳过", (d, w) -> show(a, 1))
                    .setNegativeButton("退出引导", null)
                    .show();
        } else if (step == 1) {
            new AlertDialog.Builder(a)
                    .setTitle("② 通知权限（2/3）")
                    .setMessage("没有通知权限就看不到新消息提醒。\n\n"
                            + "点「去设置」会跳到系统设置，\n"
                            + "找到「通知 / 通知管理」，\n"
                            + "把 YehoChat 的「允许通知」打开。")
                    .setPositiveButton("去设置", (d, w) -> {
                        openNotiSettings(a);
                        UI.postDelayed(() -> show(a, 2), 1500);
                    })
                    .setNeutralButton("跳过", (d, w) -> show(a, 2))
                    .setNegativeButton("退出引导", null)
                    .show();
        } else if (step == 2) {
            new AlertDialog.Builder(a)
                    .setTitle("③ 省电 / 后台运行（3/3）")
                    .setMessage("系统可能在息屏后限制后台，\n"
                            + "导致你收不到新消息。\n\n"
                            + "⚠️ 点「去设置」后一般会跳到「系统设置」，\n"
                            + "（多数系统禁止 App 直达省电页）\n\n"
                            + "进去后请按顺序找：\n"
                            + "1. 「应用」→「应用管理」\n"
                            + "2. 找到「YehoChat」\n"
                            + "3. 点「电池 / 耗电管理 / 后台管理」\n"
                            + "4. 选「允许后台运行 / 不受限制」\n\n"
                            + "（不同系统名称略有差别，找意思相近的即可）")
                    .setPositiveButton("去设置", (d, w) -> {
                        requestIgnoreBattery(a);
                        UI.postDelayed(() -> show(a, 3), 1500);
                    })
                    .setNeutralButton("跳过", (d, w) -> show(a, 3))
                    .setNegativeButton("退出引导", null)
                    .show();
        } else {
            new AlertDialog.Builder(a)
                    .setTitle("✅ 设置完成")
                    .setMessage("三项都引导完啦。\n\n"
                            + "如果之后发现后台被杀、收不到消息，\n"
                            + "在「设置」里点「后台保活引导」可以再来一遍。")
                    .setPositiveButton("好", null)
                    .show();
        }
    }

    // ---------- 各个跳转 ----------

    /** 跳应用详情页（自启动/省电需用户手动点） */
    public static void openAppSettings(Activity a) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
        } catch (Exception e) {
            Ui.toast(a, "请到系统设置里手动开启");
        }
    }

    /** 第三项省电：厂商禁止直达省电页，直接送系统设置，由用户按引导手动开 */
    public static void requestIgnoreBattery(Activity a) {
        // 先试一下官方电池优化弹框（部分机型可用，会弹系统授权框）
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return;
        } catch (Exception ignored) { }

        // 不行就直接送系统设置首页（不试厂商组件，避免弹「选择要使用的应用」）
        Ui.toast(a, "请在设置里找「应用管理 → YehoChat → 电池/后台」");
        openSettingsHome(a);
    }

    /** 第二项通知：优先直跳通知页（不探测，直接 try），失败送系统设置 */
    public static void openNotiSettings(Activity a) {
        // ① 通知页（直接 try，不用 resolveActivity —— vivo 上探测不准）
        try {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName());
            a.startActivity(i);
            return;
        } catch (Exception ignored) { }

        // ② 旧版接口
        try {
            Intent i = new Intent("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("app_package", a.getPackageName());
            i.putExtra("app_uid", a.getApplicationInfo().uid);
            a.startActivity(i);
            return;
        } catch (Exception ignored) { }

        // ③ 组件直跳（不探测）
        if (tryJump(a, "com.android.settings",
                "com.android.settings.Settings$AppNotificationSettingsActivity")) return;

        // ④ 全部失败 → 送系统设置首页，用户自己找
        Ui.toast(a, "请在设置里找到「通知」");
        openSettingsHome(a);
    }

    /** 跳到系统设置首页 */
    public static void openSettingsHome(Activity a) {
        try {
            a.startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Exception e) {
            Ui.toast(a, "请手动打开系统设置");
        }
    }

    /** 尝试跳厂商自启动页；跳不动则弹手动指引 */
    public static void openAutoStart(Activity a) {
        String[][] targets = {
                {"com.vivo.appfilter", "com.vivo.appfilter.activity.StartupManagerActivityRom30"},
                {"com.vivo.appfilter", "com.vivo.appfilter.activity.StartupManagerActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.oppo.launcher", "com.oppo.launcher.setting.SettingActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
        };
        for (String[] t : targets) {
            if (tryJump(a, t[0], t[1])) return;
        }
        showManual(a, "自启动", "设置 → 应用管理 → 权限管理 → 自启动\n找到 YehoChat 并打开开关");
    }

    /** 尝试打开指定组件，成功返回 true */
    private static boolean tryJump(Activity a, String pkg, String cls) {
        try {
            Intent i = new Intent();
            i.setComponent(new android.content.ComponentName(pkg, cls));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 手动指引弹窗（跳转失败时） */
    private static void showManual(final Activity a, String what, String steps) {
        new AlertDialog.Builder(a)
                .setTitle("请手动设置「" + what + "」")
                .setMessage("本机没找到可直接跳转的" + what + "页面。\n"
                        + "请手动操作：\n\n" + steps)
                .setPositiveButton("打开设置", (d, w) -> openAppSettings(a))
                .setNegativeButton("知道了", null)
                .show();
    }
}
