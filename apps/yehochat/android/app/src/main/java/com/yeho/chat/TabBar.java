package com.yeho.chat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 底部三 Tab 栏（聊天 / 我的 / 设置）。
 *
 * 方案选择：**手搓 LinearLayout**，不引入 material 组件库
 * （app/build.gradle 里只有 appcompat + okhttp，没有 material，
 *  BottomNavigationView 用不了；加依赖又有拉不到 Maven 的风险）。
 *
 * 切换方式：三个页面是三个独立 Activity，点 Tab 用
 * {@link Intent#FLAG_ACTIVITY_REORDER_TO_FRONT} 把已有实例提到前台，
 * 而不是重建 —— 这样 ChatActivity 不会被销毁，
 * ChatService.setObserver() 的绑定、MsgStore 内存缓存、ui Handler 全都原样保留，
 * WS 不会断、消息不会漏。详见报告。
 */
public final class TabBar {

    public static final int TAB_CHAT = 0;
    public static final int TAB_ME = 1;
    public static final int TAB_SETTINGS = 2;

    private static final String[] LABELS = {"聊天", "我的", "设置"};
    private static final int[] ICONS = {
            R.drawable.ic_tab_chat, R.drawable.ic_tab_me, R.drawable.ic_tab_settings
    };

    private TabBar() { }

    /** 把宿主布局里 id=tabbar 的占位容器填成三格 Tab 栏 */
    public static void attach(final Activity a, final int current) {
        View root = a.findViewById(R.id.tabbar);
        if (!(root instanceof LinearLayout)) return;

        LinearLayout bar = (LinearLayout) root;
        bar.removeAllViews();
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundResource(R.drawable.tabbar_bg);

        int selected = Ui.color(a, R.color.tab_selected);
        int normal = Ui.color(a, R.color.tab_normal);
        int iconSize = a.getResources().getDimensionPixelSize(R.dimen.tab_icon);

        for (int i = 0; i < LABELS.length; i++) {
            final int idx = i;
            boolean on = (i == current);
            int tint = on ? selected : normal;

            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            item.setBackgroundResource(R.drawable.row_item_bg);

            ImageView iv = new ImageView(a);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(iconSize, iconSize);
            iv.setLayoutParams(ilp);
            iv.setImageResource(ICONS[i]);
            iv.setColorFilter(tint, PorterDuff.Mode.SRC_IN);
            item.addView(iv);

            TextView tv = new TextView(a);
            tv.setText(LABELS[i]);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                    a.getResources().getDimension(R.dimen.tab_text));
            tv.setTextColor(tint);
            // 注意：垂直 LinearLayout 的 generateDefaultLayoutParams() 给的是 MATCH_PARENT 宽，
            // 不显式指定的话文字会贴格子左边（图标因为显式给了 params 才是居中的）。两重保险：
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setGravity(Gravity.CENTER);
            item.addView(tv);

            if (on) {
                // 当前页：不响应点击，避免把自己再推一遍
                item.setClickable(false);
            } else {
                item.setOnClickListener(v -> go(a, idx));
            }
            bar.addView(item);
        }
    }

    /** 切到第 idx 个 Tab（当前页不会调到这里） */
    private static void go(Activity a, int idx) {
        Class<?> target;
        if (idx == TAB_CHAT) target = ChatActivity.class;
        else if (idx == TAB_ME) target = ProfileActivity.class;
        else target = SettingsActivity.class;

        Intent i = new Intent(a, target);
        // 关键：复用已有实例，而不是重复创建 -> 聊天页不会被销毁重建
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            a.startActivity(i);
        } catch (Exception e) {
            Ui.toast(a, "打开失败");
        }
    }
}
