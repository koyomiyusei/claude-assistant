package com.rerise.assistant;

import android.app.assist.AssistStructure;
import android.view.ViewStructure;

/**
 * サイドキーで呼ばれたときに、その裏にあった画面の文字を受け取って持っておく。
 * 「このLINEに返信文を作って」「このページ要約して」のときに使う。
 */
public class Screen {

    private static volatile String text = "";
    private static volatile String app = "";
    private static volatile long at;

    public static void clear() {
        text = "";
        app = "";
        at = 0;
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
            String t = sb.toString().trim();
            if (t.length() > 8000) t = t.substring(0, 8000) + "…（以下略）";
            text = t;
            app = pkg;
            at = System.currentTimeMillis();
        } catch (Throwable ignored) {
        }
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
        if (!has()) return "";
        return "\n\n# 直前に見ていた画面\n"
                + "呼び出したときに開いていたアプリ: " + (app.isEmpty() ? "不明" : app) + "\n"
                + "その画面の文字は read_screen ツールで読める。「これ」「この画面」「このLINE」と言われたら使う。\n";
    }
}
