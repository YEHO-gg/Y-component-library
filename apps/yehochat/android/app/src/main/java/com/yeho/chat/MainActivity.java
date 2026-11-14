package com.yeho.chat;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** 入口：已登录进聊天页，否则进登录页 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        // 必须赶在任何界面取资源之前生效，所以放在第一行（super 之前也行，
        // 但 AppCompatDelegate 不依赖 super 的结果，放前面纯粹是为了显眼）
        Ui.applyNightMode(this);
        super.onCreate(b);
        Prefs prefs = new Prefs(this);
        if (prefs.loggedIn()) {
            startActivity(new Intent(this, ChatActivity.class));
        } else {
            startActivity(new Intent(this, LoginActivity.class));
        }
        finish();
    }
}