package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gleb's fork: the TV backlight through Govee's cloud API (the H6099 has no LAN control). The key comes from the
 * theme's config.json (govee_api_key); the device is looked up once and remembered. Calls run on one background
 * thread; results come back on the main thread. Every failure is a null result, never a crash.
 */
final class Govee {

    interface Result {
        /** @param on the light's state after the call, or null when it could not be read or set */
        void done(Boolean on);
    }

    private static final String TAG = "TvHome.Govee";
    private static final String BASE = "https://openapi.api.govee.com/router/api/v1/";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;
    private final String key, wantedSku;

    private Govee(Context context, String key, String wantedSku) {
        this.context = context.getApplicationContext();
        this.key = key;
        this.wantedSku = wantedSku;
    }

    /** The configured client, or null when the theme gives no API key. */
    static Govee from(Context context) {
        JSONObject json = Config.overlay(context);
        String key = json != null ? json.optString("govee_api_key", "").trim() : "";
        if (key.isEmpty()) return null;
        return new Govee(context, key, json.optString("govee_sku", "").trim());
    }

    /** Whether the theme asks to follow the screen: on when it wakes, off when it sleeps. */
    static boolean auto(Context context) {
        return AuroraPrefs.goveeAuto(context);
    }

    void state(Result result) {
        IO.execute(() -> post(result, readState()));
    }

    void power(boolean on, Result result) {
        IO.execute(() -> post(result, setPower(on) ? on : null));
    }

    /** Blocking, for the screen receiver's own thread. */
    boolean powerNow(boolean on) {
        return setPower(on);
    }

    private static void post(Result result, Boolean value) {
        if (result != null) MAIN.post(() -> result.done(value));
    }

    private Boolean readState() {
        String[] device = device();
        if (device == null) return null;
        try {
            JSONObject body = new JSONObject().put("requestId", UUID.randomUUID().toString())
                    .put("payload", new JSONObject().put("sku", device[0]).put("device", device[1]));
            JSONObject reply = call("device/state", body);
            JSONArray caps = reply.getJSONObject("payload").getJSONArray("capabilities");
            for (int i = 0; i < caps.length(); i++) {
                JSONObject c = caps.getJSONObject(i);
                if ("powerSwitch".equals(c.optString("instance"))) {
                    return c.getJSONObject("state").optInt("value", 0) == 1;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "state: " + e.getMessage());
        }
        return null;
    }

    private boolean setPower(boolean on) {
        String[] device = device();
        if (device == null) return false;
        try {
            JSONObject capability = new JSONObject().put("type", "devices.capabilities.on_off")
                    .put("instance", "powerSwitch").put("value", on ? 1 : 0);
            JSONObject body = new JSONObject().put("requestId", UUID.randomUUID().toString())
                    .put("payload", new JSONObject().put("sku", device[0]).put("device", device[1])
                            .put("capability", capability));
            return call("device/control", body).optInt("code", 0) == 200;
        } catch (Exception e) {
            Log.w(TAG, "power: " + e.getMessage());
            return false;
        }
    }

    /** {sku, device id}: remembered after the first lookup; the light named by govee_sku, else the first light. */
    private String[] device() {
        SharedPreferences prefs = context.getSharedPreferences("govee", Context.MODE_PRIVATE);
        String sku = prefs.getString("sku", null), id = prefs.getString("device", null);
        if (sku != null && id != null && (wantedSku.isEmpty() || wantedSku.equals(sku))) return new String[]{sku, id};
        try {
            JSONArray list = call("user/devices", null).getJSONArray("data");
            for (int i = 0; i < list.length(); i++) {
                JSONObject d = list.getJSONObject(i);
                boolean light = d.optString("type").endsWith(".light");
                if (wantedSku.isEmpty() ? light : wantedSku.equals(d.optString("sku"))) {
                    prefs.edit().putString("sku", d.getString("sku")).putString("device", d.getString("device")).apply();
                    return new String[]{d.getString("sku"), d.getString("device")};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "devices: " + e.getMessage());
        }
        return null;
    }

    private JSONObject call(String path, JSONObject body) throws IOException, org.json.JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Govee-API-Key", key);
            c.setRequestProperty("Content-Type", "application/json");
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = c.getResponseCode();
            try (InputStream in = status < 400 ? c.getInputStream() : c.getErrorStream()) {
                if (in == null) throw new IOException("HTTP " + status);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                if (status >= 400) throw new IOException("HTTP " + status);
                return new JSONObject(buf.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }
}
