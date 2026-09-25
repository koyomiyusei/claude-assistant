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
    private TextView update;

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

        TextView hist = ui.iconButton(this, "🕘", ui.userBubble, ui.text, 40);
        hist.setContentDescription("会話の履歴");
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
        hp.rightMargin = ui.dp(8);
        head.addView(hist, hp);
        hist.setOnClickListener(v -> startActivity(new Intent(this, HistoryActivity.class)));

        TextView pic = ui.iconButton(this, "📎", ui.userBubble, ui.text, 40);
        pic.setContentDescription("写真を添える");
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
        pp.rightMargin = ui.dp(8);
        head.addView(pic, pp);
        pic.setOnClickListener(v -> pickImage());

        update = ui.iconButton(this, "⬇", ui.userBubble, ui.text, 40);
        update.setContentDescription("更新");
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(40));
        up.rightMargin = ui.dp(8);
        head.addView(update, up);

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
        update.setOnClickListener(v -> Updater.updateNow(this));

        if (!Prefs.hasApiKey(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("はじめに")
                    .setMessage("Claude の APIキーを設定してください。キーは端末の中だけに暗号化して保存されます。")
                    .setPositiveButton("設定を開く", (d, w) -> startActivity(new Intent(this, SettingsActivity.class)))
                    .show();
        } else {
            java.util.ArrayList<String> need = new java.util.ArrayList<>();
            if (!Speech.hasMicPermission(this)) need.add(Manifest.permission.RECORD_AUDIO);
            if (!Cal.canRead(this)) need.add(Manifest.permission.READ_CALENDAR);
            if (!Cal.canWrite(this)) need.add(Manifest.permission.WRITE_CALENDAR);
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
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
        Updater.autoCheck(this, this::showUpdateBadge);
        showUpdateBadge();
    }

    /** 新しい版が見つかっているときは、⬇ボタンをオレンジにしてバージョンを出す */
    private void showUpdateBadge() {
        if (update == null) return;
        String v = Updater.available;
        if (v == null) {
            update.setText("⬇");
            update.setBackground(ui.round(ui.userBubble, 20));
            update.setTextColor(ui.text);
            update.setPadding(0, 0, 0, 0);
        } else {
            update.setText("⬇ v" + v);
            update.setBackground(ui.round(ui.accent, 20));
            update.setTextColor(ui.onAccent);
            update.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        chat.release();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        chat.destroy();
    }

    /** アプリ内の音声入力が動かないときの逃げ道：端末標準の音声入力画面を出す */
    public void voiceFallback() {
        try {
            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                    .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "話してください");
            startActivityForResult(i, 12);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "キーボードのマイクをお使いください",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /** ギャラリー／カメラから写真を選ぶ */
    private void pickImage() {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"ギャラリーから選ぶ", "カメラで撮る"}, (d, w) -> {
                    try {
                        if (w == 0) {
                            Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                                    .addCategory(Intent.CATEGORY_OPENABLE);
                            startActivityForResult(Intent.createChooser(i, "写真を選ぶ"), 10);
                        } else {
                            startActivityForResult(new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), 11);
                        }
                    } catch (Exception e) {
                        android.widget.Toast.makeText(this, "開けませんでした", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == 12) {
            java.util.ArrayList<String> r = data.getStringArrayListExtra(
                    android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) chat.sendText(r.get(0));
            return;
        }
        try {
            android.graphics.Bitmap bmp = null;
            if (req == 10 && data.getData() != null) {
                java.io.InputStream in = getContentResolver().openInputStream(data.getData());
                bmp = android.graphics.BitmapFactory.decodeStream(in);
                if (in != null) in.close();
            } else if (req == 11 && data.getExtras() != null) {
                bmp = (android.graphics.Bitmap) data.getExtras().get("data");
            }
            if (bmp == null) return;
            int max = 1280;
            if (bmp.getWidth() > max || bmp.getHeight() > max) {
                float sc = Math.min(max / (float) bmp.getWidth(), max / (float) bmp.getHeight());
                bmp = android.graphics.Bitmap.createScaledBitmap(bmp,
                        Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
            String b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
            chat.attachImage(b64, "image/jpeg");
        } catch (Throwable e) {
            App.save(this, "pickImage", e);
            android.widget.Toast.makeText(this, "写真を読めませんでした", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------- Tools.Host ----------------

    public Context context() {
        return this;
    }

    public void launch(Intent i) {
        startActivity(i);
    }

    public void confirm(String message, java.util.function.Consumer<Boolean> answer) {
        chat.askConfirm(message, answer);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
    }

    View root() {
        return chat;
    }
}
