package com.rerise.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.hardware.camera2.CameraManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.ContactsContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 連絡先・アプリ・端末設定・位置情報まわりの実務 */
public class Device {

    // ---------------- 連絡先 ----------------

    public static class Contact {
        public String name, number;
    }

    public static boolean canReadContacts(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    public static List<Contact> findContacts(Context c, String query, int limit) {
        List<Contact> out = new ArrayList<>();
        if (!canReadContacts(c)) return out;
        Uri uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(query == null ? "" : query.trim()));
        String[] proj = {ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER};
        Cursor cur = c.getContentResolver().query(uri, proj, null, null, null);
        if (cur == null) return out;
        while (cur.moveToNext() && out.size() < limit) {
            Contact ct = new Contact();
            ct.name = cur.getString(0);
            ct.number = cur.getString(1) == null ? "" : cur.getString(1).replace(" ", "").replace("-", "");
            boolean dup = false;
            for (Contact o : out) if (o.number.equals(ct.number)) dup = true;
            if (!dup) out.add(ct);
        }
        cur.close();
        return out;
    }

    // ---------------- アプリ ----------------

    public static class AppInfo {
        public String label, pkg;
    }

    public static List<AppInfo> launchableApps(Context c) {
        List<AppInfo> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            AppInfo a = new AppInfo();
            a.pkg = ri.activityInfo.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            out.add(a);
        }
        return out;
    }

    /** 表示名のゆるい一致でアプリを探す */
    public static AppInfo findApp(Context c, String name) {
        if (name == null) return null;
        String q = name.trim().toLowerCase(Locale.JAPAN);
        List<AppInfo> all = launchableApps(c);
        for (AppInfo a : all) if (a.label.toLowerCase(Locale.JAPAN).equals(q)) return a;
        for (AppInfo a : all) if (a.label.toLowerCase(Locale.JAPAN).contains(q)) return a;
        for (AppInfo a : all) if (a.pkg.toLowerCase(Locale.JAPAN).contains(q)) return a;
        return null;
    }

    public static Intent launchIntent(Context c, String pkg) {
        return c.getPackageManager().getLaunchIntentForPackage(pkg);
    }

    // ---------------- ライト ----------------

    public static void torch(Context c, boolean on) throws Exception {
        CameraManager cm = (CameraManager) c.getSystemService(Context.CAMERA_SERVICE);
        for (String id : cm.getCameraIdList()) {
            Boolean has = cm.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (has != null && has) {
                cm.setTorchMode(id, on);
                return;
            }
        }
        throw new Exception("ライトのあるカメラが見つかりません");
    }

    // ---------------- 音 ----------------

    /** mode: 通常 / バイブ / マナー(サイレント) */
    public static String ringer(Context c, String mode) throws Exception {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int m;
        String label;
        if (mode.contains("バイブ")) {
            m = AudioManager.RINGER_MODE_VIBRATE;
            label = "バイブ";
        } else if (mode.contains("マナー") || mode.contains("サイレント") || mode.contains("消音")) {
            m = AudioManager.RINGER_MODE_SILENT;
            label = "サイレント";
        } else {
            m = AudioManager.RINGER_MODE_NORMAL;
            label = "通常";
        }
        am.setRingerMode(m);
        return label;
    }

    public static int volume(Context c, String which, int percent) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        int stream = AudioManager.STREAM_MUSIC;
        if (which != null && (which.contains("着信") || which.contains("通知"))) stream = AudioManager.STREAM_RING;
        if (which != null && which.contains("アラーム")) stream = AudioManager.STREAM_ALARM;
        int max = am.getStreamMaxVolume(stream);
        int v = Math.round(max * Math.max(0, Math.min(100, percent)) / 100f);
        am.setStreamVolume(stream, v, 0);
        return Math.round(v * 100f / max);
    }

    // ---------------- 位置 ----------------

    public static boolean canLocate(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static Location lastLocation(Context c) {
        if (!canLocate(c)) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
        } catch (SecurityException ignored) {
        }
        return best;
    }

    /** 緯度経度から住所へ（ネット必要） */
    public static String describe(Context c, Location l) {
        try {
            Geocoder g = new Geocoder(c, Locale.JAPAN);
            List<Address> a = g.getFromLocation(l.getLatitude(), l.getLongitude(), 1);
            if (a != null && !a.isEmpty()) {
                Address ad = a.get(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i <= ad.getMaxAddressLineIndex(); i++) sb.append(ad.getAddressLine(i));
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
