package com.tvhome.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Worldwide weather city picker on top of Open-Meteo's free geocoder, so the home screen can
 * point at any place on earth instead of only the eight preset cities.
 */
public class CitySearchActivity extends Activity {

    /** Ready before anyone types, because hunting for the remote keyboard is the slow path. */
    private static final String[][] QUICK = {
            {"New York", "40.7128", "-74.0060"},
            {"Los Angeles", "34.0522", "-118.2437"},
            {"Chicago", "41.8781", "-87.6298"},
            {"Toronto", "43.6532", "-79.3832"},
            {"Mexico City", "19.4326", "-99.1332"},
            {"Sao Paulo", "-23.5505", "-46.6333"},
            {"London", "51.5074", "-0.1278"},
            {"Paris", "48.8566", "2.3522"},
            {"Berlin", "52.5200", "13.4050"},
            {"Madrid", "40.4168", "-3.7038"},
            {"Rome", "41.9028", "12.4964"},
            {"Moscow", "55.7558", "37.6173"},
            {"Istanbul", "41.0082", "28.9784"},
            {"Dubai", "25.2048", "55.2708"},
            {"Riyadh", "24.7136", "46.6753"},
            {"Cairo", "30.0444", "31.2357"},
            {"Karachi", "24.8607", "67.0011"},
            {"Lahore", "31.5204", "74.3587"},
            {"Islamabad", "33.6844", "73.0479"},
            {"Delhi", "28.6139", "77.2090"},
            {"Mumbai", "19.0760", "72.8777"},
            {"Bengaluru", "12.9716", "77.5946"},
            {"Kolkata", "22.5726", "88.3639"},
            {"Hyderabad, India", "17.3850", "78.4867"},
            {"Beijing", "39.9042", "116.4074"},
            {"Shanghai", "31.2304", "121.4737"},
            {"Guangzhou", "23.1291", "113.2644"},
            {"Shenzhen", "22.5431", "114.0579"},
            {"Tokyo", "35.6762", "139.6503"},
            {"Seoul", "37.5665", "126.9780"},
            {"Singapore", "1.3521", "103.8198"},
            {"Sydney", "-33.8688", "151.2093"},
            {"Melbourne", "-37.8136", "144.9631"},
            {"Perth", "-31.9505", "115.8605"},
            {"Auckland", "-36.8485", "174.7633"},
            {"Johannesburg", "-26.2041", "28.0473"},
    };

    private static final int QUICK_PER_ROW = 4;
    private static final long SEARCH_DEBOUNCE_MS = 420L;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Prefs prefs;
    private EditText input;
    private TextView status;
    private TextView matchesLabel;
    private LinearLayout quickBox;
    private LinearLayout resultsBox;

    /** Bumped per query so a slow response cannot overwrite a newer one. */
    private int generation;
    private Runnable pendingSearch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_city);
        prefs = new Prefs(this);

        input = findViewById(R.id.cityInput);
        status = findViewById(R.id.cityStatus);
        matchesLabel = findViewById(R.id.cityMatchesLabel);
        quickBox = findViewById(R.id.cityQuick);
        resultsBox = findViewById(R.id.cityResults);

        buildQuickPicks();

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSearch(s.toString());
            }
        });

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                searchNow(input.getText().toString());
                return true;
            }
            return false;
        });

        input.requestFocus();
        input.post(this::showKeyboard);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingSearch != null) {
            ui.removeCallbacks(pendingSearch);
        }
    }

    // ------------------------------------------------------------------ quick picks

    private void buildQuickPicks() {
        quickBox.removeAllViews();
        for (int start = 0; start < QUICK.length; start += QUICK_PER_ROW) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int index = start; index < Math.min(start + QUICK_PER_ROW, QUICK.length); index++) {
                final String name = QUICK[index][0];
                final float lat = Float.parseFloat(QUICK[index][1]);
                final float lon = Float.parseFloat(QUICK[index][2]);

                TextView chip = new TextView(this);
                chip.setText(name);
                chip.setTextSize(12f);
                chip.setSingleLine(true);
                chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
                chip.setIncludeFontPadding(false);
                chip.setGravity(Gravity.CENTER);
                chip.setBackgroundResource(R.drawable.card_bg_sm);
                Ui.zoom(chip);
                chip.setFocusable(true);
                chip.setFocusableInTouchMode(true);
                chip.setClickable(true);
                chip.setTextColor(getColor(name.equals(prefs.city()) ? R.color.green : R.color.text));
                chip.setOnClickListener(v -> apply(name, lat, lon));

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, dp(44), 1f);
                params.setMarginEnd(dp(8));
                params.bottomMargin = dp(8);
                row.addView(chip, params);
            }
            quickBox.addView(row);
        }
    }

    // ------------------------------------------------------------------ search

    private void scheduleSearch(final String query) {
        if (pendingSearch != null) {
            ui.removeCallbacks(pendingSearch);
        }
        pendingSearch = new Runnable() {
            @Override
            public void run() {
                searchNow(query);
            }
        };
        ui.postDelayed(pendingSearch, SEARCH_DEBOUNCE_MS);
    }

    private void searchNow(String query) {
        final String trimmed = query == null ? "" : query.trim();
        if (trimmed.length() < 2) {
            generation++;
            resultsBox.removeAllViews();
            matchesLabel.setVisibility(View.GONE);
            status.setText("");
            return;
        }
        status.setText("Searching for \"" + trimmed + "\"...");
        final int token = ++generation;
        Async.NET.execute(() -> {
            try {
                final List<Weather.Place> places = Weather.geocode(trimmed);
                ui.post(() -> render(token, trimmed, places));
            } catch (Exception e) {
                android.util.Log.e("TVHOME_CITY", "geocode failed for " + trimmed, e);
                ui.post(() -> {
                    if (token != generation) {
                        return;
                    }
                    status.setText("Search needs the network. Try again, or pick a quick city.");
                });
            }
        });
    }

    private void render(int token, String query, List<Weather.Place> places) {
        if (token != generation || isFinishing() || isDestroyed()) {
            return;
        }
        resultsBox.removeAllViews();
        if (places.isEmpty()) {
            matchesLabel.setVisibility(View.GONE);
            status.setText("Nothing found for \"" + query + "\". Try a shorter spelling.");
            return;
        }
        status.setText("");
        matchesLabel.setVisibility(View.VISIBLE);
        for (Weather.Place place : places) {
            TextView row = new TextView(this);
            row.setText(place.label());
            row.setTextSize(14f);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setIncludeFontPadding(false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.card_bg_sm);
            Ui.zoom(row);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setTextColor(getColor(R.color.text));
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setOnClickListener(v -> apply(place.label(), place.lat, place.lon));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            params.bottomMargin = dp(8);
            resultsBox.addView(row, params);
        }
        if (resultsBox.getChildCount() > 0) {
            final View first = resultsBox.getChildAt(0);
            first.post(first::requestFocus);
        }
    }

    private void apply(String name, float lat, float lon) {
        prefs.setLocation(name, lat, lon);
        prefs.invalidateWeather();
        Toast.makeText(this, "Weather city: " + name, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
}
