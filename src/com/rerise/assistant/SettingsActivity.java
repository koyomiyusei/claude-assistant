package com.rerise.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class SettingsActivity extends Activity {

    private Ui ui;
    private LinearLayout box;
    private TextView keyState, assistState, micState;

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
                android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
                v.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
            }
            return WindowInsets.CONSUMED;
        });

        TextView title = ui.label(this, "設定", 22, ui.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        // ---- APIキー ----
        section("Claude APIキー");
        keyState = note("");
        final EditText key = field("sk-ant-… を貼り付け", false);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout keyRow = row();
        Button saveKey = ui.pill(this, "保存", true);
        Button test = ui.pill(this, "接続テスト", false);
        Button delKey = ui.pill(this, "削除", false);
        keyRow.addView(saveKey);
        addGap(keyRow);
        keyRow.addView(test);
        addGap(keyRow);
        keyRow.addView(delKey);
        box.addView(keyRow);
        TextView getKey = note("キーの発行・残高の確認：console.anthropic.com（タップで開く）");
        getKey.setTextColor(ui.accent);
        getKey.setOnClickListener(v -> open("https://console.anthropic.com/settings/keys"));
        saveKey.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            if (!k.startsWith("sk-ant-")) {
                Toast.makeText(this, "sk-ant- で始まるキーを入れてください", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Prefs.setApiKey(this, k)) {
                key.setText("");
                Toast.makeText(this, "暗号化して保存しました", Toast.LENGTH_SHORT).show();
                refresh();
                if (!Speech.hasMicPermission(this)) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
                }
            } else {
                Toast.makeText(this, "保存に失敗しました", Toast.LENGTH_LONG).show();
            }
        });
        delKey.setOnClickListener(v -> {
            Prefs.setApiKey(this, null);
            refresh();
        });
        test.setOnClickListener(v -> testConnection());

        note("ワークスペースID（任意）。キーを「組織」スコープで作った場合だけ必要。"
                + "ワークスペーススコープのキーなら空のままでよい");
        final EditText ws = field("wrkspc_… （空でOK）", false);
        ws.setText(Prefs.workspaceId(this));
        ws.setOnFocusChangeListener((v, f) -> {
            if (!f) Prefs.setWorkspaceId(this, ws.getText().toString());
        });

        // ---- アシスタント ----
        section("デジタルアシスタントとして使う");
        assistState = note("");
        note("① 下のボタン →「デジタルアシスタントアプリ」（または「アシストと音声入力」）でこのアプリを選ぶ\n"
                + "② Galaxy の場合：設定 → 便利な機能 → サイドボタン → 長押し を「デジタルアシスタント」に");
        LinearLayout ar = row();
        Button openDefault = ui.pill(this, "既定のアプリ設定を開く", true);
        Button openSide = ui.pill(this, "サイドボタン設定", false);
        ar.addView(openDefault);
        addGap(ar);
        ar.addView(openSide);
        box.addView(ar);
        openDefault.setOnClickListener(v -> openDefaultAppsSettings());
        openSide.setOnClickListener(v -> openSideKeySettings());
        micState = note("");
        LinearLayout pr = row();
        Button mic = ui.pill(this, "マイクを許可", false);
        Button calp = ui.pill(this, "カレンダーを許可", false);
        pr.addView(mic);
        addGap(pr);
        pr.addView(calp);
        box.addView(pr);
        mic.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        calp.setOnClickListener(v -> requestPermissions(new String[]{
                Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, 1));

        LinearLayout pr2 = row();
        Button ctp = ui.pill(this, "連絡先・SMS", false);
        Button locp = ui.pill(this, "位置情報", false);
        pr2.addView(ctp);
        addGap(pr2);
        pr2.addView(locp);
        box.addView(pr2);
        ctp.setOnClickListener(v -> requestPermissions(new String[]{
                Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS}, 1));
        locp.setOnClickListener(v -> requestPermissions(new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, 1));

        final Switch autoVoice = toggle("呼び出したらすぐ音声入力を始める", Prefs.autoVoice(this));
        autoVoice.setOnCheckedChangeListener((v, on) -> Prefs.setAutoVoice(this, on));

        // ---- モデル ----
        section("モデル");
        RadioGroup models = radios(Prefs.MODELS, Prefs.model(this));
        models.setOnCheckedChangeListener((g, id) -> Prefs.setModel(this, Prefs.MODELS[id - 1][0]));

        section("考える深さ（端末操作・ふだんの会話）");
        note("アラーム・予定登録・ちょっとした返事はここ。速さ優先が向いています");
        RadioGroup efforts = radios(Prefs.EFFORTS, Prefs.effort(this));
        efforts.setOnCheckedChangeListener((g, id) -> Prefs.setEffort(this, Prefs.EFFORTS[id - 1][0]));

        section("考える深さ（調べ物）");
        note("「調べて」「比較して」「なぜ」などの相談と、Web検索を使ったときはこちらに切り替わります");
        RadioGroup efforts2 = radios(Prefs.EFFORTS, Prefs.searchEffort(this));
        efforts2.setOnCheckedChangeListener((g, id) -> Prefs.setSearchEffort(this, Prefs.EFFORTS[id - 1][0]));

        section("機能");
        final Switch web = toggle("Web検索を使う（1,000回あたり約1,500円）", Prefs.webSearch(this));
        web.setOnCheckedChangeListener((v, on) -> Prefs.setWebSearch(this, on));

        note("APIに送る直近の往復数（多いほど前の話を覚えているが、料金も増える）");
        final EditText turns = field("20", false);
        turns.setInputType(InputType.TYPE_CLASS_NUMBER);
        turns.setText(String.valueOf(Prefs.historyTurns(this)));
        turns.setOnFocusChangeListener((v, f) -> {
            if (f) return;
            try {
                int n = Integer.parseInt(turns.getText().toString().trim());
                Prefs.setHistoryTurns(this, Math.max(1, Math.min(100, n)));
            } catch (Exception ignored) {
            }
        });

        // ---- システムプロンプト ----
        section("前提・話し方（システムプロンプト）");
        note("仕事の前提（取引先・ドライバー・よく使う言い回し）をここに書いておくと、毎回それを踏まえて答えます");
        final EditText sys = field("", true);
        sys.setText(Prefs.systemPrompt(this));
        LinearLayout sr = row();
        Button saveSys = ui.pill(this, "保存", true);
        Button resetSys = ui.pill(this, "初期値に戻す", false);
        sr.addView(saveSys);
        addGap(sr);
        sr.addView(resetSys);
        box.addView(sr);
        saveSys.setOnClickListener(v -> {
            Prefs.setSystemPrompt(this, sys.getText().toString());
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show();
        });
        resetSys.setOnClickListener(v -> sys.setText(Prefs.DEFAULT_SYSTEM));

        section("カレンダーの運用ルール");
        note(Rules.state(this) + "\n右腕ボードと同じ決まりを rules.md から読み込んで、毎回の会話に足しています。"
                + "ボード側の決まりが変わったら rules.md を直すだけで反映されます（アプリの更新は不要）。");
        Button ruleNow = ui.pill(this, "ルールを取り直す", false);
        box.addView(ruleNow);
        ruleNow.setOnClickListener(v -> {
            getSharedPreferences("assistant_rules", MODE_PRIVATE).edit().putLong("at", 0).apply();
            Cal.syncNow(this, null);
            Rules.refreshIfStale(this);
            Toast.makeText(this, "取り直しています…", Toast.LENGTH_SHORT).show();
        });

        // ---- できること ----
        section("できること（いま使えるツール）");
        note(Tools.summary(this));

        // ---- 記憶 ----
        section("覚えていること");
        note("「覚えといて」と言ったこと、吹き出しの長押しで足したものがここに入ります。"
                + "📌 は必ず毎回読み込みます。現在 " + Mem.count(this) + " 件。");
        Button memList = ui.pill(this, "記憶を見る・消す", false);
        box.addView(memList);
        memList.setOnClickListener(v -> showMemories());

        // ---- 最後のエラー ----
        String crash = App.last(this);
        if (crash != null) {
            section("最後のエラー");
            note("アプリが落ちたときの記録です。コピーしてClaudeに貼れば原因が分かります。");
            final TextView ct = note(crash.length() > 3000 ? crash.substring(0, 3000) : crash);
            ct.setTextIsSelectable(true);
            LinearLayout cr = row();
            Button copy = ui.pill(this, "コピー", true);
            Button clr = ui.pill(this, "消す", false);
            cr.addView(copy);
            addGap(cr);
            cr.addView(clr);
            box.addView(cr);
            final String full = crash;
            copy.setOnClickListener(v -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", full));
                Toast.makeText(this, "コピーしました", Toast.LENGTH_SHORT).show();
            });
            clr.setOnClickListener(v -> {
                App.clear(this);
                ct.setText("（消しました）");
            });
        }

        // ---- その他 ----
        section("このアプリ");
        note("v" + Updater.currentName(this) + "　／　" + Usage.monthSummary(this) + "（1ドル150円換算）");
        LinearLayout ur = row();
        Button upd = ui.pill(this, "今すぐ更新", false);
        Button clear = ui.pill(this, "会話をリセット", false);
        ur.addView(upd);
        addGap(ur);
        ur.addView(clear);
        box.addView(ur);
        upd.setOnClickListener(v -> Updater.updateNow(this));
        clear.setOnClickListener(v -> {
            Conversation.get(this).reset(this);
            Toast.makeText(this, "新しい会話にしました", Toast.LENGTH_SHORT).show();
        });

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        refresh();
    }

    private void refresh() {
        if (keyState == null) return;
        keyState.setText("保存中のキー：" + Prefs.maskedKey(this));
        boolean held = false;
        try {
            RoleManager rm = getSystemService(RoleManager.class);
            held = rm != null && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT);
        } catch (Exception ignored) {
        }
        assistState.setText(held ? "✅ デフォルトのアシスタントに設定されています" : "⚪ まだデフォルトのアシスタントではありません");
        String m = Speech.hasMicPermission(this) ? "✅ マイク許可済み" : "⚪ マイク未許可（音声入力に必要）";
        m += Cal.canWrite(this) ? "　／　✅ カレンダー許可済み"
                : (Cal.canRead(this) ? "　／　△ カレンダーは読み取りのみ" : "　／　⚪ カレンダー未許可（予定の確認・登録に必要）");
        if (Cal.canRead(this)) {
            String names = Cal.calendarNames(this);
            if (!names.isEmpty()) m += "\n見えているカレンダー：" + names;
        }
        micState.setText(m);
    }

    /** 記憶の一覧。タップでピンの切り替え・削除 */
    private void showMemories() {
        org.json.JSONArray a = Mem.all(this);
        if (a.length() == 0) {
            Toast.makeText(this, "まだ何も覚えていません", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] items = new String[a.length()];
        final int[] ids = new int[a.length()];
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.optJSONObject(i);
            ids[i] = o.optInt("id");
            items[i] = (o.optBoolean("pinned") ? "📌 " : "・") + o.optString("text");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("覚えていること")
                .setItems(items, (d, w) -> {
                    final int id = ids[w];
                    new android.app.AlertDialog.Builder(this)
                            .setItems(new String[]{"📌 を付ける／外す", "消す"}, (d2, w2) -> {
                                if (w2 == 0) {
                                    org.json.JSONObject o = Mem.all(this).optJSONObject(indexOf(ids, id));
                                    Mem.pin(this, id, o == null || !o.optBoolean("pinned"));
                                } else {
                                    Mem.remove(this, id);
                                }
                                showMemories();
                            }).show();
                })
                .setPositiveButton("閉じる", null).show();
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    private void testConnection() {
        final String k = Prefs.getApiKey(this);
        if (k == null) {
            Toast.makeText(this, "先にキーを保存してください", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "テスト中…", Toast.LENGTH_SHORT).show();
        final String model = Prefs.model(this);
        new Thread(() -> {
            String msg;
            try {
                JSONObject body = new JSONObject()
                        .put("model", model)
                        .put("max_tokens", 200)
                        .put("messages", new JSONArray().put(new JSONObject()
                                .put("role", "user").put("content", "「接続OK」とだけ返してください")));
                if (!model.contains("haiku")) body.put("output_config", new JSONObject().put("effort", "low"));
                ClaudeClient.Result r = new ClaudeClient().send(k, body, new ClaudeClient.Listener() {
                    public void onText(String d) {
                    }

                    public void onStatus(String s) {
                    }
                });
                String text = "";
                for (int i = 0; i < r.content.length(); i++) {
                    JSONObject bl = r.content.getJSONObject(i);
                    if ("text".equals(bl.optString("type"))) text += bl.optString("text");
                }
                msg = "✅ " + model + "\n" + text.trim();
            } catch (Exception e) {
                msg = "❌ " + e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                    .setTitle("接続テスト").setMessage(m).setPositiveButton("OK", null).show());
        }).start();
    }

    private void openDefaultAppsSettings() {
        String[] actions = {Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_VOICE_INPUT_SETTINGS,
                Settings.ACTION_SETTINGS};
        for (String a : actions) {
            try {
                startActivity(new Intent(a));
                return;
            } catch (Exception ignored) {
            }
        }
    }

    private void openSideKeySettings() {
        // Samsung のサイドボタン設定。機種・OSで変わるので、だめなら設定トップを開く
        Intent i = new Intent().setClassName("com.android.settings",
                "com.android.settings.Settings$FunctionKeySettingsActivity");
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "設定 → 便利な機能 → サイドボタン を開いてください", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    // ---------------- 部品 ----------------

    private void section(String s) {
        TextView t = ui.label(this, s, 15, ui.accent);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, ui.dp(22), 0, ui.dp(6));
        box.addView(t);
    }

    private TextView note(String s) {
        TextView t = ui.label(this, s, 13, ui.sub);
        t.setPadding(0, ui.dp(2), 0, ui.dp(6));
        box.addView(t);
        return t;
    }

    private EditText field(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(ui.sub);
        e.setTextColor(ui.text);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, multi ? 14 : 15);
        e.setBackground(ui.roundStroke(ui.surface, ui.line, 12));
        e.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setGravity(Gravity.TOP);
            e.setMinLines(8);
        } else {
            e.setSingleLine(true);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = ui.dp(8);
        box.addView(e, lp);
        return e;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, ui.dp(2), 0, ui.dp(6));
        return r;
    }

    private void addGap(LinearLayout r) {
        r.addView(new android.view.View(this), new LinearLayout.LayoutParams(ui.dp(8), 1));
    }

    private Switch toggle(String label, boolean on) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(ui.text);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setChecked(on);
        s.setPadding(0, ui.dp(8), 0, ui.dp(8));
        box.addView(s);
        return s;
    }

    private RadioGroup radios(String[][] items, String current) {
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < items.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(i + 1);
            rb.setText(items[i][1]);
            rb.setTextColor(ui.text);
            rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            g.addView(rb);
            if (items[i][0].equals(current)) rb.setChecked(true);
        }
        box.addView(g);
        return g;
    }
}
