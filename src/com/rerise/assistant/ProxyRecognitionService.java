package com.rerise.assistant;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;

/**
 * デジタルアシスタントとして登録するには「音声認識サービス」も持っている必要がある。
 * このアプリに選ぶと端末の既定の音声認識もこれになるので、他のアプリの音声入力が壊れないよう
 * Google の音声認識へそのまま中継する。
 */
public class ProxyRecognitionService extends RecognitionService {

    private SpeechRecognizer inner;

    @Override
    protected void onStartListening(Intent recognizerIntent, final Callback cb) {
        release();
        ComponentName cn = Speech.findRecognizer(this);
        if (cn == null) {
            safe(() -> cb.error(SpeechRecognizer.ERROR_CLIENT));
            return;
        }
        inner = SpeechRecognizer.createSpeechRecognizer(this, cn);
        inner.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle p) {
                safe(() -> cb.readyForSpeech(p));
            }

            public void onBeginningOfSpeech() {
                safe(cb::beginningOfSpeech);
            }

            public void onRmsChanged(float v) {
                safe(() -> cb.rmsChanged(v));
            }

            public void onBufferReceived(byte[] b) {
                safe(() -> cb.bufferReceived(b));
            }

            public void onEndOfSpeech() {
                safe(cb::endOfSpeech);
            }

            public void onError(int e) {
                safe(() -> cb.error(e));
            }

            public void onResults(Bundle r) {
                safe(() -> cb.results(r));
            }

            public void onPartialResults(Bundle r) {
                safe(() -> cb.partialResults(r));
            }

            public void onEvent(int t, Bundle p) {
            }
        });
        inner.startListening(recognizerIntent);
    }

    @Override
    protected void onStopListening(Callback cb) {
        if (inner != null) inner.stopListening();
    }

    @Override
    protected void onCancel(Callback cb) {
        release();
    }

    @Override
    public void onDestroy() {
        release();
        super.onDestroy();
    }

    private void release() {
        if (inner != null) {
            try {
                inner.destroy();
            } catch (Exception ignored) {
            }
            inner = null;
        }
    }

    private interface Remote {
        void run() throws RemoteException;
    }

    private static void safe(Remote r) {
        try {
            r.run();
        } catch (Exception ignored) {
        }
    }
}
