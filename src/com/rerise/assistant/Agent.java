package com.rerise.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * 1回の発言を処理するループ。
 * 送信 → tool_use が来たら端末で実行 → tool_result を付けて再送 → 終わるまで繰り返す。
 */
public class Agent {

    public interface Ui {
        void onAssistantText(String fullText);   // 今の吹き出しの全文（差分ではない）

        void onStatus(String status);           // null で消す

        void onCard(String text);               // ツール実行の記録

        void onError(String message);

        void onDone();
    }

    private static final int MAX_ROUNDS = 10;

    private final Tools.Host host;
    private final Ui ui;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ClaudeClient client;
    private volatile boolean running;

    public Agent(Tools.Host host, Ui ui) {
        this.host = host;
        this.ui = ui;
    }

    public boolean isRunning() {
        return running;
    }

    public void cancel() {
        ClaudeClient c = client;
        if (c != null) c.cancel();
    }

    public void send(final String userText) {
        if (running) return;
        running = true;
        final Context ctx = host.context().getApplicationContext();
        final Conversation conv = Conversation.get(ctx);
        try {
            conv.messages.put(new JSONObject().put("role", "user").put("content", userText));
        } catch (Exception ignored) {
        }
        conv.addDisplay("user", userText);
        conv.save(ctx);

        new Thread(new Runnable() {
            public void run() {
                try {
                    loop(ctx, conv);
                } catch (final Throwable e) {
                    App.save(ctx, "agent", e);
                    if (client == null || !client.isCancelled()) {
                        final String m = e instanceof ClaudeClient.ApiException ? e.getMessage()
                                : "エラー: " + e.getClass().getSimpleName() + " " + e.getMessage()
                                + "\n（詳しい内容は 設定 → 最後のエラー で見られます）";
                        conv.addDisplay("error", m);
                        post(new Runnable() {
                            public void run() {
                                ui.onError(m);
                            }
                        });
                    }
                } finally {
                    conv.compactFinishedTurns();
                    conv.save(ctx);
                    running = false;
                    client = null;
                    post(new Runnable() {
                        public void run() {
                            ui.onStatus(null);
                            ui.onDone();
                        }
                    });
                }
            }
        }).start();
    }

    private void loop(Context ctx, Conversation conv) throws Exception {
        String key = Prefs.getApiKey(ctx);
        if (key == null) throw new ClaudeClient.ApiException(0, "APIキーが未設定です。右上の⚙から設定してください。");

        final StringBuilder shown = new StringBuilder();   // この発言に対する表示中の本文
        boolean continuing = false;                        // pause_turn の続き

        for (int round = 0; round < MAX_ROUNDS; round++) {
            client = new ClaudeClient();
            JSONObject body = buildRequest(ctx, conv);
            status("考えています…");

            ClaudeClient.Result r = client.send(key, Prefs.workspaceId(ctx), body, new ClaudeClient.Listener() {
                public void onText(String delta) {
                    shown.append(delta);
                    final String t = shown.toString();
                    Conversation.get(ctx).updateLastDisplay("assistant", t);
                    post(new Runnable() {
                        public void run() {
                            ui.onAssistantText(t);
                        }
                    });
                }

                public void onStatus(String s) {
                    status(s);
                }
            });
            if (client.isCancelled()) {
                if (shown.length() == 0) conv.addDisplay("error", "中断しました");
                // 中断した往復は API 側の整合性が取れないので、ユーザー発言ごと消す
                dropUnfinishedTurn(conv);
                return;
            }

            Usage.add(ctx, conv, body.optString("model"), r);

            // 応答を履歴に積む（pause_turn の続きなら同じ assistant メッセージに連結）
            if (continuing && lastRole(conv).equals("assistant")) {
                JSONArray prev = conv.messages.getJSONObject(conv.messages.length() - 1).getJSONArray("content");
                for (int i = 0; i < r.content.length(); i++) prev.put(r.content.get(i));
            } else {
                conv.messages.put(new JSONObject().put("role", "assistant").put("content", r.content));
            }
            continuing = false;

            if ("pause_turn".equals(r.stopReason)) {
                continuing = true;
                continue;
            }
            if (!"tool_use".equals(r.stopReason)) {
                if ("max_tokens".equals(r.stopReason)) {
                    shown.append("\n\n（長すぎて途中で切れました）");
                    final String t = shown.toString();
                    conv.updateLastDisplay("assistant", t);
                    post(new Runnable() {
                        public void run() {
                            ui.onAssistantText(t);
                        }
                    });
                }
                return;
            }

            // 端末側のツールを実行
            JSONArray results = new JSONArray();
            for (int i = 0; i < r.content.length(); i++) {
                JSONObject b = r.content.getJSONObject(i);
                if (!"tool_use".equals(b.optString("type"))) continue;
                final String name = b.optString("name");
                final JSONObject input = b.optJSONObject("input") == null ? new JSONObject() : b.getJSONObject("input");
                status(Tools.statusLabel(name));
                // ツールはこのワーカースレッドで実行する（確認ダイアログの答えをここで待てるように）
                final Tools.Outcome o = Tools.run(host, name, input);
                if (o.card != null) {
                    conv.addDisplay("card", o.card);
                    post(new Runnable() {
                        public void run() {
                            ui.onCard(o.card);
                        }
                    });
                }
                JSONObject tr = new JSONObject().put("type", "tool_result")
                        .put("tool_use_id", b.optString("id"))
                        .put("content", o.result);
                if (o.isError) tr.put("is_error", true);
                results.put(tr);
            }
            conv.messages.put(new JSONObject().put("role", "user").put("content", results));
            // ツールの後に続く本文は新しい吹き出しにする
            shown.setLength(0);
        }
        throw new ClaudeClient.ApiException(0, "やり取りが長くなりすぎたので止めました");
    }

    private JSONObject buildRequest(Context ctx, Conversation conv) throws Exception {
        String model = Prefs.model(ctx);
        boolean haiku = model.contains("haiku");
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", 8000)
                .put("system", Prefs.systemPrompt(ctx) + "\n\n" + nowLine() + calendarLine(ctx))
                .put("messages", conv.forApi(Prefs.historyTurns(ctx)));

        JSONArray tools = Tools.definitions(ctx);
        if (Prefs.webSearch(ctx)) {
            tools.put(new JSONObject()
                    .put("type", haiku ? "web_search_20250305" : "web_search_20260318")
                    .put("name", "web_search")
                    .put("max_uses", 5)
                    .put("user_location", new JSONObject()
                            .put("type", "approximate")
                            .put("city", "Okayama")
                            .put("region", "Okayama")
                            .put("country", "JP")
                            .put("timezone", "Asia/Tokyo")));
        }
        body.put("tools", tools);
        if (!haiku) body.put("output_config", new JSONObject().put("effort", Prefs.effort(ctx)));
        return body;
    }

    private static String nowLine() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy年M月d日(E) H:mm", Locale.JAPAN);
        return "# 現在\n" + f.format(new Date()) + "（日本時間）";
    }

    /**
     * 右腕ボードと共通のカレンダー運用ルールと、予定を聞かれたときの答え方。
     * 設定のシステムプロンプトとは別に、毎回ここで足す（本人が設定を書き換えてもルールは崩れない）。
     */
    /** カレンダーの運用ルール（rules.md）と、端末に見えているカレンダーを毎回足す */
    private static String calendarLine(Context ctx) {
        if (!Cal.canRead(ctx)) return "";
        String names = Cal.calendarNames(ctx);
        if (names.isEmpty()) return "";
        Rules.refreshIfStale(ctx);
        // ルールの予定はカレンダー側にあるので、たまに同期を突いて新しい版を引き寄せる
        long last = Prefs.getLong(ctx, "rules_sync_at", 0);
        if (System.currentTimeMillis() - last > 10 * 60 * 1000) {
            Prefs.putLong(ctx, "rules_sync_at", System.currentTimeMillis());
            Cal.syncNow(ctx, null);
        }
        return "\n\n# 端末で読み書きできるカレンダー\n" + names + "\n\n" + Rules.text(ctx);
    }

    private static String lastRole(Conversation conv) {
        int n = conv.messages.length();
        return n == 0 ? "" : conv.messages.optJSONObject(n - 1).optString("role");
    }

    /** 最後のユーザー発言（ツール結果ではない方）以降を消す */
    private static void dropUnfinishedTurn(Conversation conv) {
        for (int i = conv.messages.length() - 1; i >= 0; i--) {
            JSONObject m = conv.messages.optJSONObject(i);
            Object c = m.opt("content");
            boolean start = "user".equals(m.optString("role")) && c instanceof String;
            conv.messages.remove(i);
            if (start) break;
        }
    }

    private void status(final String s) {
        post(new Runnable() {
            public void run() {
                ui.onStatus(s);
            }
        });
    }

    private void post(Runnable r) {
        main.post(r);
    }
}
