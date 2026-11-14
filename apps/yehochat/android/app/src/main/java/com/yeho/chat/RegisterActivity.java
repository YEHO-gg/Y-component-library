package com.yeho.chat;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

/** 注册页（邮箱 + 密码×2 + 4位邮箱验证码） */
public class RegisterActivity extends AppCompatActivity {

    private EditText etEmail, etPw, etPw2, etCode;
    private Button btnSend, btnReg;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 60 秒倒计时（与登录页/改密码弹窗共用同一份实现） */
    private CodeBtn codeBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_register);

        etEmail = findViewById(R.id.etEmail);
        etPw = findViewById(R.id.etPw);
        etPw2 = findViewById(R.id.etPw2);
        etCode = findViewById(R.id.etCode);
        btnSend = findViewById(R.id.btnSend);
        btnReg = findViewById(R.id.btnReg);

        codeBtn = new CodeBtn(btnSend, ui);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSend.setOnClickListener(v -> sendCode());
        btnReg.setOnClickListener(v -> doRegister());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        codeBtn.stop();
    }

    private void sendCode() {
        String email = etEmail.getText().toString().trim();
        if (email.isEmpty()) { toast("请先填邮箱"); return; }
        if (!email.contains("@")) { toast("邮箱格式不对"); return; }
        btnSend.setEnabled(false);
        Net.post("/reg/code", "{\"email\":\"" + Net.esc(email) + "\"}", "", new Net.Cb() {
            @Override public void ok(String body) {
                ui.post(() -> {
                    if (Net.jbool(body, "ok")) {
                        toast("验证码已发到邮箱");
                        codeBtn.start();
                    } else {
                        toast(Net.jstr(body, "error"));
                        btnSend.setEnabled(true);
                    }
                });
            }
            @Override public void err(String m) {
                ui.post(() -> { toast("网络错误：" + m); btnSend.setEnabled(true); });
            }
        });
    }

    private void doRegister() {
        String email = etEmail.getText().toString().trim();
        String pw = etPw.getText().toString();
        String pw2 = etPw2.getText().toString();
        String code = etCode.getText().toString().trim();

        if (email.isEmpty()) { toast("请填写邮箱"); return; }
        if (pw.length() < 6) { toast("密码至少 6 位"); return; }
        if (!pw.equals(pw2)) {
            toast("两次密码不一致，请重新输入");
            etPw.setText("");
            etPw2.setText("");
            etPw.requestFocus();
            return;
        }
        if (code.isEmpty()) { toast("请填写验证码"); return; }

        btnReg.setEnabled(false);
        String json = "{\"email\":\"" + Net.esc(email) + "\",\"pw\":\"" + Net.esc(pw)
                + "\",\"pw2\":\"" + Net.esc(pw2) + "\",\"code\":\"" + Net.esc(code) + "\"}";
        Net.post("/reg/verify", json, "", new Net.Cb() {
            @Override public void ok(String body) {
                ui.post(() -> {
                    btnReg.setEnabled(true);
                    if (Net.jbool(body, "ok")) {
                        String uuid = Net.jstr(body, "uuid");
                        String name = Net.jstr(body, "name");
                        toast("注册成功！你的 UUID：" + uuid);
                        finish();
                    } else {
                        toast(Net.jstr(body, "error"));
                    }
                });
            }
            @Override public void err(String m) {
                ui.post(() -> { btnReg.setEnabled(true); toast("网络错误：" + m); });
            }
        });
    }

    private void toast(String s) {
        Ui.toast(this, s);
    }
}