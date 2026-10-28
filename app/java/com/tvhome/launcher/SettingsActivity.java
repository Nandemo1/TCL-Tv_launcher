package com.tvhome.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Weather city, tile size, corners, cricket ticker, dimmer, network status and system entry. */
public class SettingsActivity extends Activity {

    private static final String[][] CITIES = {
            {"Hyderabad, Sindh", "25.3960", "68.3578"},
            {"Karachi", "24.8607", "67.0011"},
            {"Lahore", "31.5204", "74.3587"},
            {"Islamabad", "33.6844", "73.0479"},
            {"Multan", "30.1575", "71.5249"},
            {"Peshawar", "34.0151", "71.5785"},
            {"Quetta", "30.1798", "66.9750"},
            {"Sukkur", "27.7052", "68.8574"},
    };

    private static final String[] SIZE_LABELS = {"Small", "Medium", "Large", "Extra large"};
    private static final float[] SIZE_VALUES = {0.82f, 1.0f, 1.18f, 1.34f};
    private static final String[] CORNER_LABELS = {"Square", "Slight", "Rounded", "Pill"};
    private static final int[] CORNER_VALUES = {0, 8, 18, 30};
    private static final String[] DIM_LABELS = {"35%", "45%", "55%", "65%", "75%"};
    private static final float[] DIM_VALUES = {0.35f, 0.45f, 0.55f, 0.65f, 0.75f};

    private Prefs prefs;
    private LinearLayout cityRow;
    private LinearLayout sizeRow;
    private LinearLayout cornerRow;
    private LinearLayout tickerToggleRow;
    private LinearLayout feedRow;
    private LinearLayout dimRow;
    private View dimPreviewScrim;
    private TextView netStatus;
    private final List<TextView> chips = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = new Prefs(this);
        cityRow = findViewById(R.id.cityRow);
        sizeRow = findViewById(R.id.sizeRow);
        cornerRow = findViewById(R.id.cornerRow);
        tickerToggleRow = findViewById(R.id.tickerToggleRow);
        feedRow = findViewById(R.id.feedRow);
        dimRow = findViewById(R.id.dimRow);
        dimPreviewScrim = findViewById(R.id.dimPreviewScrim);
        netStatus = findViewById(R.id.netStatus);

        TextView sub = findViewById(R.id.sub);
        sub.setText("Weather, tile style, cricket ticker, dimmer and system access");

        ImageView previewWall = findViewById(R.id.dimPreviewWall);
        previewWall.setImageBitmap(WallpaperStore.load(this, prefs.wallpaperKey(), 640));
        dimPreviewScrim.setAlpha(prefs.dimLevel());

        buildCities();
        buildSizeChips();
        buildCornerChips();
        buildTickerChips();
        buildFeedChips();
        buildDimChips();

        TextView refresh = findViewById(R.id.btnRefresh);
        Ui.zoom(refresh);
        refresh.setOnClickListener(v -> {
            prefs.invalidateWeather();
            Toast.makeText(this, "Weather will refresh on the home screen", Toast.LENGTH_SHORT).show();
            finish();
        });

        TextView wallpaper = findViewById(R.id.btnWallpaper);
        Ui.zoom(wallpaper);
        wallpaper.setOnClickListener(v -> {
            startActivity(new Intent(this, WallpaperActivity.class));
            overridePendingTransition(0, 0);
        });

        TextView system = findViewById(R.id.btnSystemSettings);
        Ui.zoom(system);
        system.setOnClickListener(v -> openSystemSettings());

        TextView info = findViewById(R.id.info);
        info.setText("TV Home 1.0\n\n"
                + "Wallpaper folder: /sdcard/" + WallpaperStore.USER_DIR + "\n"
                + "Copy any JPG or PNG there and it shows up in the wallpaper picker.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNetStatus();
        restoreSettingsFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveSettingsFocus();
    }

    /** BACK closes the panel smoothly, remembering the focused item for next time. */
    @Override
    public void onBackPressed() {
        saveSettingsFocus();
        finish();
        overridePendingTransition(0, 0);
    }

    /** Key 82 (menu) closes this panel; key 176 (settings) passes through to the TCL settings app. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int code = event.getKeyCode();
        if (code == android.view.KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0) {
                onBackPressed();
            }
            // Consume both actions so the system never opens its own menu.
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------ cricket ticker

    private void buildTickerChips() {
        tickerToggleRow.removeAllViews();
        boolean on = prefs.cricketOn();
        TextView chipOn = settingsChip(on ? "● Ticker on" : "Ticker on", on);
        chipOn.setOnClickListener(v -> {
            prefs.setCricketOn(true);
            buildTickerChips();
            Toast.makeText(this, "Cricket ticker on", Toast.LENGTH_SHORT).show();
        });
        TextView chipOff = settingsChip(!on ? "● Ticker off" : "Ticker off", !on);
        chipOff.setOnClickListener(v -> {
            prefs.setCricketOn(false);
            buildTickerChips();
            Toast.makeText(this, "Cricket ticker off", Toast.LENGTH_SHORT).show();
        });
        tickerToggleRow.addView(chipOn);
        tickerToggleRow.addView(chipOff);
    }

    private void buildFeedChips() {
        feedRow.removeAllViews();
        int feed = prefs.cricketFeed();
        TextView chipLive = settingsChip(feed == 0 ? "● Live matches" : "Live matches", feed == 0);
        chipLive.setOnClickListener(v -> {
            prefs.setCricketFeed(0);
            buildFeedChips();
            Toast.makeText(this, "Feed: live matches only", Toast.LENGTH_SHORT).show();
        });
        TextView chipAll = settingsChip(feed == 1 ? "● All matches" : "All matches", feed == 1);
        chipAll.setOnClickListener(v -> {
            prefs.setCricketFeed(1);
            buildFeedChips();
            Toast.makeText(this, "Feed: all matches", Toast.LENGTH_SHORT).show();
        });
        feedRow.addView(chipLive);
        feedRow.addView(chipAll);
    }

    // ------------------------------------------------------------------ background dimmer

    private void buildDimChips() {
        dimRow.removeAllViews();
        float current = prefs.dimLevel();
        for (int i = 0; i < DIM_LABELS.length; i++) {
            final String label = DIM_LABELS[i];
            final float value = DIM_VALUES[i];
            boolean selected = Math.abs(current - value) < 0.01f;
            TextView chip = settingsChip(selected ? "● " + label : label, selected);
            chip.setOnClickListener(v -> {
                prefs.setDimLevel(value);
                dimPreviewScrim.setAlpha(value);
                buildDimChips();
                Toast.makeText(this, "Dimmer: " + label, Toast.LENGTH_SHORT).show();
            });
            dimRow.addView(chip);
        }
    }

    // ------------------------------------------------------------------ system settings

    private void openSystemSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
            return;
        } catch (Exception ignored) {
            // fall back to the TV settings package below
        }
        Intent tv = getPackageManager().getLeanbackLaunchIntentForPackage("com.android.tv.settings");
        if (tv == null) {
            tv = getPackageManager().getLaunchIntentForPackage("com.android.tv.settings");
        }
        if (tv == null) {
            Toast.makeText(this, "System settings not found", Toast.LENGTH_SHORT).show();
            return;
        }
        tv.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(tv);
        } catch (Exception e) {
            Toast.makeText(this, "Can't open system settings", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ network status

    private void updateNetStatus() {
        netStatus.setText("Checking network…");
        Async.NET.execute(() -> {
            try {
                final NetMonitor.Status status = NetMonitor.probe(SettingsActivity.this);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String latency = status.latencyMs >= 0 ? status.latencyMs + " ms" : "unreachable";
                    String state = status.online ? "Connected" : "Offline";
                    netStatus.setText(state + " · " + status.type + " · latency " + latency);
                });
            } catch (Exception ignored) {
                // keep "checking…" state
            }
        });
    }

    // ------------------------------------------------------------------ appearance chips

    private void buildSizeChips() {
        sizeRow.removeAllViews();
        float current = prefs.tileScale();
        for (int i = 0; i < SIZE_LABELS.length; i++) {
            final String label = SIZE_LABELS[i];
            final float value = SIZE_VALUES[i];
            boolean selected = Math.abs(current - value) < 0.01f;
            TextView chip = settingsChip(selected ? "● " + label : label, selected);
            chip.setOnClickListener(v -> {
                prefs.setTileScale(value);
                buildSizeChips();
                Toast.makeText(this, "Tile size: " + label, Toast.LENGTH_SHORT).show();
            });
            sizeRow.addView(chip);
        }
    }

    private void buildCornerChips() {
        cornerRow.removeAllViews();
        int current = prefs.cornerDp();
        for (int i = 0; i < CORNER_LABELS.length; i++) {
            final String label = CORNER_LABELS[i];
            final int value = CORNER_VALUES[i];
            boolean selected = current == value;
            TextView chip = settingsChip(selected ? "● " + label : label, selected);
            chip.setOnClickListener(v -> {
                prefs.setCornerDp(value);
                buildCornerChips();
                Toast.makeText(this, "Corners: " + label, Toast.LENGTH_SHORT).show();
            });
            cornerRow.addView(chip);
        }
    }

    /** Shared chip builder for every option row, with the tvOS focus zoom applied. */
    private TextView settingsChip(String text, boolean selected) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12f);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.card_bg_sm);
        Ui.zoom(chip);
        chip.setFocusable(true);
        chip.setFocusableInTouchMode(true);
        chip.setClickable(true);
        chip.setTextColor(getColor(selected ? R.color.green : R.color.text));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(132), dp(46));
        params.setMarginEnd(dp(8));
        params.bottomMargin = dp(8);
        chip.setLayoutParams(params);
        return chip;
    }

    private void buildCities() {
        cityRow.removeAllViews();
        chips.clear();
        String current = prefs.city();

        for (int start = 0; start < CITIES.length; start += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int index = start; index < Math.min(start + 2, CITIES.length); index++) {
                final String[] city = CITIES[index];
                final int focusIndex = index;
                boolean selected = city[0].equals(current);

                TextView chip = new TextView(this);
                chip.setText(selected ? "● " + city[0] : city[0]);
                chip.setTextSize(13f);
                chip.setGravity(Gravity.CENTER);
                chip.setBackgroundResource(R.drawable.card_bg_sm);
                Ui.zoom(chip);
                chip.setFocusable(true);
                chip.setFocusableInTouchMode(true);
                chip.setClickable(true);
                chip.setTextColor(getColor(selected ? R.color.green : R.color.text));
                chip.setOnClickListener(v -> {
                    prefs.setLocation(city[0], Float.parseFloat(city[1]), Float.parseFloat(city[2]));
                    prefs.invalidateWeather();
                    rebuildAndFocus(focusIndex);
                    Toast.makeText(this, "Weather city: " + city[0], Toast.LENGTH_SHORT).show();
                });

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(216), dp(52));
                params.setMarginEnd(dp(10));
                params.bottomMargin = dp(10);
                row.addView(chip, params);
                chips.add(chip);
            }
            cityRow.addView(row);
        }
    }

    private void rebuildAndFocus(int index) {
        buildCities();
        if (index >= 0 && index < chips.size()) {
            final TextView target = chips.get(index);
            target.post(target::requestFocus);
        }
    }

    // ------------------------------------------------------------------ focus memory

    private void saveSettingsFocus() {
        View focus = getCurrentFocus();
        if (focus instanceof TextView) {
            String text = ((TextView) focus).getText().toString();
            if (text.startsWith("● ")) {
                text = text.substring(2);
            }
            prefs.setSettingsFocus(text);
        }
    }

    private void restoreSettingsFocus() {
        String text = prefs.settingsFocus();
        if (text != null && !text.isEmpty()) {
            View root = findViewById(android.R.id.content);
            TextView match = findTextView(root, text);
            if (match != null) {
                match.post(match::requestFocus);
                return;
            }
        }
        if (!chips.isEmpty()) {
            TextView first = chips.get(0);
            first.post(first::requestFocus);
        }
    }

    private TextView findTextView(View view, String text) {
        if (view instanceof TextView) {
            String current = ((TextView) view).getText().toString();
            if (text.equals(current) || ("● " + text).equals(current)) {
                return (TextView) view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextView(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
