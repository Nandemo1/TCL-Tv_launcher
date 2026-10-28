package com.tvhome.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Current conditions from Open-Meteo (no key, no tracking). */
public final class Weather {

    public String temp = "--°";
    public String desc = "—";
    public String detail = "";
    public int icon = R.drawable.w_na;

    /** A geocoding hit: a place name plus the coordinates the forecast call needs. */
    public static final class Place {
        public final String name;
        public final String region;
        public final String country;
        public final float lat;
        public final float lon;

        Place(String name, String region, String country, float lat, float lon) {
            this.name = name;
            this.region = region;
            this.country = country;
            this.lat = lat;
            this.lon = lon;
        }

        /** "Springfield, Illinois, United States", so same-name towns stay tellable apart. */
        public String label() {
            StringBuilder out = new StringBuilder(name);
            if (region != null && region.length() > 0) {
                out.append(", ").append(region);
            }
            if (country != null && country.length() > 0) {
                out.append(", ").append(country);
            }
            return out.toString();
        }
    }

    /** Open-Meteo's free geocoder: no key and no account, and it covers the whole world. */
    public static List<Place> geocode(String query) throws IOException, JSONException {
        List<Place> places = new ArrayList<>();
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() == 0) {
            return places;
        }
        String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                + URLEncoder.encode(trimmed, "UTF-8") + "&count=20&language=en&format=json";
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(9000);
        conn.setReadTimeout(9000);
        conn.setRequestProperty("User-Agent", "tvhome/1.0");
        String body;
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            body = out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }

        JSONArray results = new JSONObject(body).optJSONArray("results");
        if (results == null) {
            return places;
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.optJSONObject(i);
            if (item == null || item.optString("name", "").length() == 0) {
                continue;
            }
            places.add(new Place(
                    item.optString("name", ""),
                    item.optString("admin1", ""),
                    item.optString("country", ""),
                    (float) item.optDouble("latitude", 0.0),
                    (float) item.optDouble("longitude", 0.0)));
        }
        return places;
    }

    public static String fetch(float lat, float lon) throws IOException {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,relative_humidity_2m"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&forecast_days=1&timezone=auto",
                lat, lon);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(9000);
        conn.setReadTimeout(9000);
        conn.setRequestProperty("User-Agent", "tvhome/1.0");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    public static Weather parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject current = root.getJSONObject("current");

        double temperature = current.getDouble("temperature_2m");
        int code = current.optInt("weather_code", -1);
        int humidity = current.optInt("relative_humidity_2m", -1);

        double high = Double.NaN;
        double low = Double.NaN;
        JSONObject daily = root.optJSONObject("daily");
        if (daily != null) {
            JSONArray max = daily.optJSONArray("temperature_2m_max");
            JSONArray min = daily.optJSONArray("temperature_2m_min");
            if (max != null && max.length() > 0) {
                high = max.optDouble(0);
            }
            if (min != null && min.length() > 0) {
                low = min.optDouble(0);
            }
        }

        Weather weather = new Weather();
        weather.temp = Math.round(temperature) + "°";
        weather.desc = label(code);
        weather.icon = icon(code);

        StringBuilder detail = new StringBuilder();
        if (!Double.isNaN(high)) {
            detail.append("H ").append(Math.round(high)).append("°");
        }
        if (!Double.isNaN(low)) {
            if (detail.length() > 0) {
                detail.append("  ");
            }
            detail.append("L ").append(Math.round(low)).append("°");
        }
        if (humidity >= 0) {
            if (detail.length() > 0) {
                detail.append("  ·  ");
            }
            detail.append(humidity).append('%');
        }
        weather.detail = detail.toString();
        return weather;
    }

    private static String label(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "—";
    }

    private static int icon(int code) {
        if (code == 0) return R.drawable.w_sunny;
        if (code == 1 || code == 2) return R.drawable.w_partly;
        if (code == 3) return R.drawable.w_cloudy;
        if (code == 45 || code == 48) return R.drawable.w_fog;
        if (code >= 51 && code <= 57) return R.drawable.w_drizzle;
        if (code >= 61 && code <= 67) return R.drawable.w_rain;
        if (code >= 71 && code <= 77) return R.drawable.w_snow;
        if (code >= 80 && code <= 82) return R.drawable.w_rain;
        if (code == 85 || code == 86) return R.drawable.w_snow;
        if (code >= 95) return R.drawable.w_thunder;
        return R.drawable.w_na;
    }
}
