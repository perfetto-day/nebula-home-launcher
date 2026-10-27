package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gleb's fork: choices made on the TV in the Aurora screen's Settings. Each falls back to the theme until it is set
 * here, so pushing a theme does not undo them.
 */
final class AuroraPrefs {

    static final int MAX_HOME_APPS = 6;
    private static final String NAME = "aurora";

    private AuroraPrefs() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** The apps on Home, in order: the ones picked here, else the theme's dock_apps. */
    static List<String> homeApps(Context c, List<String> themeApps) {
        String saved = prefs(c).getString("home_apps", null);
        if (saved == null) return new ArrayList<>(themeApps);
        return saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
    }

    static void setHomeApps(Context c, List<String> apps) {
        prefs(c).edit().putString("home_apps", TextUtils.join(",", apps)).apply();
    }

    static boolean flag(Context c, String key, boolean fallback) {
        return prefs(c).getBoolean(key, fallback);
    }

    static void setFlag(Context c, String key, boolean value) {
        prefs(c).edit().putBoolean(key, value).apply();
    }

    static String text(Context c, String key, String fallback) {
        return prefs(c).getString(key, fallback);
    }

    static void setText(Context c, String key, String value) {
        prefs(c).edit().putString(key, value).apply();
    }

    /** govee_auto: the switch in Settings, else the theme's govee_auto. */
    static boolean goveeAuto(Context c) {
        JSONObject json = Config.overlay(c);
        return flag(c, "govee_auto", json != null && json.optBoolean("govee_auto", false));
    }
}
