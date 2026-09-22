package com.rerise.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity implements Tools.Host {

    public static final String EXTRA_VOICE = "voice";

    private ChatView chat;
    private Ui ui;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        getWindow().setStatusBarColor(ui.bg);
        getWindow().setNavigationBarColor(ui.bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        // 見出し
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(18), ui.dp(10), ui.dp(10), ui.dp(6));
        TextView title = ui.label(this, "アシスタント", 20, ui.text);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView fresh = ui.iconButton(this, "＋", ui.userBubble, ui.text, 40);
        fresh.setContentDescription("新しい会話");
        head.addView(fresh, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        TextView gear = ui.iconButton(this, "⚙", ui.userBubble, ui.text, 40);
        gear.setContentDescription("設定");
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
        gp.leftMargin = ui.dp(8);
        head.addView(gear, gp);
        root.addView(head);

        chat = new ChatView(this, this, false);
        root.addView(chat, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        // Android 15 以降は画面の端まで描画されるので、ステータスバー・ナビバー・キーボードの分を空ける
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars());
                android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                top = in.getSystemWindowInsetTop();
                bottom = in.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return WindowInsets.CONSUMED;
        });

        fresh.setOnClickListener(v -> {
            if (chat.isBusy()) return;
            Conversation.get(this).reset(this);
            chat.reload();
        });
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        if (!Prefs.hasApiKey(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("はじめに")
                    .setMessage("Claude の APIキーを設定してください。キーは端末の中だけに暗号化して保存されます。")
                    .setPositiveButton("設定を開く", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .show();
        } else if (!Speech.hasMicPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.getBooleanExtra(EXTRA_VOICE, false)) {
            i.removeExtra(EXTRA_VOICE);
            chat.post(() -> chat.startVoice());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!chat.isBusy()) chat.reload();   // 重ね表示側で話した内容も反映
        Updater.autoCheck(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        chat.release();
    }

    // ---------------- Tools.Host ----------------

    public Context context() {
        return this;
    }

    public void launch(Intent i) {
        startActivity(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
    }

    View root() {
        return chat;
    }
}
