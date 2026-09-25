package com.rerise.assistant;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * カレンダーの運用ルール。右腕ボードの作りに合わせてある。
 *
 * ルールはアプリに焼き込まず、GitHub の rules.md を読みに行って端末に貯める。
 * ボード側の決まりが変わったら rules.md を直すだけでよく、アプリを作り直さなくて済む。
 * 通信できないときや初回は、下の DEFAULT（ビルド時点の写し）を使う。
 */
public class Rules {

    public static final String URL_RULES =
            "https://raw.githubusercontent.com/koyomiyusei/claude-assistant/main/rules.md";

    private static final long REFRESH = 6L * 60 * 60 * 1000;
    private static volatile boolean fetching;

    public static final String DEFAULT =
            "# カレンダーの使い分け（右腕ボードと同じ決まり）\n"
            + "カレンダーは5本。種別はカレンダーで決まる。\n"
            + "- 仕事／プライベート = 予定。相手がいて動かせないもの（会議・面談・監査・通院・来客・待ち合わせ）。時間指定・予定あり。マーカーは付けない\n"
            + "- やること = タスク。終日または時間つきで、必ず「予定なし(FREE)」。末尾に【仕事】か【私用】、相手待ちは【待ち】、長期目標は【目標】、用事でないただの書き留めは【メモ】\n"
            + "- あとで = 急がないタスクの置き場。日付に意味はない\n"
            + "- 支払 = お金。【引落】【支払】【返済】【期限】は支払い予定（日付は事実なので動かさない・消さない）。【支出】【入金】は家計簿の記録\n"
            + "\n"
            + "# 予定とタスクの分かれ目\n"
            + "「相手がいて動かせないか」の一点だけで決める。時刻が付いていても、自分だけで完結するならタスク。\n"
            + "例：「15時に薬を飲む」→ タスク（時刻つき）。「15時から面談」→ 予定。\n"
            + "口座からお金が出ていく話（引落・返済・振込・納付・○○円払う）は支払。\n"
            + "\n"
            + "# 完了のしかた\n"
            + "完了はタイトルの先頭に「済 」を付ける。削除ではない（記録が消えると後から追えないため）。\n"
            + "右腕ボードは「済」を自動で隠すので、本人の画面からは消えたように見える。\n"
            + "本当に消すのはボードの「済」タブからの手動削除だけ。\n"
            + "\n"
            + "# 書き方\n"
            + "- タイトルは短い名詞句。10〜20文字。日時や「やる」などの語は落とす。人が絡むものは「用件 名前」の順\n"
            + "- 先頭に付くマーカーは「済」だけ。ほかのマーカーはすべて末尾\n"
            + "- 説明は「▶ 」で始まる動作の箇条書き → 1行空ける → 背景・詳細\n"
            + "- 時間帯の言葉は次の時刻にする：朝・午前中=07:00／昼=12:00／夕方=17:00／夜・今夜=19:00／夜遅く・深夜=21:00\n"
            + "- 「あとで」「いつか」「急がない」は あとで カレンダーへ\n"
            + "- 配送業の話（佐川・ドライバー・請求・監査・会議・報酬・車両）は【仕事】、私生活は【私用】\n"
            + "- 金額や固有名詞はタイトルに残す。勝手に足さない\n"
            + "\n"
            + "# 通知\n"
            + "終日のタスク=前日18時（360分前）／時間つき=30分前／支払い予定=6時間前\n"
            + "\n"
            + "# 予定を聞かれたときの答え方\n"
            + "スマホの画面で一目で分かる形にする。前置き・感想・締めの一文は書かない。\n"
            + "種類ごとに見出しを分け、1件1行。中身が無い見出しは丸ごと省く。\n"
            + "**予定**（時間のあるものだけ）→「H:mm タイトル」\n"
            + "**支払**（今日のお金）→「タイトル 金額」。今日が期限・期限切れは行末に ← 今日\n"
            + "**やること**（N件）→ 重要・古い順に最大5件。残りは「ほか◯件」とだけ\n"
            + "末尾のマーカーは読み上げない。全部を並べようとしない。\n"
            + "\n"
            + "# 勝手にやらないこと\n"
            + "- 調べ物の結果を、頼まれていないのに登録しない\n"
            + "- 聞かれてもいない整理・並べ替え・マーカーの付け直しをしない\n"
            + "- 支払い予定の日付・金額は変えない。消さない\n";

    /**
     * ルールの出どころは3段。上から順に使う。
     * 1. Googleカレンダーの【運用ルール】という予定の説明欄（右腕ボード側から書き換えられる・いちばん新しい）
     * 2. GitHub の rules.md（こちらで管理する写し）
     * 3. アプリに入っている初期ルール
     */
    public static String text(Context c) {
        String fromCal = Cal.rulesFromCalendar(c);
        if (fromCal != null) return fromCal + "\n（出どころ: カレンダーの【運用ルール】）";
        String saved = c.getSharedPreferences("assistant_rules", Context.MODE_PRIVATE)
                .getString("text", null);
        return saved == null ? DEFAULT : saved;
    }

    /** 古くなっていれば裏で取り直す。今回の返事には間に合わなくてよい */
    public static void refreshIfStale(final Context c) {
        final android.content.SharedPreferences p =
                c.getSharedPreferences("assistant_rules", Context.MODE_PRIVATE);
        if (fetching) return;
        if (System.currentTimeMillis() - p.getLong("at", 0) < REFRESH) return;
        fetching = true;
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(URL_RULES).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Cache-Control", "no-cache");
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    r.close();
                    String t = sb.toString().trim();
                    if (t.length() > 100) {
                        p.edit().putString("text", t).putLong("at", System.currentTimeMillis()).apply();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
                fetching = false;
            }
        }).start();
    }

    /** いつ取り込んだか（設定画面の表示用） */
    public static String state(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("assistant_rules", Context.MODE_PRIVATE);
        if (Cal.rulesFromCalendar(c) != null)
            return "カレンダーの【運用ルール】を使用中（右腕ボード側から書き換え可）";
        long at = p.getLong("at", 0);
        if (at == 0) return "アプリに入っている初期ルールを使用中";
        return "GitHub の rules.md を使用中（取得 " + Cal.fmt("M/d H:mm", at) + "）";
    }
}
