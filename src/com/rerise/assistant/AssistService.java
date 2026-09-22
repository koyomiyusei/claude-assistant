package com.rerise.assistant;

import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * 「デフォルトのデジタルアシスタント」として登録されるための入口。
 * 実際の画面は AssistSession。
 */
public class AssistService extends VoiceInteractionService {

    /** セッション（重ね表示）を作る側 */
    public static class SessionService extends VoiceInteractionSessionService {
        @Override
        public VoiceInteractionSession onNewSession(Bundle args) {
            return new AssistSession(this);
        }
    }
}
