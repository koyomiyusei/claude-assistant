package com.rerise.assistant;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知を読む係。許可は 設定 → 通知へのアクセス から本人が与える。
 * 通知は読むだけで、こちらから消したり返信したりはしない（返信は次の版で）。
 */
public class NotifyService extends NotificationListenerService {

    private static NotifyService live;

    @Override
    public void onListenerConnected() {
        live = this;
    }

    @Override
    public void onListenerDisconnected() {
        live = null;
    }

    public static boolean enabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    public static Intent settingsIntent() {
        return new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
    }

    public static class Item {
        public String app, title, text, key, pkg;
        public boolean canReply;
        public long when;
    }

    /** 今出ている通知を新しい順に */
    public static List<Item> current(Context c, int limit) {
        List<Item> out = new ArrayList<>();
        NotifyService s = live;
        if (s == null) return out;
        StatusBarNotification[] ns;
        try {
            ns = s.getActiveNotifications();
        } catch (Throwable e) {
            return out;
        }
        if (ns == null) return out;
        java.util.Arrays.sort(ns, (a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));
        for (StatusBarNotification sb : ns) {
            if (out.size() >= limit) break;
            if (sb.getPackageName().equals(c.getPackageName())) continue;
            Notification n = sb.getNotification();
            if (n == null) continue;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) continue;   // 常駐の再生中などは省く
            Bundle ex = n.extras;
            if (ex == null) continue;
            Item i = new Item();
            i.app = appLabel(c, sb.getPackageName());
            i.title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            i.text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            if (i.text.isEmpty()) i.text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            i.when = sb.getPostTime();
            i.key = sb.getKey();
            i.pkg = sb.getPackageName();
            i.canReply = replyAction(n) != null;
            if (i.title.isEmpty() && i.text.isEmpty()) continue;
            out.add(i);
        }
        return out;
    }

    /** 通知の中から「返信」できるアクションを探す */
    private static Notification.Action replyAction(Notification n) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            if (a.getRemoteInputs() != null && a.getRemoteInputs().length > 0) return a;
        }
        return null;
    }

    /** 通知から直接返信する。相手アプリを開かずに送れる */
    public static String reply(Context c, String key, String text) {
        NotifyService s = live;
        if (s == null) return "通知サービスが動いていません";
        try {
            for (StatusBarNotification sb : s.getActiveNotifications()) {
                if (!sb.getKey().equals(key)) continue;
                Notification.Action a = replyAction(sb.getNotification());
                if (a == null) return "この通知には返信欄がありません";
                android.app.RemoteInput[] ris = a.getRemoteInputs();
                Intent intent = new Intent();
                android.os.Bundle b = new android.os.Bundle();
                for (android.app.RemoteInput ri : ris) b.putCharSequence(ri.getResultKey(), text);
                android.app.RemoteInput.addResultsToIntent(ris, intent, b);
                a.actionIntent.send(c, 0, intent);
                return null;
            }
            return "その通知はもうありません";
        } catch (Throwable e) {
            return "返信できませんでした: " + e.getClass().getSimpleName();
        }
    }

    private static String str(CharSequence c) {
        return c == null ? "" : c.toString().trim();
    }

    private static String appLabel(Context c, String pkg) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }
}
