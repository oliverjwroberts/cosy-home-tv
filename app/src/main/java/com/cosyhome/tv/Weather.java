package com.cosyhome.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Current temperature and conditions from Open-Meteo (free, no key). The last reading is kept
 * so the clock has something to show straight after a restart, before the network is up.
 */
final class Weather {

    static final long REFRESH_MS = 30 * 60_000L;
    private static final String PREFS = "weather";

    private Weather() {
    }

    /** Show a cached reading only when it uses the requested temperature unit. */
    static String cached(Context ctx, boolean fahrenheit) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return unitMatches(prefs, fahrenheit) ? prefs.getString("text", "") : "";
    }

    static boolean stale(Context ctx, boolean fahrenheit) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !unitMatches(prefs, fahrenheit)
                || System.currentTimeMillis() - prefs.getLong("at", 0) > REFRESH_MS;
    }

    private static boolean unitMatches(SharedPreferences prefs, boolean fahrenheit) {
        // Old readings were Celsius and had no unit stored. Switching units forces a fresh fetch.
        return prefs.getString("unit", "celsius").equals(fahrenheit ? "fahrenheit" : "celsius");
    }

    /** Fetches on a background thread; {@code done} runs on the main thread, only on success. */
    static void fetch(Context ctx, double lat, double lon, boolean fahrenheit, Consumer<String> done) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // Two decimal places, about a kilometre: close enough for the weather.
                URL url = new URL(String.format(Locale.ROOT,
                        "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f"
                                + "&current=temperature_2m,weather_code&timezone=auto"
                                + (fahrenheit ? "&temperature_unit=fahrenheit" : ""), lat, lon));
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                if (conn.getResponseCode() != 200) return;
                JSONObject current = new JSONObject(read(conn.getInputStream())).getJSONObject("current");
                String text = Math.round(current.getDouble("temperature_2m"))
                        + (fahrenheit ? "°F " : "°C ")
                        + describe(current.getInt("weather_code"));
                SharedPreferences.Editor e = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
                e.putString("text", text).putString("unit", fahrenheit ? "fahrenheit" : "celsius")
                        .putLong("at", System.currentTimeMillis()).apply();
                main.post(() -> done.accept(text));
            } catch (Exception ignored) {
                // Offline or a bad reply: keep showing the last reading and try again next time.
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "weather").start();
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream is = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mostly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Light rain";
            case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm and hail";
            default: return "";
        }
    }
}
