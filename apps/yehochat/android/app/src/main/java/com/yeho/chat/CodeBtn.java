package com.yeho.chat;

import android.os.Handler;
import android.widget.Button;

/**
 * 「获取验证码」按钮的 60 秒倒计时。
 *
 * 原先这段逻辑在 LoginActivity（登录页 1 处 + 忘记密码弹窗里 1 处）、
 * RegisterActivity、ChatActivity（改密码弹窗里）各抄了一遍，共 4 份。
 * 现在统一成这一个类，行为完全一致：
 *   发送前按钮置灰 -> 收到成功回调后 start() -> 显示 60s…1s -> 归位为「获取验证码」。
 */
public final class CodeBtn implements Runnable {

    /** 倒计时秒数（四处原本都是 60） */
    private static final int SECONDS = 60;

    private final Button btn;
    private final Handler ui;
    private final String idleText;

    private int left = 0;

    public CodeBtn(Button btn, Handler ui) {
        this(btn, ui, "获取验证码");
    }

    public CodeBtn(Button btn, Handler ui, String idleText) {
        this.btn = btn;
        this.ui = ui;
        this.idleText = idleText;
    }

    /** 从 60 秒开始重新倒计时（按钮会立即置灰） */
    public void start() {
        left = SECONDS;
        btn.setEnabled(false);
        ui.removeCallbacks(this);
        ui.post(this);
    }

    /** 页面销毁时调用，避免 Handler 泄漏 */
    public void stop() {
        ui.removeCallbacks(this);
    }

    @Override
    public void run() {
        if (left <= 0) {
            btn.setEnabled(true);
            btn.setText(idleText);
            return;
        }
        btn.setText(left + "s");
        left--;
        ui.postDelayed(this, 1000);
    }
}
