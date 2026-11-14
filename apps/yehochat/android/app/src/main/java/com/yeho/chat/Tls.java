package com.yeho.chat;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS 配置的唯一来源（原来是 Net 和 ChatService 各写一份、还不一致）。
 *
 * 注意：服务器用的是自签证书，**信任所有证书是本项目的既定做法**，
 * 改成严格校验会直接连不上。这里只做「合并去重」，不改变信任策略。
 *
 * 安全现状（说清楚，别让人误以为换了正式证书就安全了）：
 * <ul>
 *   <li>{@link #manager()} 的 checkServerTrusted 是空实现 —— 不校验证书链；</li>
 *   <li>{@link #trustAllForHttpsURLConnection()} 把 HostnameVerifier 恒置为 true —— 不校验主机名；</li>
 *   <li>因此**换成正式证书也不会带来任何额外保护**，中间人依然可以截获。</li>
 * </ul>
 * TODO(安全)：要做证书指纹 pinning 时，在 checkServerTrusted 里比对自己的
 * SHA-256 指纹，并把 HostnameVerifier 恢复成默认实现。改动前请先确认
 * 客户端与服务端证书的轮换方式，否则升级会把所有人挡在外面。
 *
 * · HTTPS 接口（Net）：{@link #trustAllForHttpsURLConnection()}
 * · WebSocket（ChatService 的 OkHttpClient）：用 {@link #context()} + {@link #manager()}
 */
public final class Tls {

    private Tls() { }

    private static X509TrustManager TM;
    private static SSLContext CTX;

    /** 信任所有的 X509TrustManager（单例，供 OkHttp 的 sslSocketFactory 配对使用） */
    public static synchronized X509TrustManager manager() {
        if (TM == null) {
            TM = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
        }
        return TM;
    }

    /** 信任所有的 SSLContext（单例） */
    public static synchronized SSLContext context() {
        if (CTX == null) {
            try {
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, new TrustManager[]{manager()}, new SecureRandom());
                CTX = sc;
            } catch (Exception e) {
                CTX = null;
            }
        }
        return CTX;
    }

    /** 把「信任自签证书」应用到全局 HttpsURLConnection（Net 走的就是这条路） */
    public static void trustAllForHttpsURLConnection() {
        try {
            SSLContext sc = context();
            if (sc == null) return;
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) { }
    }
}
