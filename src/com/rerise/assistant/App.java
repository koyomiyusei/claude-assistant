package com.rerise.assistant;

import android.app.Application;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/**
 * 落ちたときのエラーを端末に残す。次にアプリを開いたときに中身を見せられるようにするため。
 */
public class App extends Application {

    private static final String FILE = "crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                save(this, t.getName(), e);
            } catch (Throwable ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    public static void save(Context c, String where, Throwable e) {
        StringWriter sw = new StringWriter();
        sw.append(Cal.fmt("yyyy-MM-dd HH:mm:ss", System.currentTimeMillis()))
                .append("  v").append(Updater.currentName(c))
                .append("  [").append(where).append("]\n");
        e.printStackTrace(new PrintWriter(sw));
        try {
            FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE));
            out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    public static String last(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            in.close();
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
    }
}
