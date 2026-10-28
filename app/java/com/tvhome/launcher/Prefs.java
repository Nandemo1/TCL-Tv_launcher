package com.tvhome.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny persisted settings store. */
public final class Prefs {

    /** Hard cap on how many apps the favourites row shows. */
    public static final int MAX_FAVOURITES = 10;

    private static final String FILE = "tvhome";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String wallpaperKey() {
        return sp.getString("wpkey", "A0");
    }

    public void setWallpaperKey(String key) {
        sp.edit().putString("wpkey", key).apply();
    }

    public String city() {
        return sp.getString("city", "Hyderabad, Sindh");
    }

    public float lat() {
        return sp.getFloat("lat", 25.3960f);
    }

    public float lon() {
        return sp.getFloat("lon", 68.3578f);
    }

    public void setLocation(String name, float latitude, float longitude) {
        sp.edit()
                .putString("city", name)
                .putFloat("lat", latitude)
                .putFloat("lon", longitude)
                .apply();
    }

    public String weatherCache() {
        return sp.getString("wcache", null);
    }

    public long weatherAt() {
        return sp.getLong("wat", 0L);
    }

    public void setWeather(String json, long at) {
        sp.edit().putString("wcache", json).putLong("wat", at).apply();
    }

    /** Marks the cached weather stale so the home screen refetches. */
    public void invalidateWeather() {
        sp.edit().putLong("wat", 0L).apply();
    }

    // ------------------------------------------------------------------ cricket ticker

    public boolean cricketOn() {
        return sp.getBoolean("cricketOn", true);
    }

    public void setCricketOn(boolean on) {
        sp.edit().putBoolean("cricketOn", on).apply();
    }

    /** 0 = live matches only, 1 = all matches (recent, finished and upcoming). */
    public int cricketFeed() {
        return sp.getInt("cricketFeed", 0);
    }

    public void setCricketFeed(int feed) {
        sp.edit().putInt("cricketFeed", feed).apply();
    }

    // ------------------------------------------------------------------ focus memory

    /** "row:index" of the last focused home tile ("fav", "sec" or "quick"). */
    public String focusKey() {
        return sp.getString("focus", "fav:0");
    }

    public void setFocusKey(String key) {
        sp.edit().putString("focus", key).apply();
    }

    /** Text of the last focused settings chip, restored when the panel reopens. */
    public String settingsFocus() {
        return sp.getString("settingsFocus", "");
    }

    public void setSettingsFocus(String text) {
        sp.edit().putString("settingsFocus", text).apply();
    }

    // ------------------------------------------------------------------ favourites

    public static final String DEFAULT_FAVOURITES =
            "com.netflix.ninja,com.google.android.youtube.tv,com.release.aryzaptv,"
                    + "com.cricfytv.sports,com.playtorrio.tv,com.bd.tamashatv,"
                    + "org.xbmc.kodi,com.spotify.tv.android,com.esaba.downloader";

    public String favouritesRaw() {
        return sp.getString("favs", DEFAULT_FAVOURITES);
    }

    public java.util.List<String> favourites() {
        java.util.List<String> packages = new java.util.ArrayList<>();
        for (String piece : favouritesRaw().split(",")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty() && !packages.contains(trimmed)) {
                packages.add(trimmed);
            }
        }
        return packages;
    }

    public void setFavourites(java.util.List<String> packages) {
        sp.edit().putString("favs", android.text.TextUtils.join(",", packages)).apply();
    }

    /** Returns true when the package was added, false when it was removed. */
    public boolean toggleFavourite(String pkg) {
        java.util.List<String> packages = favourites();
        boolean added;
        if (packages.contains(pkg)) {
            packages.remove(pkg);
            added = false;
        } else {
            packages.add(pkg);
            added = true;
        }
        setFavourites(packages);
        return added;
    }

    // ------------------------------------------------------------------ appearance

    /** Tile scale: 0.82 small, 1.0 medium, 1.18 large, 1.34 extra large. */
    public float tileScale() {
        return sp.getFloat("tileScale", 1.0f);
    }

    public void setTileScale(float scale) {
        sp.edit().putFloat("tileScale", scale).apply();
    }

    /** Corner radius of every tile, in dp. 0 = square. Default matches the "Rounded" preset. */
    public int cornerDp() {
        return sp.getInt("cornerDp", 18);
    }

    public void setCornerDp(int dp) {
        sp.edit().putInt("cornerDp", dp).apply();
    }

    // ------------------------------------------------------------------ manual ordering

    /** Newline separated wallpaper keys in the order the user arranged them. */
    public java.util.List<String> wallpaperOrder() {
        return splitLines(sp.getString("wpOrder", ""));
    }

    public void setWallpaperOrder(java.util.List<String> keys) {
        sp.edit().putString("wpOrder", android.text.TextUtils.join("\n", keys)).apply();
    }

    /** Newline separated flattened component names, in the order the user arranged them. */
    public java.util.List<String> appOrder() {
        return splitLines(sp.getString("appOrder", ""));
    }

    public void setAppOrder(java.util.List<String> components) {
        sp.edit().putString("appOrder", android.text.TextUtils.join("\n", components)).apply();
    }

    /** How dark the layer over the wallpaper is: 0 = none, 1 = fully black. */
    public float dimLevel() {
        return sp.getFloat("dim", 0.55f);
    }

    public void setDimLevel(float level) {
        sp.edit().putFloat("dim", level).apply();
    }

    /** Newline separated keys of the bottom tile row, in the user's order. */
    public java.util.List<String> quickOrder() {
        return splitLines(sp.getString("quickOrder", ""));
    }

    public void setQuickOrder(java.util.List<String> keys) {
        sp.edit().putString("quickOrder", android.text.TextUtils.join("\n", keys)).apply();
    }

    private java.util.List<String> splitLines(String raw) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
