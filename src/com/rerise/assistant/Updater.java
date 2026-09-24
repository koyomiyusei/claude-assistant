package com.rerise.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub 上の latest.json を見て、新しいAPKがあればアプリ内でダウンロードし、
 * PackageInstaller でそのままインストールまで行う。
 */
public class Updater {

    public static final String LATEST_URL =
            "https://raw.githubusercontent.com/koyomiyusei/claude-assistant/main/latest.json";

    private static final String PREF = "assistant_update";
    private static final String KEY_LAST_CHECK = "last_update_check";
    private static final long AUTO_INTERVAL = 3L * 60 * 60 * 1000;

    /** 黙って確認した結果、新しい版があればそのバージョン名。無ければ null */
    public static volatile String available;
    private static final String ACTION_INSTALLED = "com.rerise.assistant.INSTALL_RESULT";

    // ---------------- バージョン ----------------

    public static int currentCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------------- 確認 ----------------

    /** 数時間に1回、黙って確認するだけ。見つけたら available に入れて、画面のボタンを光らせる */
    public static void autoCheck(Activity a, Runnable onFound) {
        SharedPreferences p = a.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong(KEY_LAST_CHECK, 0) < AUTO_INTERVAL) {
            if (available != null && onFound != null) onFound.run();
            return;
        }
        p.edit().putLong(KEY_LAST_CHECK, now).apply();
        check(a, MODE_SILENT, onFound);
    }

    /** ボタン一発：確認して、新しければそのままダウンロード＆インストールまで進む */
    public static void updateNow(Activity a) {
        Toast.makeText(a, "確認しています…", Toast.LENGTH_SHORT).show();
        check(a, MODE_DIRECT, null);
    }

    public static final int MODE_SILENT = 0;   // 黙って確認だけ
    public static final int MODE_ASK = 1;      // 見つけたら内容を見せて聞く
    public static final int MODE_DIRECT = 2;   // 見つけたら即ダウンロード

    public static void check(final Activity a, final boolean silent) {
        check(a, silent ? MODE_SILENT : MODE_ASK, null);
    }

    public static void check(final Activity a, final int mode, final Runnable onFound) {
        new Thread(new Runnable() {
            public void run() {
                JSONObject obj = null;
                String err = null;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(LATEST_URL).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setRequestProperty("Cache-Control", "no-cache");
                    if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
                    StringBuilder sb = new StringBuilder();
                    BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                    obj = new JSONObject(sb.toString());
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    if (conn != null) conn.disconnect();
                }
                final JSONObject result = obj;
                final String error = err;
                ui(new Runnable() {
                    public void run() {
                        present(a, result, error, mode, onFound);
                    }
                });
            }
        }).start();
    }

    private static void present(Activity a, JSONObject o, String error, int mode, Runnable onFound) {
        if (a.isFinishing() || a.isDestroyed()) return;

        if (o == null) {
            if (mode != MODE_SILENT) {
                new AlertDialog.Builder(a).setTitle("確認できませんでした")
                        .setMessage("通信に失敗しました。\n\n" + error)
                        .setPositiveButton("OK", null).show();
            }
            return;
        }

        int latest = o.optInt("versionCode", 0);
        final String latestName = o.optString("versionName", "?");
        final String apkUrl = o.optString("apkUrl", "");
        String notes = o.optString("notes", "");

        if (latest <= currentCode(a)) {
            available = null;
            if (mode != MODE_SILENT) Toast.makeText(a, "最新です（v" + currentName(a) + "）", Toast.LENGTH_SHORT).show();
            return;
        }

        available = latestName;
        if (onFound != null) onFound.run();
        if (mode == MODE_SILENT) return;
        if (mode == MODE_DIRECT) {
            Toast.makeText(a, "v" + latestName + " を入れます", Toast.LENGTH_SHORT).show();
            startUpdate(a, apkUrl, latestName);
            return;
        }

        String msg = "現在 v" + currentName(a) + " → 新しい版 v" + latestName;
        if (!notes.isEmpty()) msg += "\n\n" + notes;

        new AlertDialog.Builder(a)
                .setTitle("アップデートがあります")
                .setMessage(msg)
                .setNegativeButton("あとで", null)
                .setPositiveButton("更新する", (d, w) -> startUpdate(a, apkUrl, latestName))
                .show();
    }

    // ---------------- ダウンロード＆インストール ----------------

    private static void startUpdate(final Activity a, final String url, final String verName) {
        if (url == null || url.isEmpty()) {
            Toast.makeText(a, "ダウンロード先が設定されていません", Toast.LENGTH_SHORT).show();
            return;
        }
        // Android 8 以降は「このアプリからのインストール」を一度だけ許可してもらう必要がある
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !a.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(a)
                    .setTitle("最初に一度だけ許可が必要です")
                    .setMessage("このアプリが更新を入れられるように、「この提供元のアプリを許可」をオンにしてください。\n\n"
                            + "設定画面を開きます。オンにしたら戻ってきて、もう一度「更新確認」を押してください。")
                    .setNegativeButton("やめる", null)
                    .setPositiveButton("設定を開く", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + a.getPackageName())));
                        } catch (Exception e) {
                            Toast.makeText(a, "設定を開けませんでした", Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
            return;
        }
        download(a, url, verName);
    }

    private static void download(final Activity a, final String url, final String verName) {
        // 進捗ダイアログ
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);

        final TextView label = new TextView(a);
        label.setText("ダウンロードしています…");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(label);

        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = pad / 2;
        box.addView(bar, bp);

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("更新 v" + verName)
                .setView(box)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection conn = null;
                PackageInstaller.Session session = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setInstanceFollowRedirects(true);
                    if (conn.getResponseCode() != 200) throw new Exception("HTTP " + conn.getResponseCode());
                    final int total = conn.getContentLength();

                    PackageInstaller pi = a.getPackageManager().getPackageInstaller();
                    PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                            PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                    if (total > 0) params.setSize(total);
                    final int sid = pi.createSession(params);
                    session = pi.openSession(sid);

                    InputStream in = conn.getInputStream();
                    OutputStream out = session.openWrite("apk", 0, total > 0 ? total : -1);
                    byte[] buf = new byte[16384];
                    int n, done = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100L / total);
                            ui(new Runnable() {
                                public void run() {
                                    bar.setIndeterminate(false);
                                    bar.setProgress(pct);
                                    label.setText("ダウンロードしています… " + pct + "%");
                                }
                            });
                        }
                    }
                    session.fsync(out);
                    out.close();
                    in.close();

                    registerResultReceiver(a, dialog);

                    Intent i = new Intent(ACTION_INSTALLED).setPackage(a.getPackageName());
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pending = PendingIntent.getBroadcast(a, sid, i, flags);

                    ui(new Runnable() {
                        public void run() {
                            bar.setIndeterminate(true);
                            label.setText("インストールしています…");
                        }
                    });
                    session.commit(pending.getIntentSender());
                    session.close();
                    session = null;
                } catch (final Exception e) {
                    if (session != null) {
                        try {
                            session.abandon();
                        } catch (Exception ignored) {
                        }
                    }
                    ui(new Runnable() {
                        public void run() {
                            try {
                                dialog.dismiss();
                            } catch (Exception ignored) {
                            }
                            fallback(a, url, e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    });
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    /** インストール結果を受け取る。確認画面の表示もここから */
    private static void registerResultReceiver(final Activity a, final AlertDialog dialog) {
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            public void onReceive(Context c, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // システムの「更新しますか？」を出す（ここは省略できない）
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            a.startActivity(confirm);
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                        dialog.dismiss();
                    } catch (Exception ignored) {
                    }
                    return;
                }
                try {
                    dialog.dismiss();
                } catch (Exception ignored) {
                }
                try {
                    c.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    available = null;
                    Toast.makeText(a, "更新しました", Toast.LENGTH_LONG).show();
                } else {
                    String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    if (m != null && m.contains("INSTALL_FAILED_ABORTED")) return; // ユーザーが取り消した
                    new AlertDialog.Builder(a).setTitle("インストールできませんでした")
                            .setMessage(m == null ? ("コード " + status) : m)
                            .setPositiveButton("OK", null).show();
                }
            }
        };
        IntentFilter f = new IntentFilter(ACTION_INSTALLED);
        if (Build.VERSION.SDK_INT >= 33) {
            a.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            a.registerReceiver(receiver, f);
        }
    }

    /** アプリ内インストールが駄目だったときは、従来通りブラウザに投げる */
    private static void fallback(final Activity a, final String url, String reason) {
        new AlertDialog.Builder(a)
                .setTitle("アプリ内で更新できませんでした")
                .setMessage(reason + "\n\nブラウザでダウンロードして手動で入れますか？")
                .setNegativeButton("やめる", null)
                .setPositiveButton("ブラウザで開く", (d, w) -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addCategory(Intent.CATEGORY_BROWSABLE);
                        a.startActivity(i);
                    } catch (Exception ignored) {
                    }
                })
                .show();
    }

    private static void ui(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }
}
