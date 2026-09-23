package com.rerise.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

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

        /** 取り消せない操作の前に「やっていいか」を画面で聞く（UIスレッドで呼ばれる） */
        void confirm(String message, java.util.function.Consumer<Boolean> answer);
    }

    /** 確認を出して答えを待つ（ツール実行はワーカースレッドなので、ここで待てる） */
    private static boolean ask(Host h, String message) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                h.confirm(message, a -> {
                    ok[0] = a != null && a;
                    latch.countDown();
                }));
        try {
            latch.await();
        } catch (InterruptedException e) {
            return false;
        }
        return ok[0];
    }

    private static void onMain(Runnable r) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException ignored) {
        }
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

        if (Cal.canRead(c)) {
            a.put(tool("list_events",
                    "カレンダーの予定・タスクを読む。「今日の予定は？」「今週の支払いは？」「残ってるやることは？」など。"
                            + "返ってくる event_id は、完了・変更・削除のときに使う。",
                    props(
                            prop("start_date", "string", "開始日 YYYY-MM-DD（省略時は今日）"),
                            prop("days", "integer", "何日分か（省略時は1。今週なら7）"),
                            prop("calendar", "string", "特定のカレンダーだけに絞るとき（仕事／プライベート／支払／やること）"),
                            new Object[]{"include_done", new JSONObject().put("type", "boolean")
                                    .put("description", "「済」が付いた完了分も含めるか（省略時は含めない）")}
                    )));

            a.put(tool("create_event",
                    "カレンダーに予定またはタスクを登録する。ルール："
                            + "kind=予定 は時間の決まった用事（会議・アポ・予約）で、カレンダーは 仕事 か プライベート。"
                            + "kind=期限 は〆切のあるもの、kind=やる は期限のないやりたいこと。この2つは終日・予定なしで「やること」カレンダーに入れ、"
                            + "タイトル先頭に【期限】【やる】が自動で付く（自分で付けなくてよい）。"
                            + "調べ物の結果をタスクにしたいときは、要点を notes に入れる。"
                            + "支払カレンダーには書き込めない。",
                    props(
                            prop("title", "string", "タイトル（接頭辞は付けない）"),
                            prop("kind", "string", "予定 / 期限 / やる"),
                            prop("date", "string", "日付 YYYY-MM-DD"),
                            prop("start_time", "string", "開始時刻 HH:MM（kind=予定 のとき必須）"),
                            prop("end_time", "string", "終了時刻 HH:MM（省略時は開始の1時間後）"),
                            prop("calendar", "string", "仕事 / プライベート（kind=予定 のとき。省略時は仕事）"),
                            prop("notes", "string", "説明欄に入れるメモ（任意）")
                    ), "title", "kind", "date"));

            a.put(tool("complete_event",
                    "タスクを完了にする。タイトルの先頭に「済」を付ける（カレンダーには完了状態が無いため、これが完了の印）。",
                    props(prop("event_id", "integer", "list_events で得た event_id")), "event_id"));

            a.put(tool("update_event",
                    "予定・タスクの日付や時刻、タイトルを変える。実行前に本人に確認が出る。",
                    props(
                            prop("event_id", "integer", "list_events で得た event_id"),
                            prop("title", "string", "新しいタイトル（変えないなら省略）"),
                            prop("date", "string", "新しい日付 YYYY-MM-DD（変えないなら省略）"),
                            prop("start_time", "string", "新しい開始時刻 HH:MM（終日のままなら省略）"),
                            prop("end_time", "string", "新しい終了時刻 HH:MM")
                    ), "event_id"));

            a.put(tool("delete_event",
                    "予定・タスクを削除する。実行前に本人に確認が出る。完了させたいだけなら complete_event を使う。",
                    props(prop("event_id", "integer", "list_events で得た event_id")), "event_id"));
        }

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
            case "list_events":
                return "カレンダーを見ています…";
            case "create_event":
                return "カレンダーに登録しています…";
            case "complete_event":
                return "完了にしています…";
            case "update_event":
                return "予定を変更しています…";
            case "delete_event":
                return "予定を削除しています…";
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
                case "list_events":
                    return listEvents(h, in);
                case "create_event":
                    return createEvent(h, in);
                case "complete_event":
                    return completeEvent(h, in);
                case "update_event":
                    return updateEvent(h, in);
                case "delete_event":
                    return deleteEvent(h, in);
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
        onMain(() -> {
            ClipboardManager cm = (ClipboardManager) h.context().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("assistant", text));
        });
        String head = text.length() > 30 ? text.substring(0, 30) + "…" : text;
        return Outcome.ok("コピーしました。", "📋 コピー: " + head.replace('\n', ' '));
    }

    // ---------------- カレンダー ----------------

    private static Outcome listEvents(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        if (!Cal.canRead(c)) return Outcome.err("カレンダーの許可がありません。アプリを開いて許可してください。");
        String start = in.optString("start_date", "");
        if (start.isEmpty()) start = Cal.today();
        int days = Math.max(1, Math.min(60, in.optInt("days", 1)));
        String only = in.optString("calendar", "");
        boolean includeDone = in.optBoolean("include_done", false);

        long from = Cal.dayStart(start);
        long to = from + days * 86400000L;
        List<Cal.Event> list = Cal.events(c, from, to);

        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" から ").append(days).append("日分\n");
        int n = 0;
        for (Cal.Event e : list) {
            if (!only.isEmpty() && (e.calendar == null || !e.calendar.contains(only))) continue;
            if (!includeDone && e.title.startsWith("済")) continue;
            n++;
            String when = e.allDay
                    ? Cal.allDayDate(e.begin) + " 終日"
                    : Cal.fmt("M/d(E) H:mm", e.begin) + "-" + Cal.fmt("H:mm", e.end);
            sb.append("- [").append(e.calendar).append("] ").append(when).append(" ")
                    .append(e.title).append(" (event_id=").append(e.id).append(")");
            if (e.notes != null && !e.notes.trim().isEmpty()) {
                String notes = e.notes.trim().replace('\n', ' ');
                if (notes.length() > 120) notes = notes.substring(0, 120) + "…";
                sb.append(" メモ: ").append(notes);
            }
            sb.append("\n");
        }
        if (n == 0) sb.append("（この期間に予定・タスクはありません）\n");
        return Outcome.ok(sb.toString(), null);
    }

    private static Outcome createEvent(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        if (!Cal.canWrite(c)) return Outcome.err("カレンダーへの書き込み許可がありません。アプリを開いて許可してください。");
        String title = in.getString("title").trim();
        String kind = in.optString("kind", "やる").trim();
        String date = in.optString("date", Cal.today());
        String notes = in.optString("notes", "");
        boolean task = kind.contains("期限") || kind.contains("やる");

        String calName = in.optString("calendar", "");
        if (task) calName = "やること";
        else if (calName.isEmpty()) calName = "仕事";
        if (Cal.isPayment(calName)) return Outcome.err("支払カレンダーへの書き込みはしない決まりです。");

        Cal.Info cal = Cal.findCalendar(c, calName);
        if (cal == null) return Outcome.err("「" + calName + "」というカレンダーが見つかりません。使えるのは: " + Cal.calendarNames(c));
        if (!cal.writable) return Outcome.err("「" + cal.name + "」は書き込みできません。");

        if (task) {
            String prefix = kind.contains("期限") ? "【期限】" : "【やる】";
            String full = title.startsWith("【") ? title : prefix + title;
            // 期限は前日18時に通知、やるは通知なし
            int reminder = kind.contains("期限") ? 6 * 60 : -1;
            long id = Cal.insertAllDay(c, cal.id, full, date, notes, reminder);
            String when = Cal.allDayDate(Cal.dateToUtcMidnight(date));
            return Outcome.ok("登録しました（event_id=" + id + "、" + cal.name + "／終日・予定なし）",
                    "📅 " + when + " " + full);
        }

        String st = in.optString("start_time", "");
        if (st.isEmpty()) return Outcome.err("時間の決まった予定には start_time（HH:MM）が要ります。時間が分からないなら kind=やる で登録してください。");
        long begin = Cal.timeOnDay(date, st);
        String et = in.optString("end_time", "");
        long end = et.isEmpty() ? begin + 3600000L : Cal.timeOnDay(date, et);
        if (end <= begin) end = begin + 3600000L;
        long id = Cal.insertTimed(c, cal.id, title, begin, end, notes, 30);
        return Outcome.ok("登録しました（event_id=" + id + "、" + cal.name + "）",
                "📅 " + Cal.fmt("M/d(E) H:mm", begin) + " " + title);
    }

    private static Outcome completeEvent(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        if (!Cal.canWrite(c)) return Outcome.err("カレンダーへの書き込み許可がありません。");
        long id = in.getLong("event_id");
        Cal.Event e = Cal.event(c, id);
        if (e == null) return Outcome.err("その予定が見つかりません（event_id=" + id + "）");
        if (Cal.isPayment(e.calendar)) return Outcome.err("支払カレンダーは書き換えない決まりです。");
        if (e.title.startsWith("済")) return Outcome.ok("すでに完了になっています：" + e.title, null);
        Cal.setTitle(c, id, "済" + e.title);
        return Outcome.ok("完了にしました：済" + e.title, "✅ 済" + e.title);
    }

    private static Outcome updateEvent(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        if (!Cal.canWrite(c)) return Outcome.err("カレンダーへの書き込み許可がありません。");
        long id = in.getLong("event_id");
        Cal.Event e = Cal.event(c, id);
        if (e == null) return Outcome.err("その予定が見つかりません（event_id=" + id + "）");
        if (Cal.isPayment(e.calendar)) return Outcome.err("支払カレンダーは書き換えない決まりです。");

        String newTitle = in.optString("title", "");
        String date = in.optString("date", "");
        String st = in.optString("start_time", "");
        String et = in.optString("end_time", "");

        StringBuilder what = new StringBuilder("「" + e.title + "」を");
        if (!newTitle.isEmpty()) what.append("『").append(newTitle).append("』に改名");
        if (!date.isEmpty() || !st.isEmpty()) {
            if (!newTitle.isEmpty()) what.append("、");
            what.append(date.isEmpty() ? Cal.fmt("M/d", e.begin) : date).append(st.isEmpty() ? "" : " " + st).append("へ変更");
        }
        what.append("します。いいですか？");
        if (!ask(h, what.toString())) return Outcome.ok("本人がキャンセルしました。変更していません。", "✋ 変更をやめました");

        if (!newTitle.isEmpty()) Cal.setTitle(c, id, newTitle);
        if (!st.isEmpty()) {
            String d = date.isEmpty() ? Cal.fmt("yyyy-MM-dd", e.begin) : date;
            long begin = Cal.timeOnDay(d, st);
            long end = et.isEmpty() ? begin + 3600000L : Cal.timeOnDay(d, et);
            Cal.setTime(c, id, begin, end);
        } else if (!date.isEmpty()) {
            if (e.allDay) Cal.setAllDayDate(c, id, date);
            else {
                long begin = Cal.timeOnDay(date, Cal.fmt("HH:mm", e.begin));
                Cal.setTime(c, id, begin, begin + (e.end - e.begin));
            }
        }
        return Outcome.ok("変更しました。", "✏ 変更: " + (newTitle.isEmpty() ? e.title : newTitle));
    }

    private static Outcome deleteEvent(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        if (!Cal.canWrite(c)) return Outcome.err("カレンダーへの書き込み許可がありません。");
        long id = in.getLong("event_id");
        Cal.Event e = Cal.event(c, id);
        if (e == null) return Outcome.err("その予定が見つかりません（event_id=" + id + "）");
        if (Cal.isPayment(e.calendar)) return Outcome.err("支払カレンダーは触らない決まりです。");
        if (!ask(h, "「" + e.title + "」を削除します。元に戻せません。いいですか？"))
            return Outcome.ok("本人がキャンセルしました。削除していません。", "✋ 削除をやめました");
        Cal.delete(c, id);
        return Outcome.ok("削除しました：" + e.title, "🗑 削除: " + e.title);
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
