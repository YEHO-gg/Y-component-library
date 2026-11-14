package com.yeho.chat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 简易网络工具：全部走 nginx 网关 /chatapi/
 * 所有请求带上 X-Chat-Token（登录返回的令牌）
 */
public class Net {

    public static final String GATE = "https://" + BuildConfig.CHAT_HOST + ":" + BuildConfig.CHAT_GATE_PORT;
    public static final String API = GATE + "/chatapi";

    static {
        // 信任自签证书（与 ChatService 的 wss 共用同一份配置，见 Tls）
        Tls.trustAllForHttpsURLConnection();
    }

    /** JSON 字符串转义：拼请求体用（原先在 3 个 Activity 里各抄了一份） */
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public interface Cb {
        void ok(String body);
        void err(String msg);
    }

    private static String req(String method, String urlStr, String json, String token) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (token != null && !token.isEmpty()) {
            c.setRequestProperty("X-Chat-Token", token);
        }
        if (json != null) {
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(json.getBytes("UTF-8"));
            os.flush();
            os.close();
        }
        int code = c.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
        }
        c.disconnect();
        return sb.toString();
    }

    public static void get(String path, String token, Cb cb) {
        new Thread(() -> {
            try { cb.ok(req("GET", API + path, null, token)); }
            catch (Exception e) { cb.err(e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }).start();
    }

    /** 走绝对地址的 GET。更新检测用的是 /update/，不在 /chatapi/ 前缀下，用这个 */
    public static void getUrl(String fullUrl, Cb cb) {
        new Thread(() -> {
            try { cb.ok(req("GET", fullUrl, null, null)); }
            catch (Exception e) { cb.err(e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }).start();
    }

    public static void post(String path, String json, String token, Cb cb) {
        new Thread(() -> {
            try { cb.ok(req("POST", API + path, json, token)); }
            catch (Exception e) { cb.err(e.getClass().getSimpleName() + ": " + e.getMessage()); }
        }).start();
    }

    // ---------- JSON 小工具（避免引入额外依赖）----------
    public static String jstr(String json, String key) {
        if (json == null) return "";
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return "";
        int c = json.indexOf(':', i + k.length());
        if (c < 0) return "";
        int start = c + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return "";
        char ch = json.charAt(start);
        if (ch == '"') {
            int end = start + 1;
            StringBuilder sb = new StringBuilder();
            while (end < json.length()) {
                char x = json.charAt(end);
                if (x == '\\') {
                    end++;
                    if (end < json.length()) sb.append(json.charAt(end));
                    end++;
                    continue;
                }
                if (x == '"') break;
                sb.append(x);
                end++;
            }
            return sb.toString();
        } else {
            int end = start;
            while (end < json.length() && ",}\n\r".indexOf(json.charAt(end)) < 0) end++;
            return json.substring(start, end).trim();
        }
    }

    public static boolean jbool(String json, String key) {
        return "true".equals(jstr(json, key));
    }
}