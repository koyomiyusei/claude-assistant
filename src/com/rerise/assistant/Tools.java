package com.rerise.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * 端末側で実行するツール（function calling）。
 * 定義(JSON Schema)と実行を1か所にまとめる。Web検索はサーバー側ツールなのでここには無い。
 */
public class Tools {

    /** ツールの実行を画面側に頼むための窓口（Activity と アシスタント重ね表示 の両方が実装する） */
    public interface Host {
        Context context();

        /** 他アプリの画面を開く。重ね表示中なら重ね表示を閉じてから開くなど、呼び出し元の事情に任せる */
        void launch(Intent i) throws Exception;
    }

    public static class Outcome {
        public final String result;   // Claude に返す文字列
        public final boolean isError;
        public final String card;     // 画面に出す実行記録（null なら出さない）

        Outcome(String result, boolean isError, String card) {
            this.result = result;
            this.isError = isError;
            this.card = card;
        }

        static Outcome ok(String result, String card) {
            return new Outcome(result, false, card);
        }

        static Outcome err(String result) {
            return new Outcome(result, true, "⚠ " + result);
        }
    }

    // ---------------- 定義 ----------------

    public static JSONArray definitions(Context c) throws Exception {
        JSONArray a = new JSONArray();

        a.put(tool("set_alarm",
                "端末の時計アプリにアラームを設定する。「明日6時に起こして」など。日付は指定できない（次に来るその時刻に鳴る）。",
                props(
                        prop("hour", "integer", "時（0-23）"),
                        prop("minute", "integer", "分（0-59）"),
                        prop("label", "string", "アラームの名前（任意）"),
                        new Object[]{"days", new JSONObject()
                                .put("type", "array")
                                .put("description", "繰り返す曜日（任意）。1=日,2=月,3=火,4=水,5=木,6=金,7=土")
                                .put("items", new JSONObject().put("type", "integer"))}
                ), "hour", "minute"));

        a.put(tool("set_timer",
                "端末の時計アプリでタイマーを開始する。「3分計って」など。",
                props(
                        prop("seconds", "integer", "秒数（1-86400）"),
                        prop("label", "string", "タイマーの名前（任意）")
                ), "seconds"));

        a.put(tool("copy_to_clipboard",
                "テキストをクリップボードにコピーする。下書きを他のアプリに貼りたいときなど。",
                props(prop("text", "string", "コピーする文字列")), "text"));

        return a;
    }

    public static String statusLabel(String name) {
        switch (name == null ? "" : name) {
            case "set_alarm":
                return "アラームを設定しています…";
            case "set_timer":
                return "タイマーを準備しています…";
            case "copy_to_clipboard":
                return "コピーしています…";
            default:
                return "端末を操作しています…";
        }
    }

    // ---------------- 実行 ----------------

    public static Outcome run(Host h, String name, JSONObject in) {
        try {
            switch (name) {
                case "set_alarm":
                    return setAlarm(h, in);
                case "set_timer":
                    return setTimer(h, in);
                case "copy_to_clipboard":
                    return copy(h, in);
                default:
                    return Outcome.err("未対応のツールです: " + name);
            }
        } catch (android.content.ActivityNotFoundException e) {
            return Outcome.err("対応するアプリが見つかりませんでした（" + name + "）");
        } catch (Exception e) {
            return Outcome.err(name + " に失敗しました: " + e.getMessage());
        }
    }

    private static Outcome setAlarm(Host h, JSONObject in) throws Exception {
        int hour = in.getInt("hour");
        int minute = in.optInt("minute", 0);
        String label = in.optString("label", "");
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        JSONArray days = in.optJSONArray("days");
        String rep = "";
        if (days != null && days.length() > 0) {
            ArrayList<Integer> d = new ArrayList<>();
            String[] names = {"", "日", "月", "火", "水", "木", "金", "土"};
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < days.length(); k++) {
                int v = days.getInt(k);
                if (v < 1 || v > 7) continue;
                d.add(v);
                sb.append(names[v]);
            }
            if (!d.isEmpty()) {
                i.putExtra(AlarmClock.EXTRA_DAYS, d);
                rep = "（毎週" + sb + "）";
            }
        }
        h.launch(i);
        String t = String.format("%d:%02d", hour, minute);
        return Outcome.ok("時計アプリに " + t + rep + " のアラーム設定を依頼しました。",
                "⏰ アラーム " + t + rep + (label.isEmpty() ? "" : "「" + label + "」"));
    }

    private static Outcome setTimer(Host h, JSONObject in) throws Exception {
        int sec = in.getInt("seconds");
        if (sec < 1 || sec > 86400) return Outcome.err("タイマーは1秒〜24時間で指定してください");
        String label = in.optString("label", "");
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, sec)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!label.isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, label);
        h.launch(i);
        String t = sec >= 60 ? (sec / 60) + "分" + (sec % 60 == 0 ? "" : (sec % 60) + "秒") : sec + "秒";
        return Outcome.ok("時計アプリで " + t + " のタイマーを開始しました。",
                "⏱ タイマー " + t + (label.isEmpty() ? "" : "「" + label + "」"));
    }

    private static Outcome copy(Host h, JSONObject in) throws Exception {
        String text = in.getString("text");
        ClipboardManager cm = (ClipboardManager) h.context().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("assistant", text));
        String head = text.length() > 30 ? text.substring(0, 30) + "…" : text;
        return Outcome.ok("コピーしました。", "📋 コピー: " + head.replace('\n', ' '));
    }

    // ---------------- JSON Schema 組み立ての小道具 ----------------

    private static JSONObject tool(String name, String desc, JSONObject properties, String... required) throws Exception {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties);
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        schema.put("required", req);
        return new JSONObject().put("name", name).put("description", desc).put("input_schema", schema);
    }

    private static Object[] prop(String name, String type, String desc) throws Exception {
        return new Object[]{name, new JSONObject().put("type", type).put("description", desc)};
    }

    private static JSONObject props(Object[]... ps) throws Exception {
        JSONObject o = new JSONObject();
        for (Object[] p : ps) o.put((String) p[0], p[1]);
        return o;
    }
}
