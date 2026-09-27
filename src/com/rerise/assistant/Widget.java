package com.rerise.assistant;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** ホーム画面ウィジェット。タップで音声入力、長い方を押すと文字入力で開く */
public class Widget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), resLayout(c));
            v.setOnClickPendingIntent(id(c, "wVoice"), open(c, true));
            v.setOnClickPendingIntent(id(c, "wText"), open(c, false));
            mgr.updateAppWidget(id, v);
        }
    }

    private static PendingIntent open(Context c, boolean voice) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (voice) i.putExtra(MainActivity.EXTRA_VOICE, true);
        return PendingIntent.getActivity(c, voice ? 1 : 2, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int resLayout(Context c) {
        return c.getResources().getIdentifier("widget", "layout", c.getPackageName());
    }

    private static int id(Context c, String name) {
        return c.getResources().getIdentifier(name, "id", c.getPackageName());
    }
}
