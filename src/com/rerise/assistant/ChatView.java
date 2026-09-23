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
    private TextView streaming;         // 今流れている assistant の吹き出し
    private boolean voiceStarted;       // 今回の入力が音声から始まったか（そのまま送信する）

    public ChatView(Context c, Tools.Host host, boolean compact) {
        super(c);
        ctx = c;
        ui = new Ui(c);
        agent = new Agent(host, this);
        speech = new Speech(c);
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

    public void focusInput() {
        input.requestFocus();
    }

    public void startVoice() {
        if (!speech.isListening()) toggleVoice();
    }

    public void release() {
        speech.stop();
    }

    public boolean isBusy() {
        return agent.isRunning();
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
        addBubble("user", t);
        streaming = null;
        send.setText("■");
        agent.send(t);
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
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
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

    public void onDone() {
        send.setText("↑");
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
                    ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("assistant", tv.getText().toString()));
                    Toast.makeText(ctx, "コピーしました", Toast.LENGTH_SHORT).show();
                    return true;
                });
        }
        list.addView(t, lp);
        return t;
    }

    private void scrollToEnd() {
        // fullScroll はフォーカスを奪ってキーボードが閉じるので使わない
        scroll.post(() -> scroll.scrollTo(0, Math.max(0, list.getHeight() - scroll.getHeight())));
    }
}
