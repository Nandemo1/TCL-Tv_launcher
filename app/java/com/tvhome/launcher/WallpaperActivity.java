package com.tvhome.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** All wallpapers: the bundled set plus anything the user drops into Pictures/TVHome. */
public class WallpaperActivity extends Activity {

    private static final int COLUMNS = 4;
    private static final int REQUEST_STORAGE = 71;

    private Prefs prefs;
    private LinearLayout grid;
    private TextView hint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper);
        prefs = new Prefs(this);
        grid = findViewById(R.id.wallpapers);
        hint = findViewById(R.id.hint);
        hint.setText("Add more: copy images into " + WallpaperStore.USER_DIR + " on this TV");
        requestStorageIfNeeded();
        populate();
    }

    private void requestStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQUEST_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        populate();
    }

    private void populate() {
        List<String> keys = WallpaperStore.allKeys(this);
        String current = prefs.wallpaperKey();
        LayoutInflater inflater = getLayoutInflater();
        grid.removeAllViews();

        for (int start = 0; start < keys.size(); start += COLUMNS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int index = start; index < Math.min(start + COLUMNS, keys.size()); index++) {
                final String key = keys.get(index);
                FrameLayout cell = (FrameLayout) inflater.inflate(R.layout.item_wall, row, false);
                ImageView image = cell.findViewById(R.id.wpImage);
                float scale = Ui.scale(this);
                cell.setBackground(Ui.card(this, Ui.radius(this)));
                Ui.zoom(cell);
                Bitmap bitmap = WallpaperStore.loadThumb(this, key);
                if (bitmap != null) {
                    image.setImageBitmap(bitmap);
                }
                TextView name = new TextView(this);
                name.setText(WallpaperStore.displayName(key));
                cell.setOnClickListener(v -> {
                    prefs.setWallpaperKey(key);
                    Toast.makeText(this, "Wallpaper applied", Toast.LENGTH_SHORT).show();
                    finish();
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        dp(216 * scale), dp(128 * scale));
                params.setMarginEnd(dp(12 * scale));
                params.bottomMargin = dp(12 * scale);
                row.addView(cell, params);
            }
            grid.addView(row);
        }

        if (grid.getChildCount() > 0) {
            View firstRow = grid.getChildAt(0);
            if (firstRow instanceof LinearLayout && ((LinearLayout) firstRow).getChildCount() > 0) {
                ((LinearLayout) firstRow).getChildAt(0).requestFocus();
            }
        }
        hint.append(current.startsWith("A") ? "" : "\nCurrent: " + WallpaperStore.displayName(current));
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }
}
