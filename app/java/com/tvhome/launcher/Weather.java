package com.tvhome.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Current conditions from Open-Meteo (no key, no tracking). */
public final class Weather {

    public String temp = "--°";
    public String desc = "—";
    public String detail = "";
    public int icon = R.drawable.w_na;

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
