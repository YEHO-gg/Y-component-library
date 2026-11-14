package com.yeho.chat;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「我的」「设置」两个 Tab 里的设置行。
 * 行为：左侧标题 + 右侧当前值 + 右箭头，可点，带点击态；
 * 行与行之间加一条浅分隔线（首行不加，避免贴着圆角卡片顶边）。
 */
public final class Rows {

    private Rows() { }

    public static LinearLayout add(Activity a, LinearLayout parent,
                                   String label, String value, Runnable onClick) {
        return add(a, parent, label, value, onClick, R.color.text_body);
    }

    public static LinearLayout add(Activity a, LinearLayout parent,
                                   String label, String value, Runnable onClick,
                                   int labelColorRes) {
        // 分隔线（第一行不加）
        if (parent.getChildCount() > 0) {
            View div = new View(a);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(a, 0.5f)));
            int inset = Ui.dp(a, 16);
            dlp.leftMargin = inset;
            dlp.rightMargin = inset;
            div.setLayoutParams(dlp);
            div.setBackgroundColor(Ui.color(a, R.color.line_soft));
            parent.addView(div);
        }

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 52)));
        row.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 16), 0);
        row.setBackgroundResource(R.drawable.row_item_bg);

        TextView tvLabel = new TextView(a);
        tvLabel.setText(label);
        tvLabel.setTextSize(16f);
        tvLabel.setTextColor(Ui.color(a, labelColorRes));
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        // 右侧当前值（即使为空也建出来，方便后面 Rows.setValue 直接改文字）
        TextView tvVal = new TextView(a);
        tvVal.setText(value == null ? "" : value);
        tvVal.setTextSize(14f);
        tvVal.setTextColor(Ui.color(a, R.color.text_sub));
        tvVal.setSingleLine(true);
        tvVal.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvVal.setMaxWidth(Ui.dp(a, 180));
        // 显式给 WRAP_CONTENT：水平 LinearLayout 的默认 params 高度是 MATCH_PARENT，
        // 会让文字顶在 52dp 行高最上面，吃不到父容器的 CENTER_VERTICAL
        tvVal.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tvVal.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tvVal);

        TextView tvArrow = new TextView(a);
        tvArrow.setText("›");
        tvArrow.setTextSize(18f);
        tvArrow.setTextColor(Ui.color(a, R.color.text_muted));
        tvArrow.setPadding(Ui.dp(a, 6), 0, 0, 0);
        tvArrow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        tvArrow.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(tvArrow);

        if (onClick != null) {
            row.setOnClickListener(v -> onClick.run());
        } else {
            row.setClickable(false);
        }
        parent.addView(row);
        return row;
    }

    /** 改某一行右侧显示的当前值（行内的第 2 个子 View） */
    public static void setValue(LinearLayout row, String value) {
        if (row == null || row.getChildCount() < 2) return;
        View v = row.getChildAt(1);
        if (v instanceof TextView) ((TextView) v).setText(value == null ? "" : value);
    }
}
