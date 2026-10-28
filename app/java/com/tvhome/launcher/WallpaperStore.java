package com.tvhome.launcher;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bundled wallpapers live in assets as "A0", "A1", ... and user wallpapers are referenced
 * by absolute path. Users can drop images into Pictures/TVHome on the TV at any time.
 */
public final class WallpaperStore {

    public static final String USER_DIR = "Pictures/TVHome";

    private static final String[] EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"};

    private WallpaperStore() {
    }

    public static int bundledCount(Context context) {
        AssetManager assets = context.getAssets();
        int count = 0;
        while (true) {
            try (InputStream ignored = assets.open("wallpapers/wp" + count + ".jpg")) {
                count++;
            } catch (IOException e) {
                break;
            }
        }
        return count;
    }

    public static List<String> allKeys(Context context) {
        List<String> keys = new ArrayList<>();
        int bundled = bundledCount(context);
        for (int i = 0; i < bundled; i++) {
            keys.add("A" + i);
        }
        for (File file : userFiles(context)) {
            if (keys.size() >= 42) {
                break;
            }
            keys.add(file.getAbsolutePath());
        }
        return applyOrder(context, keys);
    }

    /** Keeps the user's manual order, then appends anything that is new since they arranged it. */
    private static List<String> applyOrder(Context context, List<String> keys) {
        List<String> order = new Prefs(context).wallpaperOrder();
        if (order.isEmpty()) {
            return keys;
        }
        List<String> result = new ArrayList<>();
        for (String key : order) {
            if (keys.contains(key) && !result.contains(key)) {
                result.add(key);
            }
        }
        for (String key : keys) {
            if (!result.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    public static List<File> userFiles(Context context) {
        Set<File> unique = new LinkedHashSet<>();
        File external = Environment.getExternalStorageDirectory();
        String[] dirs = {
                USER_DIR,
                "Pictures",
                "Download",
                "Android/data/" + context.getPackageName() + "/files/wallpapers",
        };
        for (String dir : dirs) {
            scan(new File(external, dir), unique, 0);
        }
        return new ArrayList<>(unique);
    }

    private static void scan(File dir, Set<File> out, int depth) {
        if (out.size() >= 60 || depth > 2 || dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (out.size() >= 60) {
                return;
            }
            if (file.isDirectory()) {
                scan(file, out, depth + 1);
                continue;
            }
            String name = file.getName().toLowerCase(Locale.US);
            boolean image = false;
            for (String ext : EXTENSIONS) {
                if (name.endsWith(ext)) {
                    image = true;
                    break;
                }
            }
            if (image && file.length() > 4096 && file.length() < 24L * 1024 * 1024) {
                out.add(file);
            }
        }
    }

    public static String displayName(String key) {
        if (key.startsWith("A")) {
            return "Built-in " + (Integer.parseInt(key.substring(1)) + 1);
        }
        return new File(key).getName();
    }

    public static Bitmap load(Context context, String key, int targetWidth) {
        if (key == null || key.isEmpty()) {
            key = "A0";
        }
        if (key.startsWith("A")) {
            int index = 0;
            try {
                index = Integer.parseInt(key.substring(1));
            } catch (NumberFormatException ignored) {
                // keep default
            }
            try (InputStream in = context.getAssets().open("wallpapers/wp" + index + ".jpg")) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                return BitmapFactory.decodeStream(in, null, options);
            } catch (Exception e) {
                return null;
            }
        }
        return decodeFile(new File(key), targetWidth);
    }

    public static Bitmap loadThumb(Context context, String key) {
        if (key.startsWith("A")) {
            int index = 0;
            try {
                index = Integer.parseInt(key.substring(1));
            } catch (NumberFormatException ignored) {
                // keep default
            }
            try (InputStream in = context.getAssets().open("wallpapers/tw" + index + ".png")) {
                return BitmapFactory.decodeStream(in, null, null);
            } catch (Exception e) {
                return null;
            }
        }
        return decodeFile(new File(key), 240);
    }

    public static Bitmap decodeFile(File file, int targetWidth) {
        if (!file.exists()) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        if (targetWidth > 0 && bounds.outWidth > 0) {
            while (bounds.outWidth / (sample * 2) >= targetWidth) {
                sample *= 2;
            }
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception e) {
            return null;
        }
    }
}
