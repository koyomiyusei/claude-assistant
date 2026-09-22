package com.rerise.assistant;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Claude Messages API をストリーミング(SSE)で呼ぶ。
 * 返ってきたブロック（text / thinking / tool_use / server_tool_use / web_search_tool_result …）は
 * 型を問わず受け取った形のまま組み立て直して返す。tool_use のループでそのまま送り返すため。
 */
public class ClaudeClient {

    public static final String URL_MESSAGES = "https://api.anthropic.com/v1/messages";

    public interface Listener {
        /** 本文の差分（画面に流す） */
        void onText(String delta);

        /** 「検索中…」など状態の表示 */
        void onStatus(String status);
    }

    public static class Result {
        public JSONArray content = new JSONArray();
        public String stopReason;
        public int inputTokens, outputTokens, cacheRead, cacheWrite;
        public int webSearches;
    }

    public static class ApiException extends Exception {
        public final int code;

        public ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private volatile HttpURLConnection conn;
    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
        HttpURLConnection c = conn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public Result send(String apiKey, JSONObject body, Listener l) throws Exception {
        body.put("stream", true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(URL_MESSAGES).openConnection();
        conn = c;
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("accept", "text/event-stream");
            c.setRequestProperty("x-api-key", apiKey);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.close();

            int code = c.getResponseCode();
            if (code != 200) {
                throw new ApiException(code, describeError(code, readAll(c.getErrorStream())));
            }
            return parse(c.getInputStream(), l);
        } finally {
            conn = null;
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private Result parse(InputStream in, Listener l) throws Exception {
        Result r = new Result();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        // index -> 組み立て中のブロック
        JSONObject[] blocks = new JSONObject[64];
        StringBuilder[] partialJson = new StringBuilder[64];
        String line;
        while ((line = br.readLine()) != null) {
            if (cancelled) break;
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            JSONObject ev = new JSONObject(data);
            String type = ev.optString("type");
            switch (type) {
                case "message_start": {
                    JSONObject u = ev.optJSONObject("message") == null ? null
                            : ev.getJSONObject("message").optJSONObject("usage");
                    if (u != null) {
                        r.inputTokens = u.optInt("input_tokens");
                        r.cacheRead = u.optInt("cache_read_input_tokens");
                        r.cacheWrite = u.optInt("cache_creation_input_tokens");
                        r.outputTokens = u.optInt("output_tokens");
                    }
                    break;
                }
                case "content_block_start": {
                    int i = ev.getInt("index");
                    if (i >= blocks.length) break;
                    JSONObject b = new JSONObject(ev.getJSONObject("content_block").toString());
                    blocks[i] = b;
                    String bt = b.optString("type");
                    if ("tool_use".equals(bt) || "server_tool_use".equals(bt)) {
                        partialJson[i] = new StringBuilder();
                    }
                    if ("server_tool_use".equals(bt)) {
                        r.webSearches++;
                        l.onStatus("Webで調べています…");
                    } else if ("thinking".equals(bt)) {
                        l.onStatus("考えています…");
                    } else if ("tool_use".equals(bt)) {
                        l.onStatus(Tools.statusLabel(b.optString("name")));
                    } else if ("text".equals(bt)) {
                        l.onStatus(null);
                        String t = b.optString("text", "");
                        if (!t.isEmpty()) l.onText(t);
                    }
                    break;
                }
                case "content_block_delta": {
                    int i = ev.getInt("index");
                    if (i >= blocks.length || blocks[i] == null) break;
                    JSONObject b = blocks[i];
                    JSONObject d = ev.getJSONObject("delta");
                    String dt = d.optString("type");
                    if ("text_delta".equals(dt)) {
                        String t = d.optString("text");
                        b.put("text", b.optString("text", "") + t);
                        l.onText(t);
                    } else if ("thinking_delta".equals(dt)) {
                        b.put("thinking", b.optString("thinking", "") + d.optString("thinking"));
                    } else if ("signature_delta".equals(dt)) {
                        b.put("signature", b.optString("signature", "") + d.optString("signature"));
                    } else if ("input_json_delta".equals(dt)) {
                        if (partialJson[i] != null) partialJson[i].append(d.optString("partial_json"));
                    } else if ("citations_delta".equals(dt)) {
                        JSONArray cs = b.optJSONArray("citations");
                        if (cs == null) {
                            cs = new JSONArray();
                            b.put("citations", cs);
                        }
                        cs.put(d.opt("citation"));
                    }
                    break;
                }
                case "content_block_stop": {
                    int i = ev.getInt("index");
                    if (i >= blocks.length || blocks[i] == null) break;
                    if (partialJson[i] != null) {
                        String s = partialJson[i].toString().trim();
                        blocks[i].put("input", s.isEmpty() ? new JSONObject() : new JSONObject(s));
                    }
                    break;
                }
                case "message_delta": {
                    JSONObject d = ev.optJSONObject("delta");
                    if (d != null && d.has("stop_reason")) r.stopReason = d.optString("stop_reason");
                    JSONObject u = ev.optJSONObject("usage");
                    if (u != null) {
                        r.outputTokens = u.optInt("output_tokens", r.outputTokens);
                        if (u.has("input_tokens")) r.inputTokens = u.optInt("input_tokens");
                        JSONObject st = u.optJSONObject("server_tool_use");
                        if (st != null) r.webSearches = st.optInt("web_search_requests", r.webSearches);
                    }
                    break;
                }
                case "error": {
                    JSONObject e = ev.optJSONObject("error");
                    String m = e == null ? data : e.optString("type") + ": " + e.optString("message");
                    throw new ApiException(-1, "通信の途中でエラー: " + m);
                }
                default:
                    break;
            }
        }
        br.close();
        for (JSONObject b : blocks) {
            if (b != null) {
                // text が空のブロックは送り返すと 400 になるので捨てる
                if ("text".equals(b.optString("type")) && b.optString("text").isEmpty()) continue;
                r.content.put(b);
            }
        }
        return r;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String s;
            while ((s = br.readLine()) != null) sb.append(s);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String describeError(int code, String body) {
        String msg = body;
        try {
            JSONObject o = new JSONObject(body);
            JSONObject e = o.optJSONObject("error");
            if (e != null) msg = e.optString("message", body);
        } catch (Exception ignored) {
        }
        switch (code) {
            case 401:
                return "APIキーが正しくありません（401）。設定画面で入れ直してください。";
            case 403:
                return "このキーでは使えません（403）: " + msg;
            case 429:
                return "混み合っているか、利用上限に達しました（429）。少し待ってから試してください。\n" + msg;
            case 529:
                return "Anthropic側が混雑しています（529）。少し待ってから試してください。";
            case 400:
                if (msg.contains("credit")) return "クレジット残高が足りません。Claude Consoleでチャージしてください。";
                return "リクエストの形が不正です（400）: " + msg;
            default:
                return "エラー " + code + ": " + msg;
        }
    }
}
