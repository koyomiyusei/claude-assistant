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
        /** 調べ物のあとに出す「つぎに聞くこと」の候補 */
        void onSuggestions(java.util.List<String> questions);

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
    private volatile String lastUserText = "";
    private volatile boolean usedSearch;
    private volatile boolean forceSearch;   // 🔎ボタンで「必ず調べる」を指定されたとき
    private volatile String followUp;       // 走っている最中に足された補足
    private volatile java.util.List<String> voiceAlts;

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
        send(userText, null, null);
    }

    public void setForceSearch(boolean v) {
        forceSearch = v;
    }

    /** 音声認識の他候補。聞き間違いを文脈で直してもらうために渡す */
    public void setVoiceAlternatives(java.util.List<String> alts) {
        voiceAlts = (alts == null || alts.isEmpty()) ? null : new java.util.ArrayList<>(alts);
    }

    /** 処理中に送られた補足。いまの往復を中断し、補足を足してやり直す */
    public boolean addFollowUp(String text) {
        if (!running || text == null || text.trim().isEmpty()) return false;
        followUp = text.trim();
        Conversation conv = Conversation.get(host.context().getApplicationContext());
        conv.addDisplay("user", text.trim());
        cancel();
        return true;
    }

    /** 画像を添えて送る（base64・media typeは image/jpeg） */
    public void send(final String userText, final String imageB64, final String mediaType) {
        if (running) return;
        running = true;
        lastUserText = userText;
        usedSearch = false;
        final Context ctx = host.context().getApplicationContext();
        final Conversation conv = Conversation.get(ctx);
        try {
            String body = userText;
            if (voiceAlts != null) {
                StringBuilder sb = new StringBuilder(userText);
                sb.append("\n\n（音声入力です。聞き取りの他の候補: ");
                for (int i = 0; i < voiceAlts.size(); i++) {
                    if (i > 0) sb.append(" / ");
                    sb.append(voiceAlts.get(i));
                }
                sb.append("。文脈に合う方で解釈してよい。どれも変なら聞き返す）");
                body = sb.toString();
                voiceAlts = null;
            }
            if (imageB64 == null) {
                conv.messages.put(new JSONObject().put("role", "user").put("content", body));
            } else {
                JSONArray blocks = new JSONArray()
                        .put(new JSONObject().put("type", "image").put("source", new JSONObject()
                                .put("type", "base64").put("media_type", mediaType == null ? "image/jpeg" : mediaType)
                                .put("data", imageB64)))
                        .put(new JSONObject().put("type", "text")
                                .put("text", body.isEmpty() ? "この写真について教えて" : body));
                conv.messages.put(new JSONObject().put("role", "user").put("content", blocks));
            }
        } catch (Exception ignored) {
        }
        conv.addDisplay("user", (imageB64 == null ? "" : "🖼 ") + userText);
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
            boolean deep = usedSearch || looksLikeResearch(lastUserText);
            JSONObject body = buildRequest(ctx, conv, deep);
            status("考えています…");

            ClaudeClient.Listener listener = new ClaudeClient.Listener() {
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
            };
            ClaudeClient.Result r;
            try {
                r = client.send(key, Prefs.workspaceId(ctx), body, listener);
            } catch (ClaudeClient.ApiException e) {
                // tool_choice が使えない組み合わせのときは外してやり直す
                if (e.code == 400 && body.has("tool_choice") && String.valueOf(e.getMessage()).contains("tool_choice")) {
                    body.remove("tool_choice");
                    client = new ClaudeClient();
                    r = client.send(key, Prefs.workspaceId(ctx), body, listener);
                } else {
                    throw e;
                }
            }
            if (client.isCancelled()) {
                String add = followUp;
                followUp = null;
                if (add != null) {
                    // 直前の自分の発言に補足を足して、同じ往復をやり直す
                    appendToLastUser(conv, add);
                    lastUserText = lastUserText + "\n" + add;
                    shown.setLength(0);
                    status("補足を足して考え直しています…");
                    continue;
                }
                if (shown.length() == 0) conv.addDisplay("error", "中断しました");
                // 中断した往復は API 側の整合性が取れないので、ユーザー発言ごと消す
                dropUnfinishedTurn(conv);
                return;
            }

            if (r.webSearches > 0) usedSearch = true;
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
                // 「つぎに: A | B | C」の行を本文から外して、候補ボタンとして出す
                String body2 = shown.toString();
                java.util.List<String> qs = extractSuggestions(body2);
                if (!qs.isEmpty()) {
                    final String clean = stripSuggestions(body2).trim();
                    conv.updateLastDisplay("assistant", clean);
                    post(new Runnable() {
                        public void run() {
                            ui.onAssistantText(clean);
                            ui.onSuggestions(qs);
                        }
                    });
                }
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
                        .put("tool_use_id", b.optString("id"));
                if (o.imageB64 != null) {
                    tr.put("content", new JSONArray()
                            .put(new JSONObject().put("type", "text").put("text", o.result))
                            .put(new JSONObject().put("type", "image").put("source", new JSONObject()
                                    .put("type", "base64").put("media_type", "image/jpeg")
                                    .put("data", o.imageB64))));
                } else {
                    tr.put("content", o.result);
                }
                if (o.isError) tr.put("is_error", true);
                results.put(tr);
            }
            conv.messages.put(new JSONObject().put("role", "user").put("content", results));
            // ツールの後に続く本文は新しい吹き出しにする
            shown.setLength(0);
        }
        throw new ClaudeClient.ApiException(0, "やり取りが長くなりすぎたので止めました");
    }

    /** 今の言葉なら検索すべきか。人名・店・価格・制度など「外の事実」を聞かれたら調べる */
    static boolean needsSearch(String t) {
        if (t == null || t.isEmpty()) return false;
        if (looksLikeResearch(t)) return true;
        String[] keys = {"とは", "って何", "ってどんな", "誰", "いつ", "どこ", "何時", "営業", "定休",
                "値段", "料金", "金額", "在庫", "発売", "新型", "型番", "スペック", "仕様", "条件",
                "制度", "法律", "規則", "手続き", "必要書類", "資格", "会社", "社長", "代表", "株価",
                "ランキング", "人気", "評判", "口コミ", "今日の", "今週", "予報"};
        for (String k : keys) if (t.contains(k)) return true;
        // 「〜は？」「〜ですか？」のような、事実を尋ねる形
        return t.endsWith("？") || t.endsWith("?");
    }

    /** 調べ物っぽい頼みかどうか。深く考えるかの入口の判断 */
    static boolean looksLikeResearch(String t) {
        if (t == null) return false;
        String[] keys = {"調べ", "検索", "比較", "どっち", "どちら", "おすすめ", "なぜ", "理由", "相場", "価格",
                "いくら", "最新", "ニュース", "天気", "口コミ", "レビュー", "メリット", "デメリット", "選び方",
                "違い", "方法", "やり方", "評判", "どう思う", "考えて", "相談", "まとめて"};
        for (String k : keys) if (t.contains(k)) return true;
        return false;
    }

    static java.util.List<String> extractSuggestions(String body) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (body == null) return out;
        for (String line : body.split("\n")) {
            String s = line.trim();
            if (s.startsWith("つぎに:") || s.startsWith("つぎに：")) {
                for (String q : s.substring(4).split("[|｜]")) {
                    String t = q.trim().replaceAll("^[・\\-\\s]+", "");
                    if (t.length() > 1 && t.length() < 60) out.add(t);
                }
            }
        }
        return out;
    }

    static String stripSuggestions(String body) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            String s = line.trim();
            if (s.startsWith("つぎに:") || s.startsWith("つぎに：")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private JSONObject buildRequest(Context ctx, Conversation conv, boolean deep) throws Exception {
        String model = Prefs.model(ctx);
        boolean haiku = model.contains("haiku");
        String mode = Prefs.searchMode(ctx);
        boolean searchOn = Prefs.webSearch(ctx) && !"off".equals(mode);
        boolean must = searchOn && (forceSearch || "always".equals(mode) || needsSearch(lastUserText));
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("max_tokens", 8000)
                .put("system", Prefs.systemPrompt(ctx) + "\n\n" + nowLine() + contextLine() + memoryLine() + careLine() + calendarLine(ctx)
                        + recentActions(conv)
                        + Mem.forPrompt(ctx) + Chats.forPrompt(ctx) + Profiles.matched(ctx, lastUserText, deep)
                        + (searchOn ? searchPolicy(must) : "")
                        + Screen.forPrompt())
                .put("messages", conv.forApi(Prefs.historyTurns(ctx)));

        JSONArray tools = Tools.definitions(ctx);
        if (searchOn) {
            tools.put(new JSONObject()
                    .put("type", haiku ? "web_search_20250305" : "web_search_20260318")
                    .put("name", "web_search")
                    .put("max_uses", must ? 6 : 3)
                    .put("user_location", new JSONObject()
                            .put("type", "approximate")
                            .put("city", "Okayama")
                            .put("region", "Okayama")
                            .put("country", "JP")
                            .put("timezone", "Asia/Tokyo")));
        }
        body.put("tools", tools);
        if (must && !usedSearch) {
            // 記憶だけで即答して間違えるのを防ぐ。最初の一手を検索に固定する
            try {
                body.put("tool_choice", new JSONObject().put("type", "tool").put("name", "web_search"));
            } catch (Exception ignored) {
            }
        }
        if (!haiku) body.put("output_config", new JSONObject()
                .put("effort", deep ? Prefs.searchEffort(ctx) : Prefs.effort(ctx)));
        return body;
    }

    /** 検索するかどうかの構え。速さと正確さの折り合い */
    private static String searchPolicy(boolean must) {
        if (must) {
            return "\n\n# この質問への構え\n"
                    + "外の事実（人・店・会社・価格・制度・日付・最新情報）が絡む。記憶だけで答えない。必ず web_search で裏を取る。\n"
                    + "- 検索は2〜3回で切り上げる。完璧を待たず、分かった範囲で答える\n"
                    + "- 調べても確証が無い部分は「ここは確認できなかった」と書く。推測を事実のように書かない\n"
                    + "- 出典は本文にリンクで置く\n";
        }
        return "\n\n# この質問への構え\n"
                + "軽い質問なので、知っていることで答えてよい。ただし人名・店・価格・制度・日付など"
                + "外の事実で少しでも自信が無いものは、答える前に web_search で確かめる。"
                + "うろ覚えのまま言い切らない。確かめずに答えるときは、断定しない書き方にする。\n";
    }

    /** 直近にやった端末操作（カード）を渡す。「さっきの予定」を指せるようにするため */
    private static String recentActions(Conversation conv) {
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = conv.display.length() - 1; i >= 0 && n < 6; i--) {
                JSONObject o = conv.display.optJSONObject(i);
                if (o == null || !"card".equals(o.optString("kind"))) continue;
                sb.insert(0, "- " + o.optString("text") + "\n");
                n++;
            }
            if (n == 0) return "";
            return "\n\n# 直近にやったこと（新しいものが下）\n" + sb;
        } catch (Exception e) {
            return "";
        }
    }

    /** 文脈の扱い */
    private static String contextLine() {
        return "\n\n# 会話のつながり\n"
                + "- 直前までのやり取りを必ず踏まえる。ひとつ前の話題が続いていると考えるのが基本\n"
                + "- 「さっきの」「あれ」「それ」「やっぱり」「じゃなくて」は、直前に出てきたものを指す。"
                + "何のことか聞き返す前に、直近の自分の発言と「直近にやったこと」を見る\n"
                + "- 言い直し（例:「明日8時に焼肉」→「夜の8時にして」）は、新しい話ではなく前の指示の訂正として扱う。"
                + "作ったばかりの予定があるなら、それを直す（作り直しではなく変更）\n"
                + "- 本当に手がかりが無いときだけ、短く1つだけ聞き返す\n"
                + "- 送信のあとに「（補足）」が付いた文が足されることがある。これは同じ依頼への追加情報なので、まとめて1つの依頼として扱う\n";
    }

    /** 取りこぼしやすい操作の注意 */
    private static String careLine() {
        return "\n\n# 端末操作での注意\n"
                + "- アラームを2件以上頼まれたら set_alarms（複数まとめて）を使う。set_alarm を続けて呼ぶと2件目以降が入らない\n"
                + "- 実際に入ったかはアプリ側からは確認できない。まとめて設定したあとは一覧を開いて、本人に確認してもらう\n"
                + "- 「できました」と言い切らず、取りこぼしの可能性があるものは一言そえる\n";
    }

    /** 覚えるときの書き方 */
    private static String memoryLine() {
        return "\n\n# 覚えるときの決まり\n"
                + "- 「覚えといて」と言われたら remember を使う。その場の言い方のままではなく、"
                + "日付・場所・相手を具体に直した一文にする（例:「今日の夜、営業所に行く」→「9/25(木)の夜に営業所へ行く」）\n"
                + "- 「今の内容を覚えといて」と言われたら、直前までのやり取りを2〜4行に要約して remember する\n"
                + "- 予定・タスクとして残すべきものは、記憶ではなくカレンダーに入れるか聞く\n"
                + "- 頼まれていないことは覚えない\n";
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

    /** 走っている最中の補足を、直前のユーザー発言に足す */
    private static void appendToLastUser(Conversation conv, String add) {
        try {
            for (int i = conv.messages.length() - 1; i >= 0; i--) {
                JSONObject m = conv.messages.getJSONObject(i);
                if (!"user".equals(m.optString("role"))) continue;
                Object c = m.opt("content");
                if (c instanceof String) {
                    m.put("content", c + "\n（補足）" + add);
                    return;
                }
                if (c instanceof JSONArray) {
                    JSONArray a = (JSONArray) c;
                    boolean isToolResult = false;
                    for (int k = 0; k < a.length(); k++) {
                        if ("tool_result".equals(a.getJSONObject(k).optString("type"))) isToolResult = true;
                    }
                    if (isToolResult) continue;   // ツール結果の箱には足さない
                    a.put(new JSONObject().put("type", "text").put("text", "（補足）" + add));
                    return;
                }
            }
        } catch (Exception ignored) {
        }
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
