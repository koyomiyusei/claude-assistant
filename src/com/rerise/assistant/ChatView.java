package com.rerise.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 会話の表示と入力欄。アプリ本体の画面とアシスタント重ね表示の両方で使う。
 */
public class ChatView extends LinearLayout implements Agent.Ui {

    private final Ui ui;
    private final Context ctx;
    private final ScrollView scroll;
    private final LinearLayout list;
    private final TextView status;
    private final EditText input;
    private final TextView mic, send;
    private final Agent agent;
    private final Speech speech;
    private final Talk talk;
    private final TextView speaker;
    private boolean continuous;          // 連続会話モード（読み上げ→自動でまた聞く）
    private String pendingImage;         // 添える画像（base64）
    private String pendingImageType;
    private TextView streaming;         // 今流れている assistant の吹き出し
    private boolean voiceStarted;       // 今回の入力が音声から始まったか（そのまま送信する）

    public ChatView(Context c, Tools.Host host, boolean compact) {
        super(c);
        ctx = c;
        ui = new Ui(c);
        agent = new Agent(host, this);
        speech = new Speech(c);
        talk = new Talk(c);
        setOrientation(VERTICAL);

        scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        list.setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8));
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        status = ui.label(c, "", 13, ui.sub);
        status.setPadding(ui.dp(18), ui.dp(2), ui.dp(18), ui.dp(4));
        status.setVisibility(GONE);
        addView(status);

        // 入力行
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(10));

        input = new EditText(c);
        input.setHint("話しかける / 入力");
        input.setHintTextColor(ui.sub);
        input.setTextColor(ui.text);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setBackground(ui.roundStroke(ui.surface, ui.line, 22));
        input.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(compact ? 4 : 6);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        row.addView(input, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        speaker = ui.iconButton(c, "🔊", ui.userBubble, ui.text, 44);
        LayoutParams kp = new LayoutParams(ui.dp(44), ui.dp(44));
        kp.leftMargin = ui.dp(8);
        row.addView(speaker, kp);

        mic = ui.iconButton(c, "🎤", ui.userBubble, ui.text, 44);
        LayoutParams mp = new LayoutParams(ui.dp(44), ui.dp(44));
        mp.leftMargin = ui.dp(8);
        row.addView(mic, mp);

        send = ui.iconButton(c, "↑", ui.accent, ui.onAccent, 44);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        LayoutParams sp = new LayoutParams(ui.dp(44), ui.dp(44));
        sp.leftMargin = ui.dp(8);
        row.addView(send, sp);
        addView(row);

        send.setOnClickListener(v -> {
            if (agent.isRunning()) {
                agent.cancel();
            } else {
                submit();
            }
        });
        mic.setOnClickListener(v -> toggleVoice());
        speaker.setOnClickListener(v -> {
            continuous = !continuous;
            speaker.setBackground(ui.round(continuous ? ui.accent : ui.userBubble, 22));
            speaker.setTextColor(continuous ? ui.onAccent : ui.text);
            if (!continuous) talk.stop();
            Toast.makeText(ctx, continuous ? "連続会話モード：答えを読み上げて、そのまま次を聞きます"
                    : "連続会話モードを切りました", Toast.LENGTH_SHORT).show();
        });
        input.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });

        reload();
    }

    /** 保存されている会話を描き直す */
    public void reload() {
        list.removeAllViews();
        streaming = null;
        JSONArray d = Conversation.get(ctx).display;
        if (d.length() == 0) {
            TextView hello = ui.label(ctx, "何をしましょう？\n\n例：「明日6時に起こして」「3分計って」\n「岡山駅の近くで今やってるランチ調べて」", 15, ui.sub);
            hello.setPadding(ui.dp(6), ui.dp(24), ui.dp(6), ui.dp(24));
            list.addView(hello);
        }
        for (int i = 0; i < d.length(); i++) {
            JSONObject o = d.optJSONObject(i);
            if (o == null) continue;
            addBubble(o.optString("kind"), o.optString("text"));
        }
        scrollToEnd();
    }

    /** 画面の文脈に合わせた入口ボタン（サイドキーで開いたとき） */
    public void showScreenShortcuts() {
        if (!Screen.has() && !Screen.hasShot()) return;
        String app = Screen.label();
        java.util.List<String> qs = new java.util.ArrayList<>();
        String low = app == null ? "" : app.toLowerCase(java.util.Locale.JAPAN);
        boolean msg = low.contains("line") || low.contains("メッセージ") || low.contains("gmail")
                || low.contains("メール") || low.contains("chat") || low.contains("slack")
                || low.contains("messages") || low.contains("sms");
        boolean web = low.contains("chrome") || low.contains("browser") || low.contains("ブラウザ")
                || !Screen.url().isEmpty();
        if (msg) {
            qs.add("この内容に返信文を作って");
            qs.add("要点だけ教えて");
            qs.add("丁寧な断り文にして");
        } else if (web) {
            qs.add("このページを要約して");
            qs.add("怪しい点・注意点はある？");
            qs.add("値段と条件だけ抜き出して");
        } else {
            qs.add("この画面は何？どうすればいい？");
            qs.add("要点だけ教えて");
            qs.add("この内容をメモに残して");
        }
        TextView head = ui.label(ctx, "画面: " + app + (Screen.url().isEmpty() ? "" : " / " + Screen.url()),
                12, ui.sub);
        head.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), 0);
        list.addView(head);
        onSuggestions(qs);
    }

    public void focusInput() {
        input.requestFocus();
    }

    public void startVoice() {
        if (!speech.isListening()) toggleVoice();
    }

    public void release() {
        speech.stop();
        talk.stop();
    }

    public void destroy() {
        speech.stop();
        talk.release();
    }

    /** 写真を添える（次の送信で一緒に送る） */
    public void attachImage(String base64, String mediaType) {
        pendingImage = base64;
        pendingImageType = mediaType;
        input.setHint("この写真について聞く…");
        addBubble("card", "🖼 写真を添えました。何を知りたいか書いて送ってください");
        scrollToEnd();
    }

    public boolean isBusy() {
        return agent.isRunning();
    }

    /** 端末標準の音声入力から返ってきた文字を入力欄へ */
    public void setInput(String t) {
        input.setText(t);
        input.setSelection(input.getText().length());
    }

    public void sendText(String t) {
        input.setText(t);
        submit();
    }

    private void submit() {
        String t = input.getText().toString().trim();
        if (t.isEmpty() || agent.isRunning()) return;
        speech.stop();
        input.setText("");
        if (Conversation.get(ctx).display.length() == 0) list.removeAllViews();
        addBubble("user", (pendingImage == null ? "" : "🖼 ") + t);
        streaming = null;
        send.setText("■");
        agent.send(t, pendingImage, pendingImageType);
        pendingImage = null;
        pendingImageType = null;
        input.setHint("話しかける / 入力");
        scrollToEnd();
    }

    // ---------------- 音声 ----------------

    private void toggleVoice() {
        if (speech.isListening()) {
            speech.finish();
            return;
        }
        if (!Speech.hasMicPermission(ctx) && ctx instanceof android.app.Activity) {
            ((android.app.Activity) ctx).requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        voiceStarted = true;
        mic.setBackground(ui.round(ui.accent, 22));
        mic.setTextColor(ui.onAccent);
        onStatus("聞いています…");
        speech.start(new Speech.Callback() {
            public void onPartial(String text) {
                input.setText(text);
                input.setSelection(input.getText().length());
            }

            public void onFinal(String text) {
                input.setText(text);
                if (voiceStarted) submit();
            }

            public void onError(String message) {
                Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                onStatus(null);
                // アプリ本体なら、端末標準の音声入力画面に逃がす
                if (ctx instanceof MainActivity && message.contains("開始できませんでした")) {
                    ((MainActivity) ctx).voiceFallback();
                }
            }

            public void onLevel(float rmsDb) {
                float s = 1f + Math.max(0, Math.min(rmsDb, 10)) / 40f;
                mic.setScaleX(s);
                mic.setScaleY(s);
            }

            public void onEnd() {
                voiceStarted = false;
                mic.setScaleX(1);
                mic.setScaleY(1);
                mic.setBackground(ui.round(ui.userBubble, 22));
                mic.setTextColor(ui.text);
                if (!agent.isRunning()) onStatus(null);
            }
        });
    }

    // ---------------- Agent.Ui ----------------

    public void onAssistantText(String fullText) {
        if (streaming == null) streaming = addBubble("assistant", fullText);
        else streaming.setText(Md.render(fullText, ui.codeBg));
        scrollToEnd();
    }

    public void onStatus(String s) {
        if (s == null || s.isEmpty()) {
            status.setVisibility(GONE);
        } else {
            status.setText(s);
            status.setVisibility(VISIBLE);
        }
    }

    public void onCard(String text) {
        addBubble("card", text);
        streaming = null;
        scrollToEnd();
    }

    public void onError(String message) {
        addBubble("error", message);
        streaming = null;
        scrollToEnd();
    }

    /** 調べ物のあとの「つぎに」候補。押すとそのまま質問になる */
    public void onSuggestions(java.util.List<String> questions) {
        if (questions == null || questions.isEmpty()) return;
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(VERTICAL);
        wrap.setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        TextView head = ui.label(ctx, "つぎに", 12, ui.sub);
        head.setPadding(ui.dp(4), 0, 0, ui.dp(4));
        wrap.addView(head);
        for (String q : questions) {
            TextView chip = ui.label(ctx, q, 14, ui.accent);
            chip.setBackground(ui.roundStroke(ui.surface, ui.accent, 16));
            chip.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
            chip.setClickable(true);
            LayoutParams cp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = ui.dp(6);
            chip.setOnClickListener(v -> {
                wrap.setVisibility(GONE);
                sendText(q);
            });
            wrap.addView(chip, cp);
        }
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(4);
        list.addView(wrap, lp);
        streaming = null;
        scrollToEnd();
    }

    public void onDone() {
        send.setText("↑");
        if (continuous && streaming != null) {
            String said = streaming.getText().toString();
            talk.speak(said, () -> {
                if (continuous && !agent.isRunning()) startVoice();
            });
        }
        streaming = null;
        status.setText(Usage.monthSummary(ctx));
        status.setVisibility(VISIBLE);
    }

    // ---------------- 確認（取り消せない操作の前） ----------------

    public void askConfirm(String message, java.util.function.Consumer<Boolean> answer) {
        final LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(VERTICAL);
        wrap.setBackground(ui.roundStroke(ui.surface, ui.accent, 14));
        wrap.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));

        TextView t = ui.label(ctx, message, 15, ui.text);
        wrap.addView(t);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setPadding(0, ui.dp(10), 0, 0);
        android.widget.Button yes = ui.pill(ctx, "実行する", true);
        android.widget.Button no = ui.pill(ctx, "やめる", false);
        row.addView(yes);
        View gap = new View(ctx);
        row.addView(gap, new LayoutParams(ui.dp(8), 1));
        row.addView(no);
        wrap.addView(row);

        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(6);
        lp.bottomMargin = ui.dp(6);
        list.addView(wrap, lp);
        streaming = null;
        scrollToEnd();

        final boolean[] done = {false};
        yes.setOnClickListener(v -> {
            if (done[0]) return;
            done[0] = true;
            row.setVisibility(GONE);
            t.setText(message + "\n→ 実行します");
            answer.accept(true);
        });
        no.setOnClickListener(v -> {
            if (done[0]) return;
            done[0] = true;
            row.setVisibility(GONE);
            t.setText(message + "\n→ やめました");
            answer.accept(false);
        });
    }

    // ---------------- 吹き出し ----------------

    private TextView addBubble(String kind, String text) {
        TextView t = new TextView(ctx);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setLineSpacing(0, 1.15f);
        t.setTextColor(ui.text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(6);
        lp.bottomMargin = ui.dp(6);
        switch (kind) {
            case "user":
                t.setText(text);
                t.setOnLongClickListener(v -> {
                    bubbleMenu(text);
                    return true;
                });
                t.setBackground(ui.round(ui.userBubble, 18));
                t.setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9));
                lp.gravity = Gravity.END;
                lp.leftMargin = ui.dp(48);
                break;
            case "card":
                t.setText(text);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                t.setBackground(ui.round(ui.cardBg, 12));
                t.setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7));
                break;
            case "error":
                t.setText(text);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                t.setBackground(ui.round(ui.errorBg, 12));
                t.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
                break;
            default: // assistant
                t.setText(Md.render(text, ui.codeBg));
                t.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2));
                t.setMovementMethod(LinkMovementMethod.getInstance());
                t.setLinkTextColor(ui.accent);
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                final TextView tv = t;
                t.setOnLongClickListener(v -> {
                    bubbleMenu(tv.getText().toString());
                    return true;
                });
        }
        list.addView(t, lp);
        return t;
    }

    /** 吹き出しの長押しメニュー */
    private void bubbleMenu(final String text) {
        new android.app.AlertDialog.Builder(ctx)
                .setItems(new String[]{"コピー", "これを覚えておく", "覚えて📌（必ず毎回読む）"}, (d, w) -> {
                    if (w == 0) {
                        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("assistant", text));
                        Toast.makeText(ctx, "コピーしました", Toast.LENGTH_SHORT).show();
                    } else {
                        String t = text.length() > 300 ? text.substring(0, 300) + "…" : text;
                        int id = Mem.add(ctx, t, w == 2);
                        addBubble("card", (w == 2 ? "📌 " : "🧠 ") + "覚えた: " + (t.length() > 40 ? t.substring(0, 40) + "…" : t));
                        Toast.makeText(ctx, "記憶に追加しました（#" + id + "）", Toast.LENGTH_SHORT).show();
                        scrollToEnd();
                    }
                }).show();
    }

    private void scrollToEnd() {
        // fullScroll はフォーカスを奪ってキーボードが閉じるので使わない
        scroll.post(() -> scroll.scrollTo(0, Math.max(0, list.getHeight() - scroll.getHeight())));
    }
}
