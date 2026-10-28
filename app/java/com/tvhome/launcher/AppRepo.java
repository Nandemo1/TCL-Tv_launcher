package com.tvhome.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Discovers launchable apps once, off the main thread, and caches icons. */
public final class AppRepo {

    public static final class App {
        public final String label;
        public final ComponentName component;
        public Drawable icon;

        App(String label, ComponentName component) {
            this.label = label;
            this.component = component;
        }
    }

    public interface Listener {
        void onReady(List<App> apps);
    }

    private static final List<App> CACHE = new ArrayList<>();
    private static final List<Listener> PENDING = new CopyOnWriteArrayList<>();

    private static volatile boolean loaded;
    private static volatile boolean scanning;

    public static List<App> cached() {
        synchronized (CACHE) {
            return new ArrayList<>(CACHE);
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static void loadAsync(Context context, Listener listener) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());

        if (loaded) {
            if (listener != null) {
                final List<App> snapshot = cached();
                main.post(() -> listener.onReady(snapshot));
            }
            return;
        }
        if (listener != null) {
            PENDING.add(listener);
        }
        if (scanning) {
            return;
        }
        scanning = true;
        new Thread(() -> {
            List<App> apps = scan(app);
            synchronized (CACHE) {
                CACHE.clear();
                CACHE.addAll(apps);
            }
            loaded = true;
            scanning = false;
            final List<App> snapshot = cached();
            main.post(() -> {
                for (Listener pending : PENDING) {
                    pending.onReady(snapshot);
                }
                PENDING.clear();
            });
        }, "app-scan").start();
    }

    private static List<App> scan(Context context) {
        PackageManager pm = context.getPackageManager();
        List<App> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collect(context, pm, apps, seen, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(context, pm, apps, seen, Intent.CATEGORY_LAUNCHER);
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    private static void collect(Context context, PackageManager pm, List<App> apps,
                                Set<String> seen, String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(category);
        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentActivities(intent, 0);
        } catch (Exception e) {
            return;
        }
        if (resolved == null) {
            return;
        }
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null || info.activityInfo.name == null) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.equals(context.getPackageName())) {
                continue;
            }
            ComponentName component = new ComponentName(pkg, info.activityInfo.name);
            if (!seen.add(component.flattenToString())) {
                continue;
            }
            String label;
            try {
                label = String.valueOf(info.loadLabel(pm));
            } catch (Exception e) {
                label = pkg;
            }
            App app = new App(label, component);
            try {
                app.icon = info.loadIcon(pm);
            } catch (Exception ignored) {
                // fall through to application icon below
            }
            if (app.icon == null) {
                try {
                    app.icon = pm.getApplicationIcon(pkg);
                } catch (Exception ignored) {
                    // leave null, UI falls back to a system placeholder
                }
            }
            apps.add(app);
        }
    }

    /** First leanback, then plain launcher, then any launch intent for a package. */
    public static ComponentName resolve(PackageManager pm, String pkg) {
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(pkg);
            try {
                List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
                if (list != null && !list.isEmpty()) {
                    ResolveInfo info = list.get(0);
                    return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                }
            } catch (Exception ignored) {
                // try the next strategy
            }
        }
        try {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            return launch != null ? launch.getComponent() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void launch(Context context, ComponentName component) {
        if (component == null) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.setComponent(component);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            return;
        } catch (Exception ignored) {
            // retry below with the weakest possible intent
        }
        Intent fallback = new Intent(Intent.ACTION_MAIN);
        fallback.setComponent(component);
        fallback.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(fallback);
        } catch (Exception e) {
            Toast.makeText(context, "Can't open " + component.getPackageName(), Toast.LENGTH_SHORT).show();
        }
    }
}
