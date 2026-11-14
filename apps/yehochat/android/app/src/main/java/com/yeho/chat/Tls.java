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
