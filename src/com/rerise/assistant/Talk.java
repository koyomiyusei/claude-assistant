package com.rerise.assistant;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/** 読み上げ。連続会話モードでは、読み終わったら次の聞き取りを自分で始める */
public class Talk {

    public interface Done {
        void onSpoken();
    }

    private TextToSpeech tts;
    private boolean ready;
    private Done pending;
    private String queued;

    public Talk(Context c) {
        tts = new TextToSpeech(c.getApplicationContext(), status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready) {
                tts.setLanguage(Locale.JAPAN);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) {
                    }

                    public void onDone(String id) {
                        Done d = pending;
                        pending = null;
                        if (d != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(d::onSpoken);
                    }

                    public void onError(String id) {
                        Done d = pending;
                        pending = null;
                        if (d != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(d::onSpoken);
                    }
                });
                if (queued != null) {
                    String q = queued;
                    queued = null;
                    speak(q, pending);
                }
            }
        });
    }

    /** 読み上げる。記号やURLは読ませない */
    public void speak(String text, Done done) {
        String t = clean(text);
        if (t.isEmpty()) {
            if (done != null) done.onSpoken();
            return;
        }
        pending = done;
        if (!ready) {
            queued = t;
            return;
        }
        tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "a");
    }

    public void stop() {
        pending = null;
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) {
        }
    }

    public void release() {
        stop();
        try {
            if (tts != null) tts.shutdown();
        } catch (Exception ignored) {
        }
        tts = null;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")   // リンクは文字だけ
                .replaceAll("https?://\\S+", "")
                .replaceAll("[*#`>|]", "")
                .replaceAll("【[^】]*】", "")
                .replaceAll("\\n{2,}", "。")
                .trim();
        if (t.length() > 1200) t = t.substring(0, 1200);
        return t;
    }
}
