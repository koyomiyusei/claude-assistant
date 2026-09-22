package com.rerise.assistant;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** API 利用料の概算（今月の合計と、直近1回分）。1ドル=150円で換算 */
public class Usage {

    private static final double YEN_PER_USD = 150.0;

    /** [入力, 出力] $/100万トークン */
    private static double[] price(String model) {
        if (model.contains("haiku")) return new double[]{1, 5};
        if (model.contains("opus")) return new double[]{5, 25};
        return new double[]{2, 10};
    }

    private static String monthKey() {
        return "usage_" + new SimpleDateFormat("yyyyMM", Locale.JAPAN).format(new Date());
    }

    public static void add(Context c, Conversation conv, String model, ClaudeClient.Result r) {
        double[] p = price(model);
        double usd = r.inputTokens * p[0] / 1e6
                + r.cacheRead * p[0] * 0.1 / 1e6
                + r.cacheWrite * p[0] * 1.25 / 1e6
                + r.outputTokens * p[1] / 1e6
                + r.webSearches * 0.01;
        long micro = Math.round(usd * 1e6);
        String k = monthKey();
        Prefs.putLong(c, k, Prefs.getLong(c, k, 0) + micro);
        Prefs.putLong(c, "usage_last", micro);
    }

    public static String yen(long micro) {
        double y = micro / 1e6 * YEN_PER_USD;
        if (y < 1) return String.format(Locale.JAPAN, "%.1f円", y);
        return String.format(Locale.JAPAN, "%,d円", Math.round(y));
    }

    public static String monthSummary(Context c) {
        return "今月の概算 " + yen(Prefs.getLong(c, monthKey(), 0));
    }
}
