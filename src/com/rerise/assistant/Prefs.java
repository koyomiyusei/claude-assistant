package com.rerise.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 設定の保存。APIキーだけは Android Keystore の AES/GCM 鍵で暗号化してから保存する。
 * 鍵そのものは端末のセキュア領域にあり、アプリの外へは取り出せない。
 */
public class Prefs {

    private static final String PREF = "assistant";
    private static final String KS_ALIAS = "assistant_api_key";

    public static final String[][] MODELS = {
            {"claude-sonnet-5", "Sonnet 5（標準・おすすめ）"},
            {"claude-haiku-4-5-20251001", "Haiku 4.5（速い・安い）"},
            {"claude-opus-5", "Opus 5（重い相談向け）"},
    };

    public static final String[][] EFFORTS = {
            {"low", "速さ優先"},
            {"medium", "バランス"},
            {"high", "じっくり考える"},
    };

    public static final String DEFAULT_SYSTEM =
            "あなたは私専用のスマホアシスタントです。Androidアプリの中で動いていて、ツールを使って端末を操作できます。\n"
            + "\n"
            + "# 返し方\n"
            + "- スマホの小さな画面で読まれる。結論から、短く。長い説明は求められたときだけ\n"
            + "- 端末操作を頼まれたら、聞き返さずにツールで実行し、何をしたかを一言で伝える。日時が曖昧なら常識的に補って実行し、補ったことを伝える\n"
            + "- 最新の情報が必要な質問（価格・営業時間・ニュース・天気など）はWeb検索で調べてから答える。調べた結果は答えるだけで、頼まれない限りカレンダーには登録しない\n"
            + "- 音声で話しかけられることが多い。聞き間違いらしき語は文脈で直して解釈する\n"
            + "\n"
            + "# 私について\n"
            + "（ここに住んでいる地域・仕事・よく頼むことなどを書いてください）\n";

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------- APIキー ----------------

    public static boolean hasApiKey(Context c) {
        return sp(c).contains("api_key_enc");
    }

    public static String getApiKey(Context c) {
        String enc = sp(c).getString("api_key_enc", null);
        String iv = sp(c).getString("api_key_iv", null);
        if (enc == null || iv == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean setApiKey(Context c, String k) {
        if (k == null || k.trim().isEmpty()) {
            sp(c).edit().remove("api_key_enc").remove("api_key_iv").apply();
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] out = cipher.doFinal(k.trim().getBytes(StandardCharsets.UTF_8));
            sp(c).edit()
                    .putString("api_key_enc", Base64.encodeToString(out, Base64.NO_WRAP))
                    .putString("api_key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 表示用：先頭と末尾だけ見せる */
    public static String maskedKey(Context c) {
        String k = getApiKey(c);
        if (k == null) return "未設定";
        if (k.length() < 16) return "保存済み";
        return k.substring(0, 10) + "…" + k.substring(k.length() - 4);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KS_ALIAS, null)).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KS_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    // ---------------- その他の設定 ----------------

    /** 組織スコープのキーを使うときに必要なワークスペースID（ワークスペーススコープのキーなら空でよい） */
    public static String workspaceId(Context c) {
        return sp(c).getString("workspace_id", "");
    }

    public static void setWorkspaceId(Context c, String v) {
        sp(c).edit().putString("workspace_id", v == null ? "" : v.trim()).apply();
    }

    public static String model(Context c) {
        return sp(c).getString("model", MODELS[0][0]);
    }

    public static void setModel(Context c, String v) {
        sp(c).edit().putString("model", v).apply();
    }

    /** 端末操作・ふつうの会話の深さ（速さ優先） */
    public static String effort(Context c) {
        return sp(c).getString("effort", "low");
    }

    /** 調べ物のときの深さ（じっくり） */
    public static String searchEffort(Context c) {
        return sp(c).getString("effort_search", "high");
    }

    public static void setSearchEffort(Context c, String v) {
        sp(c).edit().putString("effort_search", v).apply();
    }

    public static void setEffort(Context c, String v) {
        sp(c).edit().putString("effort", v).apply();
    }

    public static String systemPrompt(Context c) {
        return sp(c).getString("system", DEFAULT_SYSTEM);
    }

    public static void setSystemPrompt(Context c, String v) {
        sp(c).edit().putString("system", v).apply();
    }

    public static boolean webSearch(Context c) {
        return sp(c).getBoolean("web_search", true);
    }

    public static void setWebSearch(Context c, boolean v) {
        sp(c).edit().putBoolean("web_search", v).apply();
    }

    /** サイドキーで呼んだとき、すぐ音声入力を始めるか */
    public static boolean autoVoice(Context c) {
        return sp(c).getBoolean("auto_voice", true);
    }

    public static void setAutoVoice(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_voice", v).apply();
    }

    /** APIに送る直近の往復数 */
    public static int historyTurns(Context c) {
        return sp(c).getInt("history_turns", 20);
    }

    public static void setHistoryTurns(Context c, int v) {
        sp(c).edit().putInt("history_turns", v).apply();
    }

    public static long getLong(Context c, String k, long d) {
        return sp(c).getLong(k, d);
    }

    public static void putLong(Context c, String k, long v) {
        sp(c).edit().putLong(k, v).apply();
    }
}
