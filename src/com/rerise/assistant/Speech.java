package com.rerise.assistant;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 端末の音声認識（Google の音声入力など）をアプリ内で直接使う。
 * このアプリ自身が「デフォルトのアシスタント」になると既定の認識サービスが自分（中継役）になるので、
 * ここでは中継を通さず、他社の認識サービスを直接指定する。
 */
public class Speech {

    public interface Callback {
        void onPartial(String text);

        void onFinal(String text);

        void onError(String message);

        void onLevel(float rmsDb);

        void onEnd();
    }

    private final Context ctx;
    private SpeechRecognizer rec;
    private boolean listening;
    private int attempt;          // 何番目の認識サービスを試しているか
    private boolean gotSpeech;    // 声を拾えたか

    public Speech(Context ctx) {
        this.ctx = ctx;
    }

    public boolean isListening() {
        return listening;
    }

    public static boolean hasMicPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** 自分以外の音声認識サービスを探す。Google のものを優先 */
    public static ComponentName findRecognizer(Context c) {
        List<ComponentName> all = recognizers(c);
        return all.isEmpty() ? null : all.get(0);
    }

    /** 使える音声認識サービスを、良さそうな順に並べて返す */
    public static List<ComponentName> recognizers(Context c) {
        List<ComponentName> google = new ArrayList<>(), others = new ArrayList<>();
        try {
            List<ResolveInfo> list = c.getPackageManager().queryIntentServices(
                    new Intent("android.speech.RecognitionService"), 0);
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo == null) continue;
                String pkg = ri.serviceInfo.packageName;
                if (pkg.equals(c.getPackageName())) continue;   // 自分の中継は使わない
                ComponentName cn = new ComponentName(pkg, ri.serviceInfo.name);
                if (pkg.equals("com.google.android.googlequicksearchbox")) google.add(0, cn);
                else if (pkg.startsWith("com.google")) google.add(cn);
                else others.add(cn);
            }
        } catch (Throwable ignored) {
        }
        google.addAll(others);
        return google;
    }

    /** 今どれを使うか（設定画面の表示用） */
    public static String recognizerName(Context c) {
        ComponentName cn = findRecognizer(c);
        if (cn == null) return "見つかりません（Googleアプリが要ります）";
        return cn.getPackageName();
    }

    public void start(final Callback cb) {
        attempt = 0;
        startWith(cb);
    }

    private void startWith(final Callback cb) {
        stopInner();
        if (!hasMicPermission(ctx)) {
            cb.onError("マイクの許可がありません。アプリを開いて許可してください。");
            cb.onEnd();
            return;
        }
        List<ComponentName> list = recognizers(ctx);
        if (list.isEmpty()) {
            cb.onError("音声認識サービスが見つかりません（Googleアプリが必要です）");
            cb.onEnd();
            return;
        }
        if (attempt >= list.size()) {
            cb.onError("音声入力を開始できませんでした。キーボードのマイクを使ってください");
            cb.onEnd();
            return;
        }
        ComponentName cn = list.get(attempt);
        gotSpeech = false;
        rec = SpeechRecognizer.createSpeechRecognizer(ctx, cn);
        rec.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle params) {
            }

            public void onBeginningOfSpeech() {
                gotSpeech = true;
            }

            public void onRmsChanged(float rmsdB) {
                cb.onLevel(rmsdB);
            }

            public void onBufferReceived(byte[] buffer) {
            }

            public void onEndOfSpeech() {
            }

            public void onError(int error) {
                listening = false;
                // 声を拾う前に落ちたときは、別の音声認識サービスで1回だけやり直す
                boolean startupFail = !gotSpeech && (error == SpeechRecognizer.ERROR_CLIENT
                        || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        || error == SpeechRecognizer.ERROR_SERVER
                        || error == 12 /* LANGUAGE_NOT_SUPPORTED */
                        || error == 13 /* LANGUAGE_UNAVAILABLE */
                        || error == 14 /* SERVER_DISCONNECTED */);
                if (startupFail && attempt + 1 < recognizers(ctx).size()) {
                    attempt++;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> startWith(cb), 250);
                    return;
                }
                String m;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        m = "聞き取れませんでした。もう一度🎤を押してください";
                        break;
                    case SpeechRecognizer.ERROR_NETWORK:
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        m = "音声認識：通信できませんでした";
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        m = "音声認識：マイクの許可がありません";
                        break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        m = "音声認識：使用中です。もう一度押してください";
                        break;
                    case SpeechRecognizer.ERROR_AUDIO:
                        m = "マイクを使えませんでした（他のアプリが使用中かも）";
                        break;
                    default:
                        m = "音声認識エラー（" + error + "）。キーボードのマイクも使えます";
                }
                if (m != null) cb.onError(m);
                cb.onEnd();
            }

            public void onResults(Bundle results) {
                listening = false;
                ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty() && !r.get(0).trim().isEmpty()) cb.onFinal(r.get(0));
                cb.onEnd();
            }

            public void onPartialResults(Bundle partial) {
                ArrayList<String> r = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r != null && !r.isEmpty()) cb.onPartial(r.get(0));
            }

            public void onEvent(int eventType, Bundle params) {
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName())
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        listening = true;
        rec.startListening(i);
    }

    public void stop() {
        attempt = 0;
        stopInner();
    }

    private void stopInner() {
        listening = false;
        if (rec != null) {
            try {
                rec.cancel();
                rec.destroy();
            } catch (Exception ignored) {
            }
            rec = null;
        }
    }

    /** 喋り終わりを待たずに確定させる */
    public void finish() {
        if (rec != null) {
            try {
                rec.stopListening();
            } catch (Exception ignored) {
            }
        }
    }
}
