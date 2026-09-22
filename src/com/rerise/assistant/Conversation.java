package com.rerise.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 今の会話。アプリ本体の画面とアシスタント重ね表示で同じものを共有する（同じプロセス内のシングルトン）。
 * messages … API に送る形そのもの
 * display  … 画面に並べる吹き出し（user / assistant / card / error）
 */
public class Conversation {

    private static Conversation instance;

    public final JSONArray messages = new JSONArray();
    public final JSONArray display = new JSONArray();

    public static synchronized Conversation get(Context c) {
        if (instance == null) {
            instance = new Conversation();
            instance.load(c.getApplicationContext());
        }
        return instance;
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "conversation.json");
    }

    private void load(Context c) {
        try {
            File f = file(c);
            if (!f.exists()) return;
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            JSONArray m = o.optJSONArray("messages");
            JSONArray d = o.optJSONArray("display");
            if (m != null) for (int i = 0; i < m.length(); i++) messages.put(m.get(i));
            if (d != null) for (int i = 0; i < d.length(); i++) display.put(d.get(i));
        } catch (Exception ignored) {
        }
    }

    public synchronized void save(Context c) {
        try {
            JSONObject o = new JSONObject().put("messages", messages).put("display", display);
            FileOutputStream out = new FileOutputStream(file(c));
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /** 新しい会話にする。古いものは history/ に日時付きで残す */
    public synchronized void reset(Context c) {
        if (messages.length() > 0) {
            try {
                File dir = new File(c.getFilesDir(), "history");
                dir.mkdirs();
                String name = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.JAPAN).format(new Date()) + ".json";
                JSONObject o = new JSONObject().put("messages", messages).put("display", display);
                FileOutputStream out = new FileOutputStream(new File(dir, name));
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
                out.close();
            } catch (Exception ignored) {
            }
        }
        while (messages.length() > 0) messages.remove(0);
        while (display.length() > 0) display.remove(0);
        save(c);
    }

    public synchronized void addDisplay(String kind, String text) {
        try {
            display.put(new JSONObject().put("kind", kind).put("text", text));
        } catch (Exception ignored) {
        }
    }

    /** 最後の吹き出しの本文を差し替える（ストリーミング中の assistant 用） */
    public synchronized void updateLastDisplay(String kind, String text) {
        try {
            int n = display.length();
            if (n > 0 && kind.equals(display.getJSONObject(n - 1).optString("kind"))) {
                display.getJSONObject(n - 1).put("text", text);
            } else {
                addDisplay(kind, text);
            }
        } catch (Exception ignored) {
        }
    }

    /** ユーザーの発言で始まる区切り（= 1往復の先頭）か */
    private static boolean isTurnStart(JSONObject m) {
        if (!"user".equals(m.optString("role"))) return false;
        Object c = m.opt("content");
        if (c instanceof String) return true;
        if (c instanceof JSONArray) {
            JSONArray a = (JSONArray) c;
            for (int i = 0; i < a.length(); i++) {
                JSONObject b = a.optJSONObject(i);
                if (b != null && "tool_result".equals(b.optString("type"))) return false;
            }
            return true;
        }
        return false;
    }

    /** API に送る分。直近 turns 往復だけに絞る */
    public synchronized JSONArray forApi(int turns) throws Exception {
        int start = 0, count = 0;
        for (int i = messages.length() - 1; i >= 0; i--) {
            if (isTurnStart(messages.getJSONObject(i))) {
                count++;
                start = i;
                if (count >= turns) break;
            }
        }
        JSONArray out = new JSONArray();
        for (int i = start; i < messages.length(); i++) out.put(messages.get(i));
        return out;
    }

    /**
     * 終わった往復を軽くする。検索結果の本体・思考ブロック・引用は次の往復では要らないので捨てる。
     * （そのままだと会話が進むほど毎回数万トークン送ることになる）
     */
    public synchronized void compactFinishedTurns() {
        try {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.getJSONObject(i);
                if (!"assistant".equals(m.optString("role"))) continue;
                Object c = m.opt("content");
                if (!(c instanceof JSONArray)) continue;
                JSONArray a = (JSONArray) c, keep = new JSONArray();
                for (int k = 0; k < a.length(); k++) {
                    JSONObject b = a.getJSONObject(k);
                    String t = b.optString("type");
                    if ("server_tool_use".equals(t) || "web_search_tool_result".equals(t)
                            || "thinking".equals(t) || "redacted_thinking".equals(t)) continue;
                    if ("text".equals(t)) {
                        if (b.optString("text").isEmpty()) continue;
                        b = new JSONObject().put("type", "text").put("text", b.optString("text"));
                    }
                    keep.put(b);
                }
                if (keep.length() == 0) keep.put(new JSONObject().put("type", "text").put("text", "（調べました）"));
                m.put("content", keep);
            }
        } catch (Exception ignored) {
        }
    }
}
