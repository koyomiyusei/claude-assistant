package com.rerise.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * サイドキー長押し（デジタルアシスタント呼び出し）で、今の画面の上に重ねて出すパネル。
 * 中身はアプリ本体と同じ ChatView・同じ会話。
 */
public class AssistSession extends VoiceInteractionSession implements Tools.Host {

    private ChatView chat;
    private Ui ui;

    public AssistSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        Context c = getContext();
        ui = new Ui(c);

        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Color.argb(90, 0, 0, 0));
        root.setOnClickListener(v -> hide());   // 上の暗い部分をタップで閉じる

        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ui.bg);
        float r = ui.dp(24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setClickable(true);   // シート上のタップで閉じないように

        // つまみ＋見出し
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(18), ui.dp(12), ui.dp(10), ui.dp(2));
        TextView title = ui.label(c, "✳ Claude", 16, ui.accent);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView fresh = ui.iconButton(c, "＋", ui.userBubble, ui.text, 36);
        TextView full = ui.iconButton(c, "⤢", ui.userBubble, ui.text, 36);
        TextView close = ui.iconButton(c, "✕", ui.userBubble, ui.text, 36);
        for (TextView b : new TextView[]{fresh, full, close}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36));
            lp.leftMargin = ui.dp(8);
            head.addView(b, lp);
        }
        sheet.addView(head);

        chat = new ChatView(c, this, true);
        int h = (int) (c.getResources().getDisplayMetrics().heightPixels * 0.55f);
        sheet.addView(chat, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(sheet, sp);

        root.setOnApplyWindowInsetsListener((v, in) -> {
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                bottom = Math.max(in.getInsets(WindowInsets.Type.navigationBars()).bottom,
                        in.getInsets(WindowInsets.Type.ime()).bottom);
            } else {
                bottom = in.getSystemWindowInsetBottom();
            }
            sheet.setPadding(0, 0, 0, bottom);
            return WindowInsets.CONSUMED;
        });

        fresh.setOnClickListener(v -> {
            if (chat.isBusy()) return;
            Conversation.get(c).reset(c);
            chat.reload();
        });
        full.setOnClickListener(v -> {
            launchSelf(new Intent(c, MainActivity.class));
            hide();
        });
        close.setOnClickListener(v -> hide());
        return root;
    }

    @Override
    public void onHandleAssist(android.service.voice.VoiceInteractionSession.AssistState state) {
        try {
            if (state == null) return;
            Screen.capture(state.getAssistStructure());
            Screen.capture(state.getAssistContent(), getContext());
            Screen.resolveLabel(getContext());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onHandleScreenshot(android.graphics.Bitmap screenshot) {
        try {
            Screen.capture(screenshot);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        if (chat == null) return;
        if (!chat.isBusy()) chat.reload();
        if (!Prefs.hasApiKey(getContext())) {
            launchSelf(new Intent(getContext(), SettingsActivity.class));
            hide();
            return;
        }
        if (Conversation.get(getContext()).display.length() == 0 || !chat.isBusy()) {
            chat.showScreenShortcuts();
        }
        if (Prefs.autoVoice(getContext()) && !chat.isBusy()) {
            chat.post(() -> chat.startVoice());
        } else {
            chat.focusInput();
        }
    }

    @Override
    public void onHide() {
        super.onHide();
        if (chat != null) chat.release();
    }

    // ---------------- Tools.Host ----------------

    public Context context() {
        return getContext();
    }

    public void launch(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
    }

    public void confirm(String message, java.util.function.Consumer<Boolean> answer) {
        if (chat == null) {
            answer.accept(false);
            return;
        }
        chat.askConfirm(message, answer);
    }

    private void launchSelf(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
        } catch (Exception e) {
            try {
                startAssistantActivity(i);
            } catch (Exception ignored) {
            }
        }
    }
}
