package com.rerise.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 終わった会話の置き場。＋を押したときにここへ入る。
 * ピン留めした会話は、いつまでも残り、毎回の会話にも要点として足される。
 */
public class Chats {

    private static final int KEEP = 20;          // ピン無しで残す本数
    private static final int PIN_CHARS = 2500;   // ピン留めした会話から毎回送る文字数の上限

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "history");
        d.mkdirs();
        return d;
    }

    private static File indexFile(Context c) {
        return new File(dir(c), "index.json");
    }

    public static JSONArray index(Context c) {
        try {
            File f = indexFile(c);
            if (f.exists()) return new JSONArray(read(f));
        } catch (Exception ignored) {
        }
        return new JSONArray();
    }

    private static void saveIndex(Context c, JSONArray a) {
        try {
            FileOutputStream out = new FileOutputStream(indexFile(c));
            out.write(a.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int off = 0, n;
        while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        in.close();
        return new String(b, StandardCharsets.UTF_8);
    }

    /** 会話を1本しまう。file は history/ の中のファイル名 */
    public static void add(Context c, String file, String title, JSONArray display) {
        try {
            JSONArray a = index(c);
            String head = title;
            if (head == null || head.trim().isEmpty()) {
                head = "（無題）";
                for (int i = 0; i < display.length(); i++) {
                    JSONObject o = display.optJSONObject(i);
                    if (o != null && "user".equals(o.optString("kind"))) {
                        head = o.optString("text");
                        break;
                    }
                }
            }
            if (head.length() > 24) head = head.substring(0, 24) + "…";
            a.put(new JSONObject().put("file", file).put("title", head)
                    .put("at", System.currentTimeMillis()).put("pinned", false)
                    .put("turns", display.length()));
            saveIndex(c, a);
            trim(c);
        } catch (Exception ignored) {
        }
    }

    /** 古いものを消す。ピン留めは消さない */
    private static void trim(Context c) {
        JSONArray a = index(c);
        int unpinned = 0;
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            if (o.optBoolean("pinned")) continue;
            if (++unpinned > KEEP) {
                new File(dir(c), o.optString("file")).delete();
                a.remove(i);
            }
        }
        saveIndex(c, a);
    }

    public static void pin(Context c, String file, boolean pinned) {
        JSONArray a = index(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && file.equals(o.optString("file"))) {
                try {
                    o.put("pinned", pinned);
                } catch (Exception ignored) {
                }
            }
        }
        saveIndex(c, a);
    }

    public static void delete(Context c, String file) {
        JSONArray a = index(c);
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && file.equals(o.optString("file"))) a.remove(i);
        }
        new File(dir(c), file).delete();
        saveIndex(c, a);
    }

    /** 1本ぶんの本文（吹き出しの並び）を読む */
    public static String transcript(Context c, String file, int maxChars) {
        try {
            JSONObject o = new JSONObject(read(new File(dir(c), file)));
            JSONArray d = o.optJSONArray("display");
            if (d == null) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length(); i++) {
                JSONObject b = d.getJSONObject(i);
                String kind = b.optString("kind");
                String who = "user".equals(kind) ? "自分" : "assistant".equals(kind) ? "Claude" : "・";
                sb.append(who).append(": ").append(b.optString("text")).append('\n');
                if (maxChars > 0 && sb.length() > maxChars) {
                    sb.setLength(maxChars);
                    sb.append("…");
                    break;
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static int pinnedCount(Context c) {
        JSONArray a = index(c);
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optBoolean("pinned")) n++;
        }
        return n;
    }

    /** ピン留めした会話を、毎回の会話に足す文章にする */
    public static String forPrompt(Context c) {
        JSONArray a = index(c);
        StringBuilder sb = new StringBuilder();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || !o.optBoolean("pinned")) continue;
            String t = transcript(c, o.optString("file"), PIN_CHARS);
            if (t.isEmpty()) continue;
            sb.append("\n## 📌 ").append(o.optString("title"))
                    .append("（").append(Cal.fmt("M/d", o.optLong("at"))).append("）\n")
                    .append(t).append("\n");
        }
        if (sb.length() == 0) return "";
        return "\n\n# ピン留めした過去の会話（常に踏まえる）" + sb;
    }
}
