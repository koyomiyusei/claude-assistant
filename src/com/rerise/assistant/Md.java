package com.rerise.assistant;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Claude の返す Markdown を、TextView 用に最低限だけ整形する（見出し・太字・箇条書き・コード・リンク） */
public class Md {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)");

    public static CharSequence render(String src, int codeBg) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = src.split("\n", -1);
        boolean inFence = false;
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            if (line.trim().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            int start = out.length();
            if (inFence) {
                out.append(line);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeBg), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                float size = 0;
                if (line.startsWith("### ")) {
                    line = line.substring(4);
                    size = 1.05f;
                } else if (line.startsWith("## ")) {
                    line = line.substring(3);
                    size = 1.12f;
                } else if (line.startsWith("# ")) {
                    line = line.substring(2);
                    size = 1.2f;
                } else if (line.matches("^\\s*[-*] .*")) {
                    int indent = line.indexOf(line.trim());
                    line = repeat("  ", indent / 2) + "• " + line.trim().substring(2);
                } else if (line.trim().equals("---")) {
                    line = "────────";
                } else if (line.startsWith("|") && line.replace("|", "").replace("-", "").replace(":", "").trim().isEmpty()) {
                    continue; // 表の区切り行
                }
                inline(out, line, codeBg);
                if (size > 0) {
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new RelativeSizeSpan(size), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (li < lines.length - 1) out.append('\n');
        }
        return out;
    }

    private static void inline(SpannableStringBuilder out, String line, int codeBg) {
        // 先にリンク → コード → 太字 の順で、一番早く出てくるものから処理していく
        int pos = 0;
        while (pos < line.length()) {
            Matcher mb = BOLD.matcher(line), mc = CODE.matcher(line), ml = LINK.matcher(line);
            int ib = mb.find(pos) ? mb.start() : Integer.MAX_VALUE;
            int ic = mc.find(pos) ? mc.start() : Integer.MAX_VALUE;
            int il = ml.find(pos) ? ml.start() : Integer.MAX_VALUE;
            int first = Math.min(ib, Math.min(ic, il));
            if (first == Integer.MAX_VALUE) {
                out.append(line.substring(pos));
                return;
            }
            out.append(line, pos, first);
            int s = out.length();
            if (first == il) {
                out.append(ml.group(1));
                out.setSpan(new URLSpan(ml.group(2)), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                pos = ml.end();
            } else if (first == ic) {
                out.append(mc.group(1));
                out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new BackgroundColorSpan(codeBg), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                pos = mc.end();
            } else {
                out.append(mb.group(1));
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                pos = mb.end();
            }
        }
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
