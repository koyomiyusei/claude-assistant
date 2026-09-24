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
                    "カレンダーに予定またはタスクを登録する。\n"
                            + "【ルール】種別はカレンダーで決まる。マーカーはタイトルの末尾にだけ付く（先頭に付くのは完了の「済」だけ）。\n"
                            + "kind=予定 … 時間の決まった用事（会議・面接・アポ・予約）。仕事 か プライベート のカレンダー。マーカーは付けない。\n"
                            + "kind=タスク … 〆切のあるもの・やりたいこと。「やること」カレンダーに終日・予定なしで入る。area（仕事/私用）が末尾に自動で付く。\n"
                            + "【タイトルの書き方】短い名詞句。15文字以内。一番大事な言葉を先頭に置く（ウィジェットは頭しか見えない）。"
                            + "文章にしない。人が絡むものは「用件 名前」の順（例: 制服 出射さん）。\n"
                            + "【説明(notes)の書き方】冒頭に「▶ 」で始まる箇条書きで、やる動作だけを1行1動作で並べる。"
                            + "そのあと1行空けて、背景・条件・金額などの詳細を書く。調べ物の結果もここに入れる。",
                    props(
                            prop("title", "string", "短い名詞句。マーカーは付けない"),
                            prop("kind", "string", "予定 / タスク"),
                            prop("date", "string", "日付 YYYY-MM-DD"),
                            prop("start_time", "string", "開始時刻 HH:MM（kind=予定 のとき必須。時間の決まったタスクにも使える）"),
                            prop("end_time", "string", "終了時刻 HH:MM（省略時は1時間後）"),
                            prop("calendar", "string", "仕事 / プライベート（kind=予定 のとき。省略時は仕事）"),
                            prop("area", "string", "仕事 / 私用（kind=タスク のとき末尾に付くマーカー。省略時は仕事）"),
                            new Object[]{"waiting", new JSONObject().put("type", "boolean")
                                    .put("description", "他人の動きを待つタスクなら true。末尾に【待ち】が付く")},
                            prop("notes", "string", "説明欄（▶の箇条書き＋空行＋詳細）")
                    ), "title", "kind", "date"));

            a.put(tool("record_money",
                    "家計簿として、実際に動いたお金を支払カレンダーに記録する。「ガソリン5000円」「昼飯800円」など。"
                            + "タイトルは自動で「品目 金額円【支出】【費目】」の形になる。"
                            + "これから出ていく予定（引落・支払・返済）の登録はしない（支払マスタと二重管理になるため）。",
                    props(
                            prop("item", "string", "品目（短く。例: ガソリン、昼食、コンビニ）"),
                            prop("amount", "integer", "金額（円）"),
                            prop("category", "string", "費目：食費/日用品/ガソリン/車両/通信/交際/医療/衣類/趣味/その他"),
                            prop("kind", "string", "支出 / 入金（省略時は支出）"),
                            prop("date", "string", "日付 YYYY-MM-DD（省略時は今日）")
                    ), "item", "amount"));

            a.put(tool("complete_event",
                    "タスク・予定を完了にする。タイトルの先頭に「済 」を付ける（カレンダーには完了状態が無いため、これが唯一の完了の印）。削除はしない。",
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

        // ---- 連絡先・電話・SMS ----
        if (Device.canReadContacts(c)) {
            a.put(tool("find_contact",
                    "連絡先から名前で電話番号を探す。電話やSMSの前に使う。",
                    props(prop("name", "string", "名前の一部（例: 高木）")), "name"));
        }
        a.put(tool("call_phone",
                "電話アプリを番号入力済みの状態で開く。発信ボタンは本人が押す。",
                props(
                        prop("number", "string", "電話番号（ハイフンなし）"),
                        prop("name", "string", "誰にかけるか（表示用・任意）")
                ), "number"));
        a.put(tool("send_sms",
                "SMSを送る。送信前に本人に確認が出る。相手の番号が分からないときは先に find_contact を使う。",
                props(
                        prop("number", "string", "送り先の電話番号"),
                        prop("text", "string", "本文（短く。相手がそのまま読む文章）"),
                        prop("name", "string", "相手の名前（表示用・任意）")
                ), "number", "text"));

        // ---- アプリ・端末 ----
        a.put(tool("open_app",
                "端末のアプリを開く。「LINE開いて」「カレンダー出して」など。",
                props(prop("name", "string", "アプリの表示名（一部でよい）")), "name"));
        a.put(tool("open_url",
                "ブラウザでURLを開く。",
                props(prop("url", "string", "http(s) のURL")), "url"));
        a.put(tool("set_torch",
                "端末のライト（懐中電灯）をつける・消す。",
                props(new Object[]{"on", new JSONObject().put("type", "boolean").put("description", "true=点灯 / false=消灯")}), "on"));
        a.put(tool("set_ringer",
                "マナーモードの切り替え。通常／バイブ／サイレント。",
                props(prop("mode", "string", "通常 / バイブ / マナー")), "mode"));
        a.put(tool("set_volume",
                "音量を変える。",
                props(
                        prop("which", "string", "メディア / 着信 / アラーム（省略時はメディア）"),
                        prop("percent", "integer", "0〜100")
                ), "percent"));

        // ---- 位置・地図 ----
        if (Device.canLocate(c)) {
            a.put(tool("current_location",
                    "今いる場所（緯度経度と住所）を調べる。周辺検索や所要時間の前提に使う。",
                    props()));
        }
        a.put(tool("open_maps",
                "地図アプリを開く。周辺検索（「近くのガソリンスタンド」）や、目的地までの経路案内に使う。",
                props(
                        prop("query", "string", "検索語または目的地（例: ガソリンスタンド、岡山駅）"),
                        new Object[]{"navigate", new JSONObject().put("type", "boolean")
                                .put("description", "true なら経路案内を開始する")}
                ), "query"));

        return a;
    }

    /** 設定画面に出す「できること」一覧。許可の状態で増減する */
    public static String summary(Context c) {
        StringBuilder sb = new StringBuilder();
        sb.append("・アラーム／タイマー\n");
        sb.append("・クリップボードにコピー\n");
        if (Cal.canRead(c)) {
            sb.append("・予定とタスクの確認（").append(Cal.calendarNames(c)).append("）\n");
        } else {
            sb.append("・予定とタスクの確認 … カレンダー未許可\n");
        }
        if (Cal.canWrite(c)) {
            sb.append("・予定の登録（時間つき・30分前に通知）\n");
            sb.append("・タスクの登録（やること／終日・予定なし・末尾に【仕事】【私用】【待ち】）\n");
            sb.append("・「済 」を付けて完了／変更・削除（確認あり）\n");
            sb.append("・家計簿の記録（支払カレンダーへ【支出】【入金】）\n");
            sb.append("・登録したらすぐ同期して右腕ボードに反映\n");
        }
        sb.append(Device.canReadContacts(c) ? "・連絡先の検索／電話をかける\n" : "・連絡先 … 未許可\n");
        sb.append("・SMSを送る（送信前に確認）\n");
        sb.append("・アプリを開く／ライト／マナーモード／音量\n");
        sb.append(Device.canLocate(c) ? "・今いる場所／周辺検索／経路案内\n" : "・地図・経路案内（現在地は未許可）\n");
        sb.append(Prefs.webSearch(c) ? "・Web検索\n" : "・Web検索 … オフ\n");
        sb.append("・音声入力（呼び出したらすぐ聞く：").append(Prefs.autoVoice(c) ? "オン" : "オフ").append("）");
        return sb.toString();
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
            case "record_money":
                return "家計簿に記録しています…";
            case "complete_event":
                return "完了にしています…";
            case "update_event":
                return "予定を変更しています…";
            case "delete_event":
                return "予定を削除しています…";
            case "find_contact":
                return "連絡先を探しています…";
            case "call_phone":
                return "電話アプリを開いています…";
            case "send_sms":
                return "SMSを準備しています…";
            case "open_app":
                return "アプリを開いています…";
            case "open_url":
                return "開いています…";
            case "set_torch":
                return "ライトを操作しています…";
            case "set_ringer":
            case "set_volume":
                return "音の設定を変えています…";
            case "current_location":
                return "今いる場所を調べています…";
            case "open_maps":
                return "地図を開いています…";
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
                case "record_money":
                    return recordMoney(h, in);
                case "complete_event":
                    return completeEvent(h, in);
                case "update_event":
                    return updateEvent(h, in);
                case "delete_event":
                    return deleteEvent(h, in);
                case "find_contact":
                    return findContact(h, in);
                case "call_phone":
                    return callPhone(h, in);
                case "send_sms":
                    return sendSms(h, in);
                case "open_app":
                    return openApp(h, in);
                case "open_url":
                    return openUrl(h, in);
                case "set_torch":
                    return setTorch(h, in);
                case "set_ringer":
                    return setRinger(h, in);
                case "set_volume":
                    return setVolume(h, in);
                case "current_location":
                    return currentLocation(h, in);
                case "open_maps":
                    return openMaps(h, in);
                default:
                    return Outcome.err("未対応のツールです: " + name);
            }
        } catch (android.content.ActivityNotFoundException e) {
            return Outcome.err("対応するアプリが見つかりませんでした（" + name + "）");
        } catch (Throwable e) {
            App.save(h.context(), "tool:" + name, e);
            return Outcome.err(name + " に失敗しました: " + e.getClass().getSimpleName() + " " + e.getMessage());
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
        String kind = in.optString("kind", "タスク").trim();
        String date = in.optString("date", Cal.today());
        String notes = in.optString("notes", "");
        String st = in.optString("start_time", "");
        boolean task = !kind.contains("予定");

        String calName = task ? "やること" : in.optString("calendar", "仕事");
        if (Cal.isPayment(calName)) return Outcome.err("支払カレンダーに予定は入れない決まりです（家計簿の記録は record_money）。");
        Cal.Info cal = Cal.findCalendar(c, calName);
        if (cal == null) return Outcome.err("「" + calName + "」というカレンダーが見つかりません。使えるのは: " + Cal.calendarNames(c));
        if (!cal.writable) return Outcome.err("「" + cal.name + "」は書き込みできません。");

        // マーカーは末尾に付ける（先頭に付くのは完了の「済」だけ）
        String full = title;
        if (task) {
            String area = in.optString("area", "仕事").contains("私用") ? "【私用】" : "【仕事】";
            if (!full.contains("【仕事】") && !full.contains("【私用】")) full = full + area;
            if (in.optBoolean("waiting", false) && !full.contains("【待ち】")) full = full + "【待ち】";
        }

        long id;
        String when;
        if (task && st.isEmpty()) {
            id = Cal.insertAllDay(c, cal.id, full, date, notes, 6 * 60);   // 前日18時に通知
            when = Cal.allDayDate(Cal.dateToUtcMidnight(date));
        } else {
            if (st.isEmpty()) return Outcome.err("時間の決まった予定には start_time（HH:MM）が要ります。時間が決まっていないなら kind=タスク で登録してください。");
            long begin = Cal.timeOnDay(date, st);
            String et = in.optString("end_time", "");
            long end = et.isEmpty() ? begin + 3600000L : Cal.timeOnDay(date, et);
            if (end <= begin) end = begin + 3600000L;
            id = Cal.insertTimed(c, cal.id, full, begin, end, notes, 30, task);
            when = Cal.fmt("M/d(E) H:mm", begin);
        }
        Cal.syncNow(c, cal);
        return Outcome.ok("登録しました（event_id=" + id + "、" + cal.name + (task ? "／終日・予定なし" : "") + "）。"
                + "右腕ボードには最大1分で出ます。", "📅 " + when + " " + full);
    }

    private static Outcome recordMoney(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        if (!Cal.canWrite(c)) return Outcome.err("カレンダーへの書き込み許可がありません。");
        String item = in.getString("item").trim();
        long amount = in.getLong("amount");
        String kind = in.optString("kind", "支出").contains("入金") ? "【入金】" : "【支出】";
        String cat = in.optString("category", "").trim();
        String[] cats = {"食費", "日用品", "ガソリン", "車両", "通信", "交際", "医療", "衣類", "趣味", "その他"};
        boolean known = false;
        for (String k : cats) if (k.equals(cat)) known = true;
        if (!known) cat = "その他";
        String date = in.optString("date", Cal.today());

        Cal.Info cal = Cal.findCalendar(c, "支払");
        if (cal == null) return Outcome.err("支払カレンダーが見つかりません。");
        if (!cal.writable) return Outcome.err("支払カレンダーに書き込めません。");
        String title = item + " " + String.format(java.util.Locale.JAPAN, "%,d", amount) + "円" + kind
                + ("【入金】".equals(kind) ? "" : "【" + cat + "】");
        long id = Cal.insertAllDay(c, cal.id, title, date, in.optString("notes", ""), -1);
        Cal.syncNow(c, cal);
        return Outcome.ok("家計簿に記録しました（event_id=" + id + "）", "💰 " + title);
    }

    private static Outcome completeEvent(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        if (!Cal.canWrite(c)) return Outcome.err("カレンダーへの書き込み許可がありません。");
        long id = in.getLong("event_id");
        Cal.Event e = Cal.event(c, id);
        if (e == null) return Outcome.err("その予定が見つかりません（event_id=" + id + "）");
        if (Cal.isPayment(e.calendar)) return Outcome.err("支払カレンダーは書き換えない決まりです。");
        if (e.title.startsWith("済")) return Outcome.ok("すでに完了になっています：" + e.title, null);
        Cal.setTitle(c, id, "済 " + e.title);
        Cal.syncNow(c, null);
        return Outcome.ok("完了にしました：済 " + e.title, "✅ 済 " + e.title);
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
        Cal.syncNow(c, null);
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
        Cal.syncNow(c, null);
        return Outcome.ok("削除しました：" + e.title, "🗑 削除: " + e.title);
    }

    // ---------------- 連絡先・電話・SMS ----------------

    private static Outcome findContact(Host h, JSONObject in) throws Exception {
        List<Device.Contact> list = Device.findContacts(h.context(), in.getString("name"), 5);
        if (list.isEmpty()) return Outcome.ok("その名前の連絡先は見つかりませんでした。", null);
        StringBuilder sb = new StringBuilder();
        for (Device.Contact ct : list) sb.append("- ").append(ct.name).append(" ").append(ct.number).append("\n");
        return Outcome.ok(sb.toString(), null);
    }

    private static Outcome callPhone(Host h, JSONObject in) throws Exception {
        String num = in.getString("number").replace("-", "").replace(" ", "");
        String name = in.optString("name", "");
        h.launch(new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:" + num))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return Outcome.ok("電話アプリを開きました（発信ボタンは本人が押します）。",
                "📞 " + (name.isEmpty() ? num : name + " " + num));
    }

    private static Outcome sendSms(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        String num = in.getString("number").replace("-", "").replace(" ", "");
        String text = in.getString("text");
        String name = in.optString("name", "");
        String who = name.isEmpty() ? num : name + "（" + num + "）";
        if (!ask(h, who + " にSMSを送ります。\n\n" + text + "\n\n送っていいですか？"))
            return Outcome.ok("本人がキャンセルしました。送っていません。", "✋ 送信をやめました");

        boolean canSend = c.checkSelfPermission(android.Manifest.permission.SEND_SMS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (canSend) {
            android.telephony.SmsManager sm = c.getSystemService(android.telephony.SmsManager.class);
            java.util.ArrayList<String> parts = sm.divideMessage(text);
            sm.sendMultipartTextMessage(num, null, parts, null, null);
            return Outcome.ok("送信しました。", "✉ SMS送信: " + who);
        }
        // 権限が無いときは、SMSアプリに本文を入れた状態で開く
        Intent i = new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:" + num))
                .putExtra("sms_body", text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        h.launch(i);
        return Outcome.ok("SMSアプリを本文入りで開きました（送信ボタンは本人が押します）。", "✉ SMS下書き: " + who);
    }

    // ---------------- アプリ・端末 ----------------

    private static Outcome openApp(Host h, JSONObject in) throws Exception {
        Device.AppInfo app = Device.findApp(h.context(), in.getString("name"));
        if (app == null) return Outcome.err("「" + in.getString("name") + "」というアプリが見つかりません。");
        Intent i = Device.launchIntent(h.context(), app.pkg);
        if (i == null) return Outcome.err(app.label + " を開けませんでした。");
        h.launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return Outcome.ok(app.label + " を開きました。", "▶ " + app.label);
    }

    private static Outcome openUrl(Host h, JSONObject in) throws Exception {
        String url = in.getString("url");
        if (!url.startsWith("http")) return Outcome.err("http(s) で始まるURLだけ開けます。");
        h.launch(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return Outcome.ok("開きました。", "🔗 " + url);
    }

    private static Outcome setTorch(Host h, JSONObject in) throws Exception {
        boolean on = in.getBoolean("on");
        Device.torch(h.context(), on);
        return Outcome.ok(on ? "ライトをつけました。" : "ライトを消しました。", on ? "🔦 点灯" : "🔦 消灯");
    }

    private static Outcome setRinger(Host h, JSONObject in) throws Exception {
        try {
            String label = Device.ringer(h.context(), in.getString("mode"));
            return Outcome.ok(label + "にしました。", "🔕 " + label);
        } catch (SecurityException e) {
            return Outcome.err("サイレントにするには「通知へのアクセス（サイレントモードの制御）」の許可が要ります。"
                    + "設定 → アプリ → アシスタント → 通知の制御 から許可してください。");
        }
    }

    private static Outcome setVolume(Host h, JSONObject in) throws Exception {
        String which = in.optString("which", "メディア");
        int got = Device.volume(h.context(), which, in.getInt("percent"));
        return Outcome.ok(which + "の音量を" + got + "%にしました。", "🔊 " + which + " " + got + "%");
    }

    // ---------------- 位置・地図 ----------------

    private static Outcome currentLocation(Host h, JSONObject in) throws Exception {
        Context c = h.context();
        if (!Device.canLocate(c)) return Outcome.err("位置情報の許可がありません。アプリを開いて許可してください。");
        android.location.Location l = Device.lastLocation(c);
        if (l == null) return Outcome.err("今いる場所が取れませんでした（位置情報がオフか、まだ測位していません）。");
        String addr = Device.describe(c, l);
        String age = "（" + Math.max(0, (System.currentTimeMillis() - l.getTime()) / 60000) + "分前の位置）";
        return Outcome.ok("緯度 " + String.format(java.util.Locale.US, "%.5f", l.getLatitude())
                + " / 経度 " + String.format(java.util.Locale.US, "%.5f", l.getLongitude())
                + (addr.isEmpty() ? "" : "\n住所: " + addr) + "\n" + age, null);
    }

    private static Outcome openMaps(Host h, JSONObject in) throws Exception {
        String q = in.getString("query");
        boolean nav = in.optBoolean("navigate", false);
        android.net.Uri uri = nav
                ? android.net.Uri.parse("google.navigation:q=" + android.net.Uri.encode(q))
                : android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(q));
        h.launch(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return Outcome.ok((nav ? "経路案内を開きました：" : "地図を開きました：") + q, "🗺 " + q);
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
