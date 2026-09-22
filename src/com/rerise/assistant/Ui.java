package com.rerise.assistant;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** 色と小さな部品。レイアウトXMLを使わずコードで組むので、見た目の決まりはここに集める */
public class Ui {

    public final int bg, surface, text, sub, accent, onAccent, userBubble, cardBg, errorBg, codeBg, line;
    public final boolean dark;
    private final float density;

    public Ui(Context c) {
        dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        density = c.getResources().getDisplayMetrics().density;
        accent = Color.parseColor("#D97757");   // Claude のオレンジ
        onAccent = Color.WHITE;
        if (dark) {
            bg = Color.parseColor("#1F1E1C");
            surface = Color.parseColor("#2B2A27");
            text = Color.parseColor("#ECEAE4");
            sub = Color.parseColor("#A09D96");
            userBubble = Color.parseColor("#3A3834");
            cardBg = Color.parseColor("#2F3A36");
            errorBg = Color.parseColor("#4A2B2B");
            codeBg = Color.parseColor("#3A3834");
            line = Color.parseColor("#3A3834");
        } else {
            bg = Color.parseColor("#FAF9F5");
            surface = Color.WHITE;
            text = Color.parseColor("#1F1E1C");
            sub = Color.parseColor("#77746D");
            userBubble = Color.parseColor("#EFEDE6");
            cardBg = Color.parseColor("#E6F1EC");
            errorBg = Color.parseColor("#FBE7E4");
            codeBg = Color.parseColor("#EFEDE6");
            line = Color.parseColor("#E5E2DA");
        }
    }

    public int dp(float v) {
        return Math.round(v * density);
    }

    public GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public GradientDrawable roundStroke(int fill, int stroke, float radiusDp) {
        GradientDrawable g = round(fill, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }

    public TextView label(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** 丸いアイコンボタン（文字や絵文字で） */
    public TextView iconButton(Context c, String glyph, int fill, int fg, float sizeDp) {
        TextView b = new TextView(c);
        b.setText(glyph);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setBackground(round(fill, sizeDp / 2));
        b.setMinWidth(dp(sizeDp));
        b.setMinHeight(dp(sizeDp));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public Button pill(Context c, String s, boolean primary) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(primary ? onAccent : text);
        b.setBackground(primary ? round(accent, 20) : roundStroke(surface, line, 20));
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(40));
        b.setMinimumHeight(dp(40));
        b.setStateListAnimator(null);
        return b;
    }
}
