package com.tvhome.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Pick which apps live in the favourites row. OK toggles, BACK saves and returns. */
public class PickAppsActivity extends Activity {

    private static final int COLUMNS = 6;

    private Prefs prefs;
    private LinearLayout grid;
    private TextView loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_allapps);
        prefs = new Prefs(this);
        grid = findViewById(R.id.grid);
        loading = findViewById(R.id.loading);
        loading.setText("Loading apps…");

        TextView title = findViewById(R.id.title);
        title.setText("ADD TO FAVOURITES");

        AppRepo.loadAsync(this, this::populate);
    }

    private void populate(List<AppRepo.App> apps) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        grid.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();

        for (int start = 0; start < apps.size(); start += COLUMNS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int index = start; index < Math.min(start + COLUMNS, apps.size()); index++) {
                final AppRepo.App app = apps.get(index);
                final String pkg = app.component.getPackageName();

                View tile = inflater.inflate(R.layout.item_grid, row, false);
                ImageView icon = tile.findViewById(R.id.tileIcon);
                final TextView label = tile.findViewById(R.id.tileLabel);
                float scale = Ui.scale(this);
                tile.setBackground(Ui.card(this, Ui.radius(this)));
                Ui.zoom(tile);
                icon.setLayoutParams(new LinearLayout.LayoutParams(
                        dp(62 * scale), dp(62 * scale)));
                label.setTextSize(12f * scale);
                if (app.icon != null) {
                    icon.setImageDrawable(app.icon);
                } else {
                    icon.setImageResource(android.R.drawable.sym_def_app_icon);
                }
                style(label, pkg, app.label);

                tile.setOnClickListener(v -> {
                    if (!prefs.favourites().contains(pkg)
                            && prefs.favourites().size() >= Prefs.MAX_FAVOURITES) {
                        Toast.makeText(this, "Favourites are full — " + Prefs.MAX_FAVOURITES
                                + " max. Remove one first.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean added = prefs.toggleFavourite(pkg);
                    style(label, pkg, app.label);
                    Toast.makeText(this,
                            (added ? "Added to favourites: " : "Removed: ") + app.label,
                            Toast.LENGTH_SHORT).show();
                });

                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        dp(132 * scale), ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMarginEnd(dp(10 * scale));
                params.bottomMargin = dp(10 * scale);
                row.addView(tile, params);
            }
            grid.addView(row);
        }

        loading.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
        if (apps.isEmpty()) {
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

    private void style(TextView view, String pkg, String text) {
        boolean favourite = prefs.favourites().contains(pkg);
        view.setText(favourite ? "✓ " + text : text);
        view.setTextColor(getColor(favourite ? R.color.green : R.color.text));
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
