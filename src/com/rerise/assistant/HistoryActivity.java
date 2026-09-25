package com.rerise.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 過去の会話の一覧。読む・ピン留め・消す */
public class HistoryActivity extends Activity {

    private Ui ui;
    private LinearLayout box;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ui = new Ui(this);
        getWindow().setStatusBarColor(ui.bg);
        getWindow().setNavigationBarColor(ui.bg);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(ui.bg);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(40));
        sv.addView(box);
        setContentView(sv);
        sv.setOnApplyWindowInsetsListener((v, in) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, bars.top, 0, bars.bottom);
            }
            return WindowInsets.CONSUMED;
        });
        draw();
    }

    private void draw() {
        box.removeAllViews();
        TextView title = ui.label(this, "会話の履歴", 22, ui.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);
        TextView note = ui.label(this,
                "＋を押したときに、それまでの会話がここに入ります。📌を付けた会話は消えず、毎回の会話でも踏まえます。"
                        + "ピンなしは新しい20本まで残ります。", 13, ui.sub);
        note.setPadding(0, ui.dp(6), 0, ui.dp(12));
        box.addView(note);

        JSONArray a = Chats.index(this);
        if (a.length() == 0) {
            box.addView(ui.label(this, "まだ履歴はありません。", 15, ui.sub));
            return;
        }
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            final String file = o.optString("file");
            final boolean pinned = o.optBoolean("pinned");
            TextView t = ui.label(this,
                    (pinned ? "📌 " : "") + o.optString("title") + "\n"
                            + Cal.fmt("M/d(E) H:mm", o.optLong("at")) + "　" + o.optInt("turns") + "件",
                    15, ui.text);
            t.setBackground(ui.roundStroke(ui.surface, pinned ? ui.accent : ui.line, 12));
            t.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = ui.dp(8);
            t.setOnClickListener(v -> show(file));
            t.setOnLongClickListener(v -> {
                menu(file, pinned);
                return true;
            });
            box.addView(t, lp);
        }
    }

    private void show(String file) {
        String body = Chats.transcript(this, file, 20000);
        TextView t = ui.label(this, body.isEmpty() ? "（読めませんでした）" : body, 14, ui.text);
        t.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        t.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this).setView(sv).setPositiveButton("閉じる", null).show();
    }

    private void menu(String file, boolean pinned) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{pinned ? "📌 を外す" : "📌 ピン留めする（毎回踏まえる）", "消す"}, (d, w) -> {
                    if (w == 0) {
                        Chats.pin(this, file, !pinned);
                        Toast.makeText(this, pinned ? "ピンを外しました" : "ピン留めしました", Toast.LENGTH_SHORT).show();
                    } else {
                        Chats.delete(this, file);
                    }
                    draw();
                }).show();
    }
}
