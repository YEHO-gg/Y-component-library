package com.yeho.chat;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地偏好：登录K、当前用户信息、缓存清理设置 */
public class Prefs {

    private static final String NAME = "yeho_chat";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    // ---- 登录态 ----
    public String token() { return sp.getString("token", ""); }
    public void setToken(String t) { sp.edit().putString("token", t).apply(); }

    public String uid() { return sp.getString("uid", ""); }
    public String uuid() { return sp.getString("uuid", ""); }
    public String name() { return sp.getString("name", ""); }
    public String email() { return sp.getString("email", ""); }

    public void setUser(String uid, String uuid, String name, String email) {
        sp.edit().putString("uid", uid).putString("uuid", uuid)
                 .putString("name", name).putString("email", email).apply();
    }

    public boolean loggedIn() { return !token().isEmpty(); }

    public void logout() {
        sp.edit().remove("token").remove("uid").remove("uuid")
                 .remove("name").remove("email").apply();
    }

    // ---- 缓存清理设置 ----
    // 模式：0=按条数 1=按大小 2=按天数 3=无上限
    public int cleanMode() { return sp.getInt("clean_mode", 0); }
    public void setCleanMode(int m) { sp.edit().putInt("clean_mode", m).apply(); }

    public int maxCount() { return sp.getInt("max_count", 150); }       // 50~1000
    public void setMaxCount(int n) { sp.edit().putInt("max_count", n).apply(); }

    public float maxMB() { return sp.getFloat("max_mb", 50f); }         // 1~3072 (1MB~3GB)
    public void setMaxMB(float mb) { sp.edit().putFloat("max_mb", mb).apply(); }

    public int maxDays() { return sp.getInt("max_days", 30); }          // 1~60
    public void setMaxDays(int d) { sp.edit().putInt("max_days", d).apply(); }

    // ---- 消息免打扰 ----
    // dnd_until: 0 = 未开启；-1 = 一直开启；>0 = 到该时刻(秒)自动结束
    public long dndUntil() { return sp.getLong("dnd_until", 0L); }
    public void setDndUntil(long t) { sp.edit().putLong("dnd_until", t).apply(); }
    public boolean dndOn() {
        long t = dndUntil();
        if (t == 0L) return false;
        if (t == -1L) return true;
        if (t > System.currentTimeMillis() / 1000L) return true;
        setDndUntil(0L);
        return false;
    }
    /** 剩余秒数；-1 表示"一直开启"；0 表示未开启 */
    public long dndLeft() {
        long t = dndUntil();
        if (t == 0L) return 0;
        if (t == -1L) return -1;
        return Math.max(0, t - System.currentTimeMillis() / 1000L);
    }

    // ---- 后台引导弹窗是否已看过 ----
    public boolean bgGuided() { return sp.getBoolean("bg_guided", false); }
    public void setBgGuided(boolean b) { sp.edit().putBoolean("bg_guided", b).apply(); }

    // ---- 夜间模式 ----
    // 直接存 AppCompatDelegate 的常量值，省一层映射：
    //   MODE_NIGHT_FOLLOW_SYSTEM = -1
    //   MODE_NIGHT_NO            =  1
    //   MODE_NIGHT_YES           =  2
    // 默认 -1 = 跟随系统。用户没主动选过就别替他做主。
    // （0 在 AppCompat 里是已废弃的 MODE_NIGHT_AUTO「按时间自动」，所以默认取 -1 而不是 0）
    public int nightMode() { return sp.getInt("night_mode", -1); }
    public void setNightMode(int m) { sp.edit().putInt("night_mode", m).apply(); }
}