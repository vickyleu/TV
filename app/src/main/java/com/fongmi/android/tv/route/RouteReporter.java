package com.fongmi.android.tv.route;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.utils.Task;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RouteReporter {

    private static final String TAG = "RouteReporter";

    public static void reportLive(String playUrl, Channel channel, Group group, Live live) {
        reportLive(playUrl, channel, group, live, 0);
    }

    public static void reportLive(String playUrl, Channel channel, Group group, Live live, long waitMs) {
        String endpoint = BuildConfig.ROUTE_REPORT_URL;
        if (TextUtils.isEmpty(endpoint) || TextUtils.isEmpty(playUrl)) return;
        String host = hostFrom(playUrl);
        String channelName = channel == null ? "" : channel.getName();
        String groupName = group == null ? "" : group.getName();
        String line = channel == null ? "" : channel.getLine();
        String liveName = live == null ? "" : live.getName();
        String payload = buildPayload(playUrl, host, channelName, groupName, line, liveName);
        if (waitMs <= 0) {
            Task.execute(() -> post(endpoint, payload));
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        Task.execute(() -> {
            try {
                post(endpoint, payload);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String hostFrom(String playUrl) {
        try {
            return Uri.parse(playUrl).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private static String buildPayload(String playUrl, String host, String channel, String group, String line, String live) {
        return "{"
                + "\"source\":\"fongmi\","
                + "\"url\":\"" + escape(playUrl) + "\","
                + "\"host\":\"" + escape(host) + "\","
                + "\"channel\":\"" + escape(channel) + "\","
                + "\"group\":\"" + escape(group) + "\","
                + "\"line\":\"" + escape(line) + "\","
                + "\"live\":\"" + escape(live) + "\""
                + "}";
    }

    private static void post(String endpoint, String payload) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(data);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) Log.w(TAG, "route report returned " + code);
        } catch (Exception e) {
            Log.d(TAG, "route report skipped: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }
}
