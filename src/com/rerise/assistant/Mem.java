package com.rerise.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 端末の中だけに持つ「覚えていること」。
 * ピン留めしたものは必ず毎回の会話に足す。ピン無しは新しいものから一定数だけ足す。
 */
public class Mem {

    private static final int SOFT_LIMIT = 40;   // ピン無しで毎回送る上限
    private static JSONArray cache;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "memory.json");
    }

    public static synchronized JSONArray all(Context c) {
        if (cache != null) return cache;
        cache = new JSONArray();
        try {
            File f = file(c);
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                FileInputStream in = new FileInputStream(f);
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                in.close();
                cache = new JSONArray(new String(b, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
        return cache;
    }

    private static synchronized void save(Context c) {
        try {
            FileOutputStream out = new FileOutputStream(file(c));
            out.write(all(c).toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    public static synchronized int add(Context c, String text, boolean pinned) {
        try {
            JSONArray a = all(c);
            String t = text.trim();
            for (int i = 0; i < a.length(); i++) {
                if (t.equals(a.getJSONObject(i).optString("text"))) {
                    a.getJSONObject(i).put("pinned", pinned || a.getJSONObject(i).optBoolean("pinned"));
                    save(c);
                    return a.getJSONObject(i).optInt("id");
                }
            }
            int id = 1;
            for (int i = 0; i < a.length(); i++) id = Math.max(id, a.getJSONObject(i).optInt("id") + 1);
            a.put(new JSONObject().put("id", id).put("text", t).put("pinned", pinned)
                    .put("at", System.currentTimeMillis()));
            save(c);
            return id;
        } catch (Exception e) {
            return -1;
        }
    }

    public static synchronized boolean remove(Context c, int id) {
        JSONArray a = all(c);
        for (int i = 0; i < a.length(); i++) {
            if (a.optJSONObject(i) != null && a.optJSONObject(i).optInt("id") == id) {
                a.remove(i);
                save(c);
                return true;
            }
        }
        return false;
    }

    public static synchronized boolean pin(Context c, int id, boolean pinned) {
        JSONArray a = all(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optInt("id") == id) {
                try {
                    o.put("pinned", pinned);
                } catch (Exception ignored) {
                }
                save(c);
                return true;
            }
        }
        return false;
    }

    public static int count(Context c) {
        return all(c).length();
    }

    /** 毎回の会話に足す文章。ピンは全部、ピン無しは新しい順に SOFT_LIMIT 件まで */
    public static String forPrompt(Context c) {
        JSONArray a = all(c);
        if (a.length() == 0) return "";
        StringBuilder pin = new StringBuilder(), rest = new StringBuilder();
        int n = 0;
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String line = "- " + o.optString("text") + "（#" + o.optInt("id") + "）\n";
            if (o.optBoolean("pinned")) pin.append("📌 ").append(line);
            else if (n++ < SOFT_LIMIT) rest.append(line);
        }
        if (pin.length() == 0 && rest.length() == 0) return "";
        return "\n\n# 覚えていること（本人が「覚えといて」と言ったこと。📌は特に重要）\n"
                + pin + rest
                + "忘れてと言われたら forget で消す。番号(#)は forget に使う。\n";
    }
}
