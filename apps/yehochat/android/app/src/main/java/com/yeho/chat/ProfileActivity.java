package com.yeho.chat;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

/**
 * 「我的」Tab：个人资料 + 账号操作。
 *
 * 这些功能原先全塞在 ChatActivity 右上角的「我的」菜单里（改名 / 改密码 / 改 UUID /
 * 后台引导 / 退出登录），现在搬到这里。**请求路径、参数、成功后的本地处理一字未改**：
 *   · 改名        POST /set/name   {"name":..}      成功后同时往 WS 发 {"type":"name"}
 *   · 改密码      POST /me/change_code {}  ->  POST /me/change_pw {"code":..,"pw":..,"pw2":..}
 *   · 改 UUID     POST /set/uuid   {"uuid":..}
 *   · 我的资料    GET  /me
 *   · 退出登录    停服务 + prefs.logout() + 回登录页
 */
public class ProfileActivity extends AppCompatActivity {

    private Prefs prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvAvatar, tvName, tvSub;
    private LinearLayout cardAccount, cardActions;

    /** 改密码弹窗里的倒计时，页面销毁时停掉 */
    private CodeBtn codeBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        if (!prefs.loggedIn()) {
            finish();
            return;
        }
        setContentView(R.layout.activity_profile);

        tvAvatar = findViewById(R.id.tvAvatar);
        tvName = findViewById(R.id.tvName);
        tvSub = findViewById(R.id.tvSub);
        cardAccount = findViewById(R.id.cardAccount);
        cardActions = findViewById(R.id.cardActions);

        TabBar.attach(this, TabBar.TAB_ME);
        buildRows();
        refreshHeader();
    }

    private void buildRows() {
        Rows.add(this, cardAccount, "刷新我的资料", null, this::loadProfile);
        Rows.add(this, cardAccount, "改名", null, this::editName);
        Rows.add(this, cardAccount, "修改密码", null, this::changePassword);
        Rows.add(this, cardAccount, "修改 UUID", null, this::editUuid);

        Rows.add(this, cardActions, "退出登录", null,
                this::confirmLogout, R.color.danger);
    }

    private void refreshHeader() {
        String name = prefs.name() == null ? "" : prefs.name();
        String uuid = prefs.uuid() == null ? "" : prefs.uuid();
        tvName.setText(name.isEmpty() ? "（未命名）" : name);
        tvSub.setText(uuid.isEmpty() ? "UUID：—" : ("UUID：" + uuid));
        tvAvatar.setText(name.isEmpty() ? "Y" : name.substring(0, 1).toUpperCase());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.loggedIn()) {
            finish();
            return;
        }
        refreshHeader();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (codeBtn != null) codeBtn.stop();
    }

    // ---------- 拉自己的资料 ----------
    private void loadProfile() {
        Net.get("/me", prefs.token(), new Net.Cb() {
            @Override public void ok(String body) {
                ui.post(() -> showProfile(body));
            }
            @Override public void err(String m) {
                ui.post(() -> Ui.toast(ProfileActivity.this, "网络错误：" + m));
            }
        });
    }

    private void showProfile(String body) {
        String name = Net.jstr(body, "name");
        String uuid = Net.jstr(body, "uuid");
        String uid = Net.jstr(body, "uid");
        String email = Net.jstr(body, "email");

        StringBuilder sb = new StringBuilder();
        if (!name.isEmpty()) sb.append("昵称：").append(name).append('\n');
        if (!uuid.isEmpty()) sb.append("UUID：").append(uuid).append('\n');
        if (!uid.isEmpty()) sb.append("用户 ID：").append(uid).append('\n');
        if (!email.isEmpty()) sb.append("邮箱：").append(email).append('\n');

        // 服务器返回的字段名如果有变化，至少把原文给出来，不至于什么都看不到
        String msg = sb.length() > 0 ? sb.toString().trim() : body;

        new AlertDialog.Builder(this)
                .setTitle("我的资料")
                .setMessage(msg == null || msg.isEmpty() ? "（服务器没有返回内容）" : msg)
                .setPositiveButton("好", null)
                .show();

        // 顺手用服务器返回的权威值刷新本地（字段缺失时不动原有值）
        if (!name.isEmpty() || !uuid.isEmpty()) {
            prefs.setUser(
                    uid.isEmpty() ? prefs.uid() : uid,
                    uuid.isEmpty() ? prefs.uuid() : uuid,
                    name.isEmpty() ? prefs.name() : name,
                    email.isEmpty() ? prefs.email() : email);
            refreshHeader();
        }
    }

    // ---------- 改名 ----------
    private void editName() {
        Dlg.input(this, "改名", "1-20 位中英文/数字", prefs.name(), false, n -> {
            if (n.isEmpty()) { Ui.toast(this, "名字不能为空"); return; }
            Net.post("/set/name", "{\"name\":\"" + Net.esc(n) + "\"}", prefs.token(), new Net.Cb() {
                @Override public void ok(String body) {
                    ui.post(() -> {
                        if (Net.jbool(body, "ok")) {
                            prefs.setUser(prefs.uid(), prefs.uuid(), n, prefs.email());
                            // 同步告诉服务器改昵称（原逻辑原样保留）
                            try {
                                JSONObject o = new JSONObject();
                                o.put("type", "name");
                                o.put("name", n);
                                ChatService.send(o.toString());
                            } catch (Exception ignored) { }
                            refreshHeader();
                            Ui.toast(ProfileActivity.this, "已改成 " + n);
                        } else Ui.toast(ProfileActivity.this, Net.jstr(body, "error"));
                    });
                }
                @Override public void err(String m) {
                    ui.post(() -> Ui.toast(ProfileActivity.this, "网络错误"));
                }
            });
        });
    }

    // ---------- 改 UUID ----------
    private void editUuid() {
        Dlg.input(this, "改 UUID（每 30 天一次）", "字母数字，≤20 位", prefs.uuid(), false, u -> {
            if (u.isEmpty()) { Ui.toast(this, "UUID 不能为空"); return; }
            Net.post("/set/uuid", "{\"uuid\":\"" + Net.esc(u) + "\"}", prefs.token(), new Net.Cb() {
                @Override public void ok(String body) {
                    ui.post(() -> {
                        if (Net.jbool(body, "ok")) {
                            prefs.setUser(prefs.uid(), u, prefs.name(), prefs.email());
                            refreshHeader();
                            Ui.toast(ProfileActivity.this, "UUID 已改成 " + u);
                        } else Ui.toast(ProfileActivity.this, Net.jstr(body, "error"));
                    });
                }
                @Override public void err(String m) {
                    ui.post(() -> Ui.toast(ProfileActivity.this, "网络错误"));
                }
            });
        });
    }

    // ---------- 改密码（邮箱验证码 + 新密码两次输入） ----------
    private void changePassword() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etCode = new EditText(this);
        etCode.setHint("邮箱验证码");
        etCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        box.addView(etCode);

        final EditText etPw = new EditText(this);
        etPw.setHint("新密码（至少 6 位）");
        etPw.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etPw);

        final EditText etPw2 = new EditText(this);
        etPw2.setHint("再输一次新密码");
        etPw2.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etPw2);

        final Button btnSend = new Button(this);
        btnSend.setText("获取验证码");
        btnSend.setAllCaps(false);
        box.addView(btnSend);

        codeBtn = new CodeBtn(btnSend, ui);

        btnSend.setOnClickListener(v -> {
            btnSend.setEnabled(false);
            Net.post("/me/change_code", "{}", prefs.token(), new Net.Cb() {
                @Override public void ok(String body) {
                    ui.post(() -> {
                        if (Net.jbool(body, "ok")) {
                            Ui.toast(ProfileActivity.this, "验证码已发到你的邮箱");
                            codeBtn.start();
                        } else {
                            Ui.toast(ProfileActivity.this, Net.jstr(body, "error"));
                            btnSend.setEnabled(true);
                        }
                    });
                }
                @Override public void err(String m) {
                    ui.post(() -> {
                        btnSend.setEnabled(true);
                        Ui.toast(ProfileActivity.this, "网络错误：" + m);
                    });
                }
            });
        });

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("改密码")
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        // 手动接管「确定」，校验不通过时不关对话框
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code = etCode.getText().toString().trim();
            String pw = etPw.getText().toString();
            String pw2 = etPw2.getText().toString();
            if (code.isEmpty()) { Ui.toast(this, "请填写验证码"); return; }
            if (pw.length() < 6) { Ui.toast(this, "密码至少 6 位"); return; }
            if (!pw.equals(pw2)) {
                Ui.toast(this, "两次密码不一致，请重新输入");
                etPw.setText("");
                etPw2.setText("");
                etPw.requestFocus();
                return;
            }
            Net.post("/me/change_pw",
                    "{\"code\":\"" + Net.esc(code) + "\",\"pw\":\"" + Net.esc(pw)
                            + "\",\"pw2\":\"" + Net.esc(pw2) + "\"}",
                    prefs.token(), new Net.Cb() {
                        @Override public void ok(String body) {
                            ui.post(() -> {
                                if (Net.jbool(body, "ok")) {
                                    Ui.toast(ProfileActivity.this, "密码已修改，请重新登录");
                                    dlg.dismiss();
                                    doLogout();
                                } else {
                                    Ui.toast(ProfileActivity.this, Net.jstr(body, "error"));
                                }
                            });
                        }
                        @Override public void err(String m) {
                            ui.post(() -> Ui.toast(ProfileActivity.this, "网络错误：" + m));
                        }
                    });
        });
    }

    // ---------- 退出登录 ----------
    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("退出登录")
                .setMessage("退出后需重新登录，本地消息缓存会保留。")
                .setPositiveButton("退出", (d, w) -> doLogout())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 停服务 + 清登录态 + 回登录页；用 CLEAR_TASK 把三个 Tab 页一起收掉，避免留下旧账号的页面 */
    private void doLogout() {
        try {
            stopService(new Intent(this, ChatService.class));
        } catch (Exception ignored) { }
        prefs.logout();
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
