package com.yeho.chat;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/** 登录页（仿微信，默认登录 / 下方注册入口） */
public class LoginActivity extends AppCompatActivity {

    private EditText etAcc, etPw, etCode;
    private Button btnSendCode, btnLogin, btnToReg;
    private LinearLayout boxCode;
    private boolean useCodeMode = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    /** 60 秒倒计时（原来在这一个类里抄了两份，现在统一成 CodeBtn） */
    private CodeBtn codeBtn;
    private CodeBtn forgotCodeBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_login);
        prefs = new Prefs(this);

        etAcc = findViewById(R.id.etAcc);
        etPw = findViewById(R.id.etPw);
        etCode = findViewById(R.id.etCode);
        btnSendCode = findViewById(R.id.btnSendCode);
        btnLogin = findViewById(R.id.btnLogin);
        btnToReg = findViewById(R.id.btnToReg);
        boxCode = findViewById(R.id.boxCode);

        codeBtn = new CodeBtn(btnSendCode, ui);

        findViewById(R.id.tvSwitch).setOnClickListener(v -> switchMode());
        findViewById(R.id.tvForgot).setOnClickListener(v -> forgotPassword());

        btnSendCode.setOnClickListener(v -> sendLoginCode());
        btnLogin.setOnClickListener(v -> doLogin());
        btnToReg.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        codeBtn.stop();
        if (forgotCodeBtn != null) forgotCodeBtn.stop();
    }

    private void switchMode() {
        useCodeMode = !useCodeMode;
        if (useCodeMode) {
            boxCode.setVisibility(View.VISIBLE);
            etPw.setVisibility(View.GONE);
            ((TextView) findViewById(R.id.tvSwitch)).setText("用密码登录");
            btnLogin.setText("验证码登录");
        } else {
            boxCode.setVisibility(View.GONE);
            etPw.setVisibility(View.VISIBLE);
            ((TextView) findViewById(R.id.tvSwitch)).setText("用验证码登录");
            btnLogin.setText("登录");
        }
    }

    private void sendLoginCode() {
        String acc = etAcc.getText().toString().trim();
        if (acc.isEmpty()) { toast("先填账号"); return; }
        btnSendCode.setEnabled(false);
        Net.post("/login/code", "{\"acc\":\"" + Net.esc(acc) + "\"}", "", new Net.Cb() {
            @Override public void ok(String body) {
                ui.post(() -> {
                    if (Net.jbool(body, "ok")) {
                        toast("验证码已发到你的邮箱");
                        codeBtn.start();
                    } else {
                        toast(Net.jstr(body, "error"));
                        btnSendCode.setEnabled(true);
                    }
                });
            }
            @Override public void err(String m) {
                ui.post(() -> { toast("网络错误：" + m); btnSendCode.setEnabled(true); });
            }
        });
    }

    private void doLogin() {
        String acc = etAcc.getText().toString().trim();
        if (acc.isEmpty()) { toast("请填写账号"); return; }

        String path, json;
        if (useCodeMode) {
            String code = etCode.getText().toString().trim();
            if (code.isEmpty()) { toast("请填写验证码"); return; }
            path = "/login/verify";
            json = "{\"acc\":\"" + Net.esc(acc) + "\",\"code\":\"" + Net.esc(code) + "\"}";
        } else {
            String pw = etPw.getText().toString();
            if (pw.isEmpty()) { toast("请填写密码"); return; }
            path = "/login/pw";
            json = "{\"acc\":\"" + Net.esc(acc) + "\",\"pw\":\"" + Net.esc(pw) + "\"}";
        }

        btnLogin.setEnabled(false);
        Net.post(path, json, "", new Net.Cb() {
            @Override public void ok(String body) {
                ui.post(() -> {
                    btnLogin.setEnabled(true);
                    if (Net.jbool(body, "ok")) {
                        prefs.setToken(Net.jstr(body, "token"));
                        prefs.setUser(Net.jstr(body, "uid"), Net.jstr(body, "uuid"),
                                Net.jstr(body, "name"), acc);
                        toast("登录成功");
                        startActivity(new Intent(LoginActivity.this, ChatActivity.class));
                        finish();
                    } else {
                        toast(Net.jstr(body, "error"));
                    }
                });
            }
            @Override public void err(String m) {
                ui.post(() -> { btnLogin.setEnabled(true); toast("网络错误：" + m); });
            }
        });
    }

    // ---------- 忘记密码（账号 + 邮箱验证码 + 新密码两次输入） ----------
    private void forgotPassword() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etAcc = new EditText(this);
        etAcc.setHint("邮箱 或 UUID");
        etAcc.setText(this.etAcc.getText().toString().trim());
        box.addView(etAcc);

        final EditText etCode = new EditText(this);
        etCode.setHint("邮箱验证码");
        etCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(etCode);

        final EditText etPw = new EditText(this);
        etPw.setHint("新密码（至少 6 位）");
        etPw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etPw);

        final EditText etPw2 = new EditText(this);
        etPw2.setHint("再输一次新密码");
        etPw2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(etPw2);

        final Button btnSend = new Button(this);
        btnSend.setText("获取验证码");
        btnSend.setAllCaps(false);
        box.addView(btnSend);

        forgotCodeBtn = new CodeBtn(btnSend, ui);

        btnSend.setOnClickListener(v -> {
            String acc = etAcc.getText().toString().trim();
            if (acc.isEmpty()) { toast("请先填账号"); return; }
            btnSend.setEnabled(false);
            Net.post("/reset/code", "{\"acc\":\"" + Net.esc(acc) + "\"}", "", new Net.Cb() {
                @Override public void ok(String body) {
                    ui.post(() -> {
                        if (Net.jbool(body, "ok")) {
                            toast("验证码已发到你的邮箱");
                            forgotCodeBtn.start();
                        } else {
                            toast(Net.jstr(body, "error"));
                            btnSend.setEnabled(true);
                        }
                    });
                }
                @Override public void err(String m) {
                    ui.post(() -> { btnSend.setEnabled(true); toast("网络错误：" + m); });
                }
            });
        });

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("重置密码")
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String acc = etAcc.getText().toString().trim();
            String code = etCode.getText().toString().trim();
            String pw = etPw.getText().toString();
            String pw2 = etPw2.getText().toString();
            if (acc.isEmpty()) { toast("请填写账号"); return; }
            if (code.isEmpty()) { toast("请填写验证码"); return; }
            if (pw.length() < 6) { toast("密码至少 6 位"); return; }
            if (!pw.equals(pw2)) {
                toast("两次密码不一致，请重新输入");
                etPw.setText("");
                etPw2.setText("");
                etPw.requestFocus();
                return;
            }
            Net.post("/reset/verify",
                    "{\"acc\":\"" + Net.esc(acc) + "\",\"code\":\"" + Net.esc(code)
                            + "\",\"pw\":\"" + Net.esc(pw) + "\",\"pw2\":\"" + Net.esc(pw2) + "\"}",
                    "", new Net.Cb() {
                        @Override public void ok(String body) {
                            ui.post(() -> {
                                if (Net.jbool(body, "ok")) {
                                    toast("密码已重置，请用新密码登录");
                                    dlg.dismiss();
                                    etPw.setText("");
                                } else {
                                    toast(Net.jstr(body, "error"));
                                }
                            });
                        }
                        @Override public void err(String m) {
                            ui.post(() -> toast("网络错误：" + m));
                        }
                    });
        });
    }

    private void toast(String s) {
        Ui.toast(this, s);
    }
}