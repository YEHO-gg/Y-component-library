package com.yeho.chat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 版本检测 + 应用内一键更新。
 *
 * 服务端接口（nginx 的 /update/ 转到 127.0.0.1:8811）：
 *   GET /update/check?app=yehochat&amp;ver=&lt;versionCode&gt;
 *   -&gt; {ok, hasUpdate, versionCode, versionName, notes, force, size, url}
 *
 * 更新流程（2026-10-02 起改成 App 自己干）：
 *   点「立即更新」→ 下载 → 判断是外层 ZIP 还是裸 apk → 取出 apk →
 *   通过 FileProvider 拉起系统安装器。用户全程看不到压缩包。
 *
 * 为什么要处理 ZIP：
 *   服务端把包放在阿里云 OSS 上分发，而阿里云**禁止用 OSS 默认域名对公网
 *   分发 .apk**（返回 ApkDownloadForbidden），所以发布时会额外打一个真 ZIP。
 *   但 OSS 不可用时服务端会回退到本机直发**原始 apk** —— 两种都要能装。
 *   判据见 extractApk()。
 *
 * 为什么以前不做应用内安装、现在能做了：
 *   老注释说"vivo 这类国产 ROM 会拦截未知来源安装"。实际拦截的是**没有引导
 *   用户去开权限**的情况。Android 8+ 只要先引导用户打开「安装未知应用」，
 *   再用 FileProvider 交 content:// 给安装器，主流 ROM 都是通的。
 *   仍然保留浏览器兜底：任何一步失败都退回去，不能让用户卡死在更新上。
 */
public final class UpdateChecker {

    private UpdateChecker() { }

    /** 检查更新的入口（走网关 https，证书是自签的，Net 里已经信任） */
    private static final String CHECK = Net.GATE + "/update/check?app=yehochat&ver=";

    /** 下载/解压的工作目录，必须和 res/xml/file_paths.xml 里声明的一致 */
    private static final String WORK_DIR = "updates";

    public static int versionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;   // 拿不到就当 0，服务端会认为"比任何版本都旧"
        }
    }

    public static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public interface Done {
        void ok(JSONObject result);
        void err(String msg);
    }

    /** 静默问一次服务器；网络不通、服务器还没发布过版本，都只是走 err 分支，不打扰用户 */
    public static void check(Context ctx, final Done cb) {
        Net.getUrl(CHECK + versionCode(ctx), new Net.Cb() {
            @Override public void ok(String body) {
                try {
                    cb.ok(new JSONObject(body));
                } catch (Exception e) {
                    cb.err("返回格式不对：" + e.getMessage());
                }
            }
            @Override public void err(String m) {
                cb.err(m);
            }
        });
    }

    /**
     * 检查并按需弹窗。
     * @param manual 用户主动点的（设置页「版本」那一行）：没更新 / 失败都要给个回音；
     *               启动时的自动检查传 false，只有真有新版本才出声。
     */
    public static void checkAndPrompt(final Context ctx, final boolean manual) {
        check(ctx, new Done() {
            @Override public void ok(JSONObject o) {
                if (!o.optBoolean("hasUpdate", false)) {
                    if (manual) toast(ctx, "已是最新版本 v" + versionName(ctx));
                    return;
                }
                showDialog(ctx, o);
            }

            @Override public void err(String m) {
                if (manual) toast(ctx, "检查更新失败：" + m);
            }
        });
    }

    private static void toast(final Context ctx, final String s) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, s, Toast.LENGTH_LONG).show();
            } catch (Exception ignored) { }
        });
    }

    private static void showDialog(final Context ctx, final JSONObject o) {
        final String ver = o.optString("versionName", "");
        final String notes = o.optString("notes", "").trim();
        final boolean force = o.optBoolean("force", false);
        final long size = o.optLong("size", 0);
        final String url = Net.GATE + o.optString("url", "");

        StringBuilder sb = new StringBuilder();
        sb.append("当前版本：v").append(versionName(ctx)).append('\n');
        sb.append("最新版本：v").append(ver).append('\n');
        if (size > 0) {
            sb.append("安装包大小：")
              .append(String.format(Locale.CHINA, "%.1f MB", size / 1024.0 / 1024.0))
              .append('\n');
        }
        if (!notes.isEmpty()) {
            sb.append("\n更新内容：\n").append(notes);
        }
        sb.append("\n\n点「立即更新」会自动下载并打开安装界面。");
        if (force) {
            sb.append("\n\n⚠️ 这是一个强制更新版本。");
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                        .setTitle("发现新版本 v" + ver)
                        .setMessage(sb.toString())
                        .setCancelable(!force)
                        .setPositiveButton("立即更新", (d, w) -> startUpdate(ctx, url, ver))
                        // 兜底：万一自动更新被 ROM 拦了，还能退回浏览器那条老路
                        .setNeutralButton("用浏览器", (d, w) -> openBrowser(ctx, url));
                if (!force) b.setNegativeButton("稍后再说", null);
                b.show();
            } catch (Exception ignored) {
                // 极端情况下（比如 Activity 正在销毁）弹不出来就算了，别把主流程搞崩
            }
        });
    }

    private static void openBrowser(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(ctx instanceof Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(i);
        } catch (Exception e) {
            toast(ctx, "打不开浏览器，请手动访问：\n" + url);
        }
    }

    // ================= 应用内更新 =================

    /**
     * 更新入口：先确认有没有「安装未知应用」的权限，没有就先引导去开。
     *
     * 这一步不能省 —— Android 8+ 没这个权限时，安装器会直接被系统拒掉，
     * 表现是"点了没反应"，很难查。ROM 差异主要也在这里。
     */
    private static void startUpdate(final Context ctx, final String url, final String ver) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean allowed;
            try {
                allowed = ctx.getPackageManager().canRequestPackageInstalls();
            } catch (Exception e) {
                allowed = true;     // 查不了就别拦着，让安装器自己去报错
            }
            if (!allowed) {
                askInstallPermission(ctx, url, ver);
                return;
            }
        }
        downloadAndInstall(ctx, url, ver);
    }

    private static void askInstallPermission(final Context ctx, final String url, final String ver) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                new AlertDialog.Builder(ctx)
                        .setTitle("还需要一个权限")
                        .setMessage("安装更新需要你允许 YehoChat「安装未知应用」。\n\n"
                                + "点「去设置」打开那个开关，回来再点一次「立即更新」就行。\n\n"
                                + "（如果找不到那个开关，页面上通常会有「允许来自此来源」的提示。"
                                + "实在不行就点「用浏览器」走老路子。）")
                        .setPositiveButton("去设置", (d, w) -> {
                            try {
                                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + ctx.getPackageName()));
                                if (!(ctx instanceof Activity)) {
                                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                }
                                ctx.startActivity(i);
                            } catch (Exception e) {
                                toast(ctx, "打不开设置页，请手动到：\n"
                                        + "系统设置 → 应用 → YehoChat → 安装未知应用");
                            }
                        })
                        .setNegativeButton("用浏览器", (d, w) -> openBrowser(ctx, url))
                        .show();
            } catch (Exception e) {
                openBrowser(ctx, url);      // 弹不出来就直接退回浏览器
            }
        });
    }

    private static void downloadAndInstall(final Context ctx, final String url, final String ver) {
        AlertDialog d = null;
        try {
            d = new AlertDialog.Builder(ctx)
                    .setTitle("正在更新到 v" + ver)
                    .setMessage("准备中…")
                    .setCancelable(false)
                    .create();
            d.show();
        } catch (Exception e) {
            d = null;   // 弹不出来也照常更新，只是没进度可看
        }
        final AlertDialog dlg = d;

        new Thread(() -> {
            try {
                File dir = new File(ctx.getCacheDir(), WORK_DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new Exception("建不了缓存目录");
                }

                // 下载地址是网关的 /update/dl/...，服务端通常 302 到 OSS 上的
                // ZIP。HttpURLConnection 对 GET 默认跟随同协议跳转，不用手动处理。
                File pkg = new File(dir, "update.pkg");
                setMsg(dlg, "正在下载…");
                download(url, pkg, dlg);

                setMsg(dlg, "正在解压…");
                File apk = extractApk(pkg, dir);
                try { pkg.delete(); } catch (Exception ignored) { }   // 包用完就删，别占缓存

                setMsg(dlg, "正在打开安装界面…");
                Thread.sleep(400);          // 让最后一句看得见，不然一闪就没了
                dismiss(dlg);
                install(ctx, apk);
            } catch (Exception e) {
                dismiss(dlg);
                String m = e.getMessage();
                if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
                toast(ctx, "自动更新失败（" + m + "）\n改从浏览器下载");
                openBrowser(ctx, url);
            }
        }).start();
    }

    private static void download(String url, File dest, final AlertDialog dlg) throws Exception {
        Tls.trustAllForHttpsURLConnection();     // 网关是自签证书
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        // 别让服务端再压一层，否则 contentLength 是压缩后的，进度算不准
        c.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

            long total = c.getContentLength();       // 服务端没给就是 -1
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[65536];
            long got = 0;
            int last = -1, n;
            try {
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        int pct = (int) (got * 100 / total);
                        if (pct >= last + 5) {       // 每 5% 刷一次，别刷太频
                            last = pct;
                            setMsg(dlg, "正在下载… " + pct + "%");
                        }
                    }
                }
            } finally {
                try { out.flush(); } catch (Exception ignored) { }
                try { out.close(); } catch (Exception ignored) { }
                try { in.close(); } catch (Exception ignored) { }
            }
            if (got <= 0) throw new Exception("下载到 0 字节");
        } finally {
            c.disconnect();
        }
    }

    /**
     * 从下载到的文件里取出可安装的 apk。
     *
     * 服务端可能给两种东西，而且**不能靠文件头判断**（apk 本身也是 zip，
     * 都以 PK\x03\x04 开头）：
     *   1. 外层 ZIP，里面才装着 apk  —— 走 OSS 时是这样
     *   2. 直接的 apk                —— OSS 不可用、回退到本机直发时是这样
     *
     * 判据用**压缩包里有没有一个顶层的 .apk 条目**：
     * 有 → 是外层包装，取出来；没有 → 它自己就是 apk，原样拿去装。
     */
    private static File extractApk(File pkg, File dir) throws Exception {
        ZipFile zf = null;
        try {
            zf = new ZipFile(pkg);

            ZipEntry pick = null;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String nm = e.getName();
                if (nm.indexOf('/') >= 0) continue;      // 只看顶层，别误取嵌套的
                if (nm.toLowerCase(Locale.US).endsWith(".apk")) { pick = e; break; }
            }

            File out = new File(dir, "yehochat-update.apk");
            if (pick == null) {
                copyFile(pkg, out);                      // 它自己就是 apk
            } else {
                InputStream in = zf.getInputStream(pick);
                OutputStream os = new FileOutputStream(out);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    try { os.flush(); } catch (Exception ignored) { }
                    try { os.close(); } catch (Exception ignored) { }
                    try { in.close(); } catch (Exception ignored) { }
                }
            }
            if (out.length() <= 0) throw new Exception("解出来的 apk 是空的");
            return out;
        } catch (java.util.zip.ZipException ze) {
            throw new Exception("下载到的不是有效安装包，可能被网络劫持了");
        } finally {
            if (zf != null) try { zf.close(); } catch (Exception ignored) { }
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            try { out.flush(); } catch (Exception ignored) { }
            try { out.close(); } catch (Exception ignored) { }
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    /** 把 apk 交给系统安装器。Android 7 起必须走 content://（FileProvider） */
    private static void install(Context ctx, File apk) throws Exception {
        Uri uri = FileProvider.getUriForFile(
                ctx, ctx.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    // ---------- 进度对话框的小工具（dlg 可能是 null，都按空处理）----------

    private static void setMsg(final AlertDialog dlg, final String msg) {
        if (dlg == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try { if (dlg.isShowing()) dlg.setMessage(msg); } catch (Exception ignored) { }
        });
    }

    private static void dismiss(final AlertDialog dlg) {
        if (dlg == null) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try { if (dlg.isShowing()) dlg.dismiss(); } catch (Exception ignored) { }
        });
    }
}
