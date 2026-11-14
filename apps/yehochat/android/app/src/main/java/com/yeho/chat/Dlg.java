package com.yeho.chat;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

/** 统一的「一个输入框 + 确定/取消」弹窗（改名 / 改 UUID / 各种数字设置都用它） */
public final class Dlg {

    private Dlg() { }

    public interface OnText { void ok(String text); }

    public static void input(Activity a, String title, String hint,
                             String initial, boolean number, OnText cb) {
        EditText et = new EditText(a);
        et.setHint(hint);
        if (initial != null) et.setText(initial);
        if (number) et.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    if (cb != null) cb.ok(et.getText().toString().trim());
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
