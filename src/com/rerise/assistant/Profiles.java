package com.rerise.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 分野ごとの指示。「飲食店を調べるときはこう」「ガジェットならこう」を本人が書き換えられるようにする。
 * 会話の言葉がキーワードに当たった分野だけを、そのときの会話に足す。
 */
public class Profiles {

    private static final String PREF = "assistant_profiles";

    /** name, keywords(カンマ区切り), text */
    private static final String[][] SEED = {
            {"調べ物の基本", "調べ,検索,比較,どっち,どちら,おすすめ,なぜ,理由,相場,価格,いくら,最新,ニュース,天気,口コミ,レビュー,メリット,デメリット,選び方,違い,評判",
                    "- 公式サイト・公的機関・メーカーなどの一次情報を優先する。アフィリエイト目的のまとめ記事は根拠にしない\n"
                            + "- 出典は本文のその場に [ドメイン](URL) の形で付ける\n"
                            + "- 「噂・未確定」と「確定情報」を分けて書く\n"
                            + "- 価格は日本円。海外のものは現地価格と概算円、日本で買えるかも書く\n"
                            + "- 良い面だけでなく、弱点・向いていない場合も必ず書く\n"
                            + "- 数字・日付・型番は記憶で言い切らず、調べた結果を使う\n"
                            + "- 最後の行に「つぎに: 質問1 | 質問2 | 質問3」を付ける（ボタンとして出る短い質問3つ）"},
            {"飲食店", "店,ランチ,ディナー,ご飯,ごはん,飯,グルメ,食べ,食事,カフェ,居酒屋,ラーメン,焼肉,寿司,定食,テイクアウト",
                    "- 5軒以上出す。チェーンばかりにせず、個人店・知られていない店も必ず混ぜる\n"
                            + "- 1軒ごとに：店名／ジャンル／場所（最寄りと車での目安）／予算／営業時間と定休日／推しの一品／ひとこと\n"
                            + "- 営業時間・定休日・休業は必ず調べた上で書く。古い情報なら「要確認」と書く\n"
                            + "- 公式サイト・Googleマップ・食べログの順でリンクを付ける\n"
                            + "- 店の写真や人気メニューの写真が公式・地図にあれば ![](画像URL) の形で貼る（アプリが画像として表示する）\n"
                            + "- 岡山市内が基本。範囲を広げるときは断ってから"},
            {"ガジェット・家電", "スマホ,イヤホン,ヘッドホン,PC,パソコン,タブレット,ウェアラブル,スマートウォッチ,ガジェット,家電,充電器,モニター,カメラ",
                    "- 型番・発売日・実売価格は必ず調べる。記憶で断定しない\n"
                            + "- 実売価格（Amazon・楽天・メーカー直販）と、型落ち品の相場も出す\n"
                            + "- 後継機の噂・発表予定があれば「待つべきか」を書く\n"
                            + "- 使っている端末（Galaxy S24／Pixel Watch 4／HP OmniBook・Windows）との相性を見る"},
            {"仕事（Re.RISE・配送）", "ドライバー,佐川,配送,コース,請求,報酬,監査,車両,リース,軽貨物,委託",
                    "- 連絡文は、そのまま送れる形で出す。あいさつは短く、要件を先に\n"
                            + "- ドライバー個人の報酬額・個人情報は書かない\n"
                            + "- 数字は税抜／税込を明示する"},
            {"端末操作", "アラーム,タイマー,電話,SMS,ライト,音量,マナー,アプリ,地図,経路,コピー",
                    "- 聞き返さずに実行する。日時が曖昧なら常識的に補い、補ったことを一言添える\n"
                            + "- 結果は1行で伝える。手順の説明はしない"},
    };

    public static JSONArray all(Context c) {
        try {
            String s = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("json", null);
            if (s != null) return new JSONArray(s);
        } catch (Exception ignored) {
        }
        JSONArray a = new JSONArray();
        try {
            for (String[] p : SEED) {
                a.put(new JSONObject().put("name", p[0]).put("keywords", p[1]).put("text", p[2]).put("on", true));
            }
        } catch (Exception ignored) {
        }
        return a;
    }

    public static void save(Context c, JSONArray a) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("json", a.toString()).apply();
    }

    public static void reset(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove("json").apply();
    }

    public static void update(Context c, int index, String name, String keywords, String text, boolean on) {
        try {
            JSONArray a = all(c);
            JSONObject o = index >= 0 && index < a.length() ? a.getJSONObject(index) : new JSONObject();
            o.put("name", name).put("keywords", keywords).put("text", text).put("on", on);
            if (index < 0 || index >= a.length()) a.put(o);
            save(c, a);
        } catch (Exception ignored) {
        }
    }

    public static void remove(Context c, int index) {
        JSONArray a = all(c);
        if (index >= 0 && index < a.length()) {
            a.remove(index);
            save(c, a);
        }
    }

    /** 会話の言葉に当たった分野の指示を返す。当たらなければ空 */
    public static String matched(Context c, String userText, boolean alwaysResearch) {
        JSONArray a = all(c);
        StringBuilder sb = new StringBuilder();
        String t = userText == null ? "" : userText;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !o.optBoolean("on", true)) continue;
            boolean hit = false;
            if (alwaysResearch && i == 0) hit = true;   // 調べ物の基本は、調べ物のときは必ず
            for (String k : o.optString("keywords").split(",")) {
                String key = k.trim();
                if (!key.isEmpty() && t.contains(key)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;
            sb.append("\n\n# ").append(o.optString("name")).append("のときの指示\n")
                    .append(o.optString("text")).append("\n");
        }
        return sb.toString();
    }
}
