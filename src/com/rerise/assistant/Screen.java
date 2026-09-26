package com.rerise.assistant;

import android.app.assist.AssistStructure;
import android.view.ViewStructure;

/**
 * サイドキーで呼ばれたときに、その裏にあった画面の文字を受け取って持っておく。
 * 「このLINEに返信文を作って」「このページ要約して」のときに使う。
 */
public class Screen {

    private static volatile String text = "";
    private static volatile String app = "";      // パッケージ名
    private static volatile String label = "";    // アプリの表示名（LINE、Chrome など）
    private static volatile String url = "";      // ブラウザなら見ていたページ
    private static volatile String shot = "";     // スクリーンショット（base64 JPEG）
    private static volatile long at;

    public static void clear() {
        text = "";
        app = "";
        label = "";
        url = "";
        shot = "";
        at = 0;
    }

    public static boolean hasShot() {
        return !shot.isEmpty() && System.currentTimeMillis() - at < 10 * 60 * 1000;
    }

    public static String shot() {
        return shot;
    }

    public static String label() {
        return label.isEmpty() ? app : label;
    }

    public static String url() {
        return url;
    }

    /** スクリーンショットを受け取って持っておく（小さくしてJPEGに） */
    public static void capture(android.graphics.Bitmap bmp) {
        try {
            if (bmp == null) return;
            int max = 1100;
            if (bmp.getWidth() > max || bmp.getHeight() > max) {
                float sc = Math.min(max / (float) bmp.getWidth(), max / (float) bmp.getHeight());
                bmp = android.graphics.Bitmap.createScaledBitmap(bmp,
                        Math.round(bmp.getWidth() * sc), Math.round(bmp.getHeight() * sc), true);
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos);
            shot = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
            at = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    /** ブラウザで見ていたページ（AssistContent から） */
    public static void capture(android.app.assist.AssistContent content, android.content.Context c) {
        try {
            if (content == null) return;
            android.net.Uri u = content.getWebUri();
            if (u != null) url = u.toString();
        } catch (Throwable ignored) {
        }
    }

    /** パッケージ名から表示名を引く */
    public static void resolveLabel(android.content.Context c) {
        try {
            if (app.isEmpty()) return;
            android.content.pm.PackageManager pm = c.getPackageManager();
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(app, 0)));
        } catch (Throwable e) {
            label = app;
        }
    }

    public static boolean has() {
        return !text.isEmpty() && System.currentTimeMillis() - at < 10 * 60 * 1000;
    }

    public static String app() {
        return app;
    }

    public static String text() {
        return text;
    }

    /** 直前の画面から文字を拾う */
    public static void capture(AssistStructure st) {
        try {
            if (st == null) return;
            StringBuilder sb = new StringBuilder();
            String pkg = "";
            for (int i = 0; i < st.getWindowNodeCount(); i++) {
                AssistStructure.WindowNode w = st.getWindowNodeAt(i);
                if (w == null) continue;
                AssistStructure.ViewNode root = w.getRootViewNode();
                if (root != null && root.getIdPackage() != null) pkg = root.getIdPackage();
                walk(root, sb, 0);
            }
            // ブラウザなら見ているページのドメインを拾う
            try {
                for (int i = 0; i < st.getWindowNodeCount(); i++) {
                    AssistStructure.ViewNode r = st.getWindowNodeAt(i).getRootViewNode();
                    String d = webDomain(r, 0);
                    if (d != null && !d.isEmpty()) {
                        url = d;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            String t = sb.toString().trim();
            if (t.length() > 8000) t = t.substring(0, 8000) + "…（以下略）";
            text = t;
            app = pkg;
            at = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
    }

    private static String webDomain(AssistStructure.ViewNode n, int depth) {
        if (n == null || depth > 25) return null;
        String d = n.getWebDomain();
        if (d != null && !d.isEmpty()) return d;
        for (int i = 0; i < n.getChildCount(); i++) {
            String r = webDomain(n.getChildAt(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private static void walk(AssistStructure.ViewNode n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || sb.length() > 12000) return;
        CharSequence t = n.getText();
        if (t != null) {
            String s = t.toString().trim();
            if (!s.isEmpty()) sb.append(s).append('\n');
        }
        CharSequence d = n.getContentDescription();
        if (t == null && d != null) {
            String s = d.toString().trim();
            if (!s.isEmpty() && s.length() < 120) sb.append(s).append('\n');
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i), sb, depth + 1);
    }

    /** 会話に足す一文。中身そのものは read_screen ツールで渡す */
    public static String forPrompt() {
        if (!has() && !hasShot()) return "";
        StringBuilder sb = new StringBuilder("\n\n# 直前に見ていた画面\n");
        sb.append("アプリ: ").append(label().isEmpty() ? "不明" : label());
        if (!app.isEmpty()) sb.append("（").append(app).append("）");
        sb.append("\n");
        if (!url.isEmpty()) sb.append("見ていたページ: ").append(url).append("\n");
        String head = text;
        if (head.length() > 1500) head = head.substring(0, 1500) + "…";
        if (!head.isEmpty()) {
            sb.append("画面に出ていた文字（先頭だけ）:\n").append(head).append("\n");
        }
        sb.append("全文は read_screen で読める");
        if (hasShot()) sb.append("。見た目の話（表示崩れ・エラー画面・どこを押すか・写真の中身）なら include_image=true でスクリーンショットも見られる");
        sb.append("。\n"
                + "本人が「これ」「この画面」と言わなくても、上の画面が話題の前提だと考えてよい。"
                + "返信を頼まれたら、そのアプリでそのまま送れる文章にする。"
                + "不具合の相談なら、アプリ名と画面の文字から何が起きているかを先に言う。"
                + "画面と関係ない話を始めたときは、画面のことは忘れてよい。\n");
        return sb.toString();
    }
}
