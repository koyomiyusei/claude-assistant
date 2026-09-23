package com.rerise.assistant;

import android.Manifest;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 端末に同期済みの Google カレンダーを読み書きする（CalendarContract）。
 * 予定管理のルール：
 *   【期限】【やる】は終日・予定なし(FREE)、完了はタイトル先頭に「済」、支払カレンダーは読み取り専用。
 */
public class Cal {

    public static final TimeZone TZ = TimeZone.getTimeZone("Asia/Tokyo");

    public static class Info {
        public long id;
        public String name;
        public boolean writable;
    }

    public static class Event {
        public long id;
        public long begin, end;
        public boolean allDay;
        public String title, calendar, notes;
        public boolean free;
    }

    public static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------- カレンダー一覧 ----------------

    public static List<Info> calendars(Context c) {
        List<Info> out = new ArrayList<>();
        if (!canRead(c)) return out;
        String[] proj = {CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL};
        Cursor cur = c.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI, proj,
                CalendarContract.Calendars.SYNC_EVENTS + "=1", null, null);
        if (cur == null) return out;
        while (cur.moveToNext()) {
            Info i = new Info();
            i.id = cur.getLong(0);
            i.name = cur.getString(1);
            i.writable = cur.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR;
            if (i.name != null) out.add(i);
        }
        cur.close();
        return out;
    }

    /** 名前でカレンダーを探す（部分一致、大文字小文字は無視） */
    public static Info findCalendar(Context c, String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.JAPAN);
        List<Info> all = calendars(c);
        for (Info i : all) if (i.name.toLowerCase(Locale.JAPAN).equals(n)) return i;
        for (Info i : all) if (i.name.toLowerCase(Locale.JAPAN).contains(n)) return i;
        return null;
    }

    public static String calendarNames(Context c) {
        StringBuilder sb = new StringBuilder();
        for (Info i : calendars(c)) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(i.name);
        }
        return sb.toString();
    }

    public static boolean isPayment(String calendarName) {
        return calendarName != null && calendarName.contains("支払");
    }

    // ---------------- 読み取り ----------------

    public static List<Event> events(Context c, long from, long to) {
        List<Event> out = new ArrayList<>();
        if (!canRead(c)) return out;
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] proj = {CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                CalendarContract.Instances.AVAILABILITY,
                CalendarContract.Instances.DESCRIPTION};
        Cursor cur = c.getContentResolver().query(b.build(), proj, null, null,
                CalendarContract.Instances.BEGIN + " ASC");
        if (cur == null) return out;
        while (cur.moveToNext()) {
            Event e = new Event();
            e.id = cur.getLong(0);
            e.title = cur.getString(1) == null ? "(無題)" : cur.getString(1);
            e.begin = cur.getLong(2);
            e.end = cur.getLong(3);
            e.allDay = cur.getInt(4) == 1;
            e.calendar = cur.getString(5);
            e.free = cur.getInt(6) == CalendarContract.Instances.AVAILABILITY_FREE;
            e.notes = cur.getString(7);
            out.add(e);
        }
        cur.close();
        return out;
    }

    /** 1件だけ引く（event_id 指定） */
    public static Event event(Context c, long id) {
        if (!canRead(c)) return null;
        String[] proj = {CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY, CalendarContract.Events.CALENDAR_DISPLAY_NAME};
        Cursor cur = c.getContentResolver().query(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), proj, null, null, null);
        if (cur == null) return null;
        Event e = null;
        if (cur.moveToFirst()) {
            e = new Event();
            e.id = cur.getLong(0);
            e.title = cur.getString(1) == null ? "(無題)" : cur.getString(1);
            e.begin = cur.getLong(2);
            e.end = cur.getLong(3);
            e.allDay = cur.getInt(4) == 1;
            e.calendar = cur.getString(5);
        }
        cur.close();
        return e;
    }

    // ---------------- 書き込み ----------------

    /** 時間のある予定 */
    public static long insertTimed(Context c, long calId, String title, long begin, long end,
                                   String notes, int reminderMin) throws Exception {
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.CALENDAR_ID, calId);
        v.put(CalendarContract.Events.TITLE, title);
        v.put(CalendarContract.Events.DTSTART, begin);
        v.put(CalendarContract.Events.DTEND, end);
        v.put(CalendarContract.Events.EVENT_TIMEZONE, TZ.getID());
        v.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY);
        if (notes != null && !notes.isEmpty()) v.put(CalendarContract.Events.DESCRIPTION, notes);
        return finishInsert(c, v, reminderMin);
    }

    /** 終日・予定なし（【期限】【やる】用） */
    public static long insertAllDay(Context c, long calId, String title, String date,
                                    String notes, int reminderMin) throws Exception {
        long utcMidnight = dateToUtcMidnight(date);
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.CALENDAR_ID, calId);
        v.put(CalendarContract.Events.TITLE, title);
        v.put(CalendarContract.Events.DTSTART, utcMidnight);
        v.put(CalendarContract.Events.DTEND, utcMidnight + 86400000L);
        v.put(CalendarContract.Events.ALL_DAY, 1);
        v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        v.put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE);
        if (notes != null && !notes.isEmpty()) v.put(CalendarContract.Events.DESCRIPTION, notes);
        return finishInsert(c, v, reminderMin);
    }

    private static long finishInsert(Context c, ContentValues v, int reminderMin) throws Exception {
        Uri u = c.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, v);
        if (u == null) throw new Exception("カレンダーに書き込めませんでした");
        long id = Long.parseLong(u.getLastPathSegment());
        if (reminderMin >= 0) {
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, id);
            r.put(CalendarContract.Reminders.MINUTES, reminderMin);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            try {
                c.getContentResolver().insert(CalendarContract.Reminders.CONTENT_URI, r);
            } catch (Exception ignored) {
            }
        }
        return id;
    }

    public static void setTitle(Context c, long id, String title) throws Exception {
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.TITLE, title);
        int n = c.getContentResolver().update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null);
        if (n == 0) throw new Exception("更新できませんでした");
    }

    public static void setTime(Context c, long id, long begin, long end) throws Exception {
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.DTSTART, begin);
        v.put(CalendarContract.Events.DTEND, end);
        v.put(CalendarContract.Events.ALL_DAY, 0);
        v.put(CalendarContract.Events.EVENT_TIMEZONE, TZ.getID());
        int n = c.getContentResolver().update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null);
        if (n == 0) throw new Exception("更新できませんでした");
    }

    public static void setAllDayDate(Context c, long id, String date) throws Exception {
        long utc = dateToUtcMidnight(date);
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Events.DTSTART, utc);
        v.put(CalendarContract.Events.DTEND, utc + 86400000L);
        v.put(CalendarContract.Events.ALL_DAY, 1);
        v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC");
        int n = c.getContentResolver().update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null);
        if (n == 0) throw new Exception("更新できませんでした");
    }

    public static void delete(Context c, long id) throws Exception {
        int n = c.getContentResolver().delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null);
        if (n == 0) throw new Exception("削除できませんでした");
    }

    // ---------------- 日時の小道具 ----------------

    public static Calendar cal() {
        return Calendar.getInstance(TZ, Locale.JAPAN);
    }

    /** "YYYY-MM-DD" の 0:00（日本時間）のミリ秒 */
    public static long dayStart(String date) throws Exception {
        String[] p = date.trim().split("-");
        Calendar c = cal();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** 終日予定用：その日の 0:00 UTC */
    public static long dateToUtcMidnight(String date) throws Exception {
        String[] p = date.trim().split("-");
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.JAPAN);
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 0, 0, 0);
        return c.getTimeInMillis();
    }

    public static long timeOnDay(String date, String hhmm) throws Exception {
        String[] t = hhmm.trim().split(":");
        Calendar c = cal();
        c.setTimeInMillis(dayStart(date));
        c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(t[0]));
        c.set(Calendar.MINUTE, t.length > 1 ? Integer.parseInt(t[1]) : 0);
        return c.getTimeInMillis();
    }

    public static String today() {
        return fmt("yyyy-MM-dd", System.currentTimeMillis());
    }

    public static String fmt(String pattern, long millis) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.JAPAN);
        f.setTimeZone(TZ);
        return f.format(new Date(millis));
    }

    /** 終日予定は UTC で入っているので、表示用の日付は UTC で読む */
    public static String allDayDate(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("M/d(E)", Locale.JAPAN);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }
}
