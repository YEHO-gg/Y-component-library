package com.yeho.chat;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;

/** 极小的 UI 工具：dp 换算、Toast、取色、夜间模式（避免各处重复写） */
public final class Ui {

    private Ui() { }

    /**
     * 把用户选的夜间模式生效到整个进程。
     *
     * 必须在任何 Activity 的 setContentView 之前调用 —— 调晚了当前界面不会重新
     * 取资源，得等下次重建才变色。所以入口 Activity 的 onCreate 第一行就调它。
     *
     * setDefaultNightMode 是全局的：它会让所有存活的 Activity 重建一次，
     * 所以设置页里改完开关不用手动刷新，界面自己就换了。
     */
    public static void applyNightMode(Context ctx) {
        AppCompatDelegate.setDefaultNightMode(new Prefs(ctx).nightMode());
    }

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static int color(Context ctx, int resId) {
        return ctx.getColor(resId);
    }

    public static void toast(Context ctx, String s) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show();
    }
}
