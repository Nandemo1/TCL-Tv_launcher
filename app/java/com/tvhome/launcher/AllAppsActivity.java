package com.tvhome.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every launchable app on the device in a D-pad grid.
 * Long-press OK to arrange: arrows move the tile, OK saves the order.
 */
public class AllAppsActivity extends Activity {

    private static final int COLUMNS = 6;

    private LinearLayout grid;
    private TextView loading;

    private final List<AppRepo.App> items = new ArrayList<>();
    private int movingIndex = -1;
    private boolean swallowNextUp;
    private long okDownAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_allapps);
        grid = findViewById(R.id.grid);
        loading = findViewById(R.id.loading);
        AppRepo.loadAsync(this, this::populate);
    }

    private void populate(List<AppRepo.App> apps) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        items.clear();
        items.addAll(applyOrder(apps));
        render();
    }

    /** User's saved order first, then everything else alphabetically. */
    private List<AppRepo.App> applyOrder(List<AppRepo.App> apps) {
        Prefs prefs = new Prefs(this);
        List<String> saved = prefs.appOrder();
        List<AppRepo.App> ordered = new ArrayList<>();
        for (String component : saved) {
            for (AppRepo.App app : apps) {
                if (app.component.flattenToString().equals(component) && !ordered.contains(app)) {
                    ordered.add(app);
                }
            }
        }
        for (AppRepo.App app : apps) {
            if (!ordered.contains(app)) {
                ordered.add(app);
            }
        }
        return ordered;
    }

    private void render() {
        grid.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        float scale = Ui.scale(this);
        int radius = Ui.radius(this);

        for (int start = 0; start < items.size(); start += COLUMNS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int index = start; index < Math.min(start + COLUMNS, items.size()); index++) {
                final int position = index;
                final AppRepo.App app = items.get(index);
                View tile = inflater.inflate(R.layout.item_grid, row, false);
                ImageView icon = tile.findViewById(R.id.tileIcon);
                TextView label = tile.findViewById(R.id.tileLabel);

                tile.setBackground(Ui.card(this, radius));
                Ui.zoom(tile);
                icon.setLayoutParams(new LinearLayout.LayoutParams(
                        dp(62 * scale), dp(62 * scale)));
                label.setTextSize(12f * scale);
                label.setText(app.label);
                if (app.icon != null) {
                    icon.setImageDrawable(app.icon);
                } else {
                    icon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
                tile.setOnClickListener(v -> AppRepo.launch(AllAppsActivity.this, app.component));
                tile.setLongClickable(true);
                tile.setOnLongClickListener(v -> {
                    movingIndex = position;
                    Toast.makeText(this, "Arranging — arrows move, OK saves",
                            Toast.LENGTH_SHORT).show();
                    return true;
                });

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        dp(132 * scale), ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMarginEnd(dp(10 * scale));
                params.bottomMargin = dp(10 * scale);
                row.addView(tile, params);
            }
            grid.addView(row);
        }

        boolean empty = items.isEmpty();
        loading.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            loading.setText("No launchable apps found");
            return;
        }
        if (grid.getChildCount() > 0) {
            View firstRow = grid.getChildAt(0);
            if (firstRow instanceof LinearLayout && ((LinearLayout) firstRow).getChildCount() > 0) {
                ((LinearLayout) firstRow).getChildAt(0).requestFocus();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (swallowNextUp && event.getAction() == KeyEvent.ACTION_UP) {
            swallowNextUp = false;
            return true;
        }
        if (movingIndex >= 0) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return true;
            }
            int code = event.getKeyCode();
            if (event.getRepeatCount() > 0) {
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                swap(-1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                swap(1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                swap(-COLUMNS);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                swap(COLUMNS);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                    || code == KeyEvent.KEYCODE_BACK) {
                saveOrder();
                return true;
            }
            return true;
        }
        boolean okKey = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER
                || event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
        if (okKey) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                okDownAt = android.os.SystemClock.uptimeMillis();
            } else if (event.getAction() == KeyEvent.ACTION_UP && okDownAt > 0) {
                long held = android.os.SystemClock.uptimeMillis() - okDownAt;
                okDownAt = 0;
                if (held >= 600) {
                    int index = focusedFlatIndex();
                    if (index >= 0) {
                        movingIndex = index;
                        Toast.makeText(this, "Arranging — arrows move, OK saves",
                                Toast.LENGTH_SHORT).show();
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private int focusedFlatIndex() {
        for (int row = 0; row < grid.getChildCount(); row++) {
            View child = grid.getChildAt(row);
            if (child instanceof LinearLayout) {
                LinearLayout linear = (LinearLayout) child;
                for (int column = 0; column < linear.getChildCount(); column++) {
                    if (linear.getChildAt(column).isFocused()) {
                        return row * COLUMNS + column;
                    }
                }
            }
        }
        return -1;
    }

    private void swap(int delta) {
        int to = movingIndex + delta;
        if (to < 0 || to >= items.size()) {
            return;
        }
        Collections.swap(items, movingIndex, to);
        movingIndex = to;
        render();
        focusIndex(movingIndex);
    }

    private void saveOrder() {
        List<String> components = new ArrayList<>();
        for (AppRepo.App app : items) {
            components.add(app.component.flattenToString());
        }
        new Prefs(this).setAppOrder(components);
        movingIndex = -1;
        swallowNextUp = true;
        Toast.makeText(this, "App order saved", Toast.LENGTH_SHORT).show();
    }

    private void focusIndex(int index) {
        int rowIndex = index / COLUMNS;
        int inRow = index % COLUMNS;
        if (rowIndex < grid.getChildCount()) {
            View row = grid.getChildAt(rowIndex);
            if (row instanceof LinearLayout && inRow < ((LinearLayout) row).getChildCount()) {
                ((LinearLayout) row).getChildAt(inRow).requestFocus();
            }
        }
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
