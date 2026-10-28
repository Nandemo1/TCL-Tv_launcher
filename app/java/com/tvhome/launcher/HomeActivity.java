package com.tvhome.launcher;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The home screen: clock, weather, a compact cricket scorecard widget, an editable
 * favourites row, a Spotify card and a single horizontal dock (All Apps, Wallpapers,
 * Play Store, Live TV, HDMI 1, HDMI 2). Every network call and package query runs on
 * the shared {@link Async} pool, never on the UI thread.
 */
public class HomeActivity extends Activity {

    private static final long WEATHER_MAX_AGE_MS = 30L * 60L * 1000L;
    /** CricAPI's free tier allows 100 hits a day, so 5 minutes keeps a full day under it. */
    private static final long CRICKET_REFRESH_MS = 5L * 60L * 1000L;
    private static final long NET_REFRESH_MS = 60L * 1000L;
    private static final String SPOTIFY_PACKAGE = "com.spotify.tv.android";
    private static final String PLAY_STORE_PACKAGE = "com.android.vending";
    private static final int MAX_HDMI = 2;

    /** TV input list is static hardware state, so it is scanned once per process. */
    private static List<TvInputInfo> inputCache;
    private static volatile boolean inputsScanned;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH);

    private Prefs prefs;
    private ImageView wallpaper;
    private View scrim;
    private LinearLayout favouritesRow;
    private LinearLayout dockRow;
    private TextView clock;
    private TextView date;
    private ImageView weatherIcon;
    private TextView weatherTemp;
    private TextView weatherDesc;
    private TextView weatherCity;
    private LinearLayout tickerRow;
    private TextView tickerBadge;
    private LinearLayout tickerChips;
    private TextView netDot;
    private TextView netText;
    private View spotifyCard;
    private View spotifyOpen;
    private TextView spotifyTitle;
    private TextView spotifySub;
    private TextView spotifyPrev;
    private TextView spotifyPlay;
    private TextView spotifyNext;
    private MediaSessionManager mediaSessionManager;
    private MediaController mediaController;
    private MediaController.Callback mediaCallback;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private View liveTvTile;

    private String favouritesSignature;
    private String lastCricketText;
    private final java.util.Map<String, Object[]> iconCache = new java.util.HashMap<>();
    private final java.util.concurrent.ExecutorService iconPool =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    private final java.util.concurrent.atomic.AtomicBoolean cricketFetching =
            new java.util.concurrent.atomic.AtomicBoolean();
    private long cricketBlockedUntil;
    private float tileScale = 1f;
    private int cornerDp = 16;
    private int movingFavourite = -1;
    private int swallowUpCode = android.view.KeyEvent.KEYCODE_UNKNOWN;
    private boolean settingsHandled;
    private boolean scanningInputs;
    private long okDownAt;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            clock.setText(timeFormat.format(now));
            date.setText(dateFormat.format(now));
            ui.postDelayed(this, 15000L);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather(true);
            ui.postDelayed(this, WEATHER_MAX_AGE_MS);
        }
    };

    private final Runnable cricketTick = new Runnable() {
        @Override
        public void run() {
            refreshCricket();
            ui.postDelayed(this, CRICKET_REFRESH_MS);
        }
    };

    private final Runnable netTick = new Runnable() {
        @Override
        public void run() {
            refreshNet();
            ui.postDelayed(this, NET_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The TV's DNS may hand out IPv6 addresses its router can't route; force IPv4 for
        // all sockets so cricket/weather fetches don't die in connect timeouts.
        System.setProperty("java.net.preferIPv4Stack", "true");
        prefs = new Prefs(this);
        tileScale = prefs.tileScale();
        cornerDp = prefs.cornerDp();
        setContentView(R.layout.activity_home);

        wallpaper = findViewById(R.id.wallpaper);
        scrim = findViewById(R.id.scrim);
        favouritesRow = findViewById(R.id.favRow);
        dockRow = findViewById(R.id.dockRow);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        weatherIcon = findViewById(R.id.weatherIcon);
        weatherTemp = findViewById(R.id.weatherTemp);
        weatherDesc = findViewById(R.id.weatherDesc);
        weatherCity = findViewById(R.id.weatherCity);
        tickerRow = findViewById(R.id.tickerRow);
        tickerBadge = findViewById(R.id.tickerBadge);
        tickerChips = findViewById(R.id.tickerChips);
        netDot = findViewById(R.id.netDot);
        netText = findViewById(R.id.netText);
        spotifyCard = findViewById(R.id.spotifyCard);
        spotifyTitle = findViewById(R.id.spotifyTitle);
        spotifySub = findViewById(R.id.spotifySub);

        buildFavourites();
        buildDock();
        buildSpotifyCard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tileScale = prefs.tileScale();
        cornerDp = prefs.cornerDp();
        swallowUpCode = android.view.KeyEvent.KEYCODE_UNKNOWN;
        applyWallpaper();
        buildDock();
        buildFavourites();
        showCachedWeather();
        refreshWeather(false);
        refreshCricket();
        refreshNet();
        restoreFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        ui.removeCallbacks(weatherTick);
        ui.postDelayed(weatherTick, WEATHER_MAX_AGE_MS);
        ui.removeCallbacks(cricketTick);
        ui.post(cricketTick);
        ui.removeCallbacks(netTick);
        ui.post(netTick);
        refreshMediaSessions();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(weatherTick);
        ui.removeCallbacks(cricketTick);
        ui.removeCallbacks(netTick);
        if (mediaController != null && mediaCallback != null) {
            mediaController.unregisterCallback(mediaCallback);
        }
        mediaController = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveFocus();
    }

    /**
     * Home screen: BACK never leaves the launcher. Only arrange mode consumes BACK
     * (in {@link #handleMoveKey}); here it simply does nothing.
     */
    @Override
    public void onBackPressed() {
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getCurrentFocus() == null) {
            View first = favouritesRow.getChildCount() > 0 ? favouritesRow.getChildAt(0)
                    : dockRow.getChildCount() > 0 ? dockRow.getChildAt(0) : null;
            if (first != null) {
                first.requestFocus();
            }
        }
    }

    /**
     * Primary key path. Only the settings key (176), arrange mode and the three known
     * vendor app buttons are intercepted; BACK and D-pad flow through to the framework
     * so focus navigation stays native.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int code = event.getKeyCode();

        // The window can start with nothing focused until the first real key arrives.
        if (getCurrentFocus() == null && isDirectional(code)) {
            View first = favouritesRow.getChildCount() > 0 ? favouritesRow.getChildAt(0)
                    : dockRow.getChildCount() > 0 ? dockRow.getChildAt(0) : null;
            if (first != null) {
                first.requestFocus();
            }
        }

        // Drop only the UP of the exact key that ended arrange mode, never any other event.
        if (swallowUpCode != android.view.KeyEvent.KEYCODE_UNKNOWN) {
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                int swallow = swallowUpCode;
                swallowUpCode = android.view.KeyEvent.KEYCODE_UNKNOWN;
                if (swallow == code) {
                    return true;
                }
            }
        }

        // Key 82 (menu) toggles the glass settings panel; key 176 (settings)
        // is left to the system, which opens the TCL settings app.
        if (isSettingsToggleKey(code)) {
            settingsHandled = true;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    open(SettingsActivity.class);
                }
            } else {
                settingsHandled = false;
            }
            // Both actions are consumed so the system never opens its own panel.
            return true;
        }

        if (movingFavourite >= 0) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                return handleMoveKey(code, event.getRepeatCount());
            }
            return true;
        }

        boolean okKey = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || code == android.view.KeyEvent.KEYCODE_ENTER;
        if (okKey) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                okDownAt = android.os.SystemClock.uptimeMillis();
            } else if (event.getAction() == android.view.KeyEvent.ACTION_UP && okDownAt > 0) {
                long held = android.os.SystemClock.uptimeMillis() - okDownAt;
                okDownAt = 0;
                if (held >= 600) {
                    int favourite = focusedIndex(favouritesRow);
                    if (favourite >= 0) {
                        startMoveFavourite(favourite);
                        return true;
                    }
                }
            }
        }

        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            android.util.Log.i("TVHOME_KEY", "key=" + code + " scan=" + event.getScanCode());
            View focused = getCurrentFocus();
            android.util.Log.i("TVHOME_FOCUS", "key=" + code + " focus="
                    + (focused == null ? "null" : focused.getClass().getSimpleName()));
            String pkg = packageForKey(code, event.getScanCode());
            if (pkg != null) {
                launchPackage(pkg);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** Fallback path in case anything bypasses dispatchKeyEvent; the flag prevents double opens. */
    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent event) {
        if (isSettingsToggleKey(code)
                && !settingsHandled && event.getRepeatCount() == 0) {
            open(SettingsActivity.class);
            return true;
        }
        return super.onKeyDown(code, event);
    }

    private boolean isSettingsToggleKey(int code) {
        // Only MENU (82) toggles the panel. KEYCODE_SETTINGS (176) passes through
        // so the system opens the TCL settings app.
        return code == android.view.KeyEvent.KEYCODE_MENU;
    }

    // ------------------------------------------------------------------ manual arranging

    private void startMoveFavourite(int index) {
        movingFavourite = index;
        toast("Arranging — ◀ ▶ to move, OK to save");
    }

    private boolean isDirectional(int code) {
        return code == android.view.KeyEvent.KEYCODE_DPAD_UP
                || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                || code == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || code == android.view.KeyEvent.KEYCODE_ENTER;
    }

    private int focusedIndex(LinearLayout row) {
        if (row == null) {
            return -1;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).isFocused()) {
                return i;
            }
        }
        return -1;
    }

    private boolean handleMoveKey(int code, int repeatCount) {
        // Holding a key repeats it: only the first press counts, otherwise the mode
        // would end while the button is still held down.
        if (repeatCount > 0) {
            return true;
        }
        switch (code) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                shift(-1);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                shift(1);
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
            case android.view.KeyEvent.KEYCODE_BACK:
                swallowUpCode = code;
                finishMove();
                return true;
            default:
                return true;
        }
    }

    private void shift(int delta) {
        if (movingFavourite < 0) {
            return;
        }
        List<String> packages = prefs.favourites();
        int to = movingFavourite + delta;
        if (to < 0 || to >= packages.size()) {
            return;
        }
        Collections.swap(packages, movingFavourite, to);
        prefs.setFavourites(packages);
        movingFavourite = to;
        favouritesSignature = null;
        buildFavourites();
        if (to < favouritesRow.getChildCount()) {
            favouritesRow.getChildAt(to).requestFocus();
        }
    }

    private void finishMove() {
        movingFavourite = -1;
        toast("Favourites saved");
    }

    // ------------------------------------------------------------------ vendor remote bridge

    /**
     * The remote reports vendor keycodes (4062 Netflix, 4065 YouTube, 4099 Prime) whose
     * scan codes match the TCL_* entries in the key layout. Only these three are touched;
     * every other hardware shortcut passes through to the system.
     */
    private String packageForKey(int code, int scan) {
        if (matches(code, scan, 4062, 242, 758)) {
            return "com.netflix.ninja";
        }
        if (matches(code, scan, 4065, 245, 569)) {
            return "com.google.android.youtube.tv";
        }
        if (matches(code, scan, 4099, 752)) {
            return "com.amazon.amazonvideo.livingroom";
        }
        return null;
    }

    private boolean matches(int code, int scan, int... values) {
        for (int value : values) {
            if (code == value || scan == value) {
                return true;
            }
        }
        return false;
    }

    private boolean launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(pkg);
        }
        if (intent == null) {
            return false;
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ favourites

    private void buildFavourites() {
        List<String> packages = prefs.favourites();
        String signature = TextUtils.join(",", packages);
        boolean changed = !signature.equals(favouritesSignature);
        favouritesSignature = signature;
        if (!changed && favouritesRow.getChildCount() > 0) {
            return;
        }

        favouritesRow.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();

        for (int i = 0; i < packages.size() && i < Prefs.MAX_FAVOURITES; i++) {
            final int index = i;
            View tile = favouriteTile(inflater, packages.get(i));
            tile.setLongClickable(true);
            tile.setOnLongClickListener(v -> {
                startMoveFavourite(index);
                return true;
            });
            favouritesRow.addView(tile, tileParams());
        }
        // Sits at the end of the row, so it only shows once you scroll right.
        favouritesRow.addView(addAppsTile(), tileParams());
    }

    /** Tile skeleton is instant; labels and icons resolve on the background pool. */
    private View favouriteTile(LayoutInflater inflater, final String pkg) {
        View tile = inflater.inflate(R.layout.item_tile, favouritesRow, false);
        ImageView iconView = tile.findViewById(R.id.tileIcon);
        TextView labelView = tile.findViewById(R.id.tileLabel);
        tile.setBackground(Ui.card(this, cornerDp));
        Ui.zoom(tile);
        iconView.setLayoutParams(new LinearLayout.LayoutParams(
                dp(88 * tileScale), dp(88 * tileScale)));
        labelView.setTextSize(14f * tileScale);
        labelView.setText(pkg);
        iconView.setImageResource(android.R.drawable.sym_def_app_icon);
        iconView.setAlpha(0.3f);
        labelView.setAlpha(0.55f);
        tile.setTag(pkg);

        Object[] cached;
        synchronized (iconCache) {
            cached = iconCache.get(pkg);
        }
        if (cached != null) {
            applyTileData(pkg, (Drawable) cached[0], (String) cached[1]);
        }

        tile.setOnClickListener(v -> Async.NET.execute(() -> {
            ComponentName component = AppRepo.resolve(getPackageManager(), pkg);
            if (component == null) {
                toast("Not installed: " + pkg);
                return;
            }
            AppRepo.launch(HomeActivity.this, component);
        }));

        iconPool.execute(() -> {
            try {
                Object[] done;
                synchronized (iconCache) {
                    done = iconCache.get(pkg);
                }
                if (done == null) {
                    PackageManager pm = getPackageManager();
                    ComponentName component = AppRepo.resolve(pm, pkg);
                    String label = pkg;
                    Drawable icon = null;
                    if (component != null) {
                        try {
                            icon = pm.getActivityIcon(component);
                        } catch (Exception e) {
                            try {
                                icon = pm.getApplicationIcon(pkg);
                            } catch (Exception ignored) {
                                // placeholder stays
                            }
                        }
                        try {
                            CharSequence appLabel = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                            if (appLabel != null && appLabel.length() > 0) {
                                label = appLabel.toString();
                            }
                        } catch (Exception ignored) {
                            // keep the package name as a fallback label
                        }
                    }
                    done = new Object[] { icon, label };
                    synchronized (iconCache) {
                        iconCache.put(pkg, done);
                    }
                }
                final Drawable drawable = (Drawable) done[0];
                final String title = (String) done[1];
                android.util.Log.i("TVHOME_ICON", "fill " + pkg + " -> icon=" + (drawable != null) + ", label=" + title);
                ui.post(() -> applyTileData(pkg, drawable, title));
            } catch (Exception e) {
                android.util.Log.e("TVHOME_ICON", "icon load failed for " + pkg, e);
            }
        });
        return tile;
    }

    /** Applies resolved icon/label to whichever tile currently holds this package tag. */
    private void applyTileData(String pkg, Drawable drawable, String title) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        View tile = favouritesRow.findViewWithTag(pkg);
        if (tile == null) {
            return;
        }
        ImageView iconView = tile.findViewById(R.id.tileIcon);
        TextView labelView = tile.findViewById(R.id.tileLabel);
        labelView.setText(title);
        if (drawable != null) {
            iconView.setImageDrawable(drawable);
            iconView.setAlpha(1f);
            labelView.setAlpha(1f);
        }
    }

    private View addAppsTile() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Ui.card(this, cornerDp));
        Ui.zoom(box);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        TextView plus = new TextView(this);
        plus.setText("+");
        plus.setTextSize(38f * tileScale);
        plus.setGravity(Gravity.CENTER);
        plus.setTextColor(getColor(R.color.green));
        plus.setIncludeFontPadding(false);
        box.addView(plus);

        TextView label = new TextView(this);
        label.setText("Add apps");
        label.setTextSize(13f * tileScale);
        label.setTextColor(getColor(R.color.text));
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(2);
        box.addView(label, labelParams);

        box.setOnClickListener(v -> open(PickAppsActivity.class));
        return box;
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(136 * tileScale), dp(138 * tileScale));
        params.setMarginEnd(dp(12 * tileScale));
        return params;
    }

    // ------------------------------------------------------------------ dock

    /**
     * One horizontal line, strict order: All Apps, Wallpapers, Play Store, Live TV,
     * HDMI 1, HDMI 2, Settings. Inputs are scanned once off the main thread and appended.
     */
    private void buildDock() {
        dockRow.removeAllViews();
        dockRow.addView(actionTile("All Apps", R.drawable.ic_apps,
                () -> open(AllAppsActivity.class)));
        dockRow.addView(actionTile("Wallpapers", R.drawable.ic_wallpaper,
                () -> open(WallpaperActivity.class)));
        dockRow.addView(playStoreTile());

        String tuner = cachedTunerInputId();
        liveTvTile = actionTile("Live TV", (Drawable) null,
                tuner != null ? () -> openInput(tuner) : this::launchLiveTv);
        dockRow.addView(liveTvTile);

        if (inputsScanned) {
            appendHdmiTiles();
        } else {
            scanInputsAsync();
        }
        dockRow.addView(actionTile("Settings", R.drawable.ic_settings,
                () -> open(SettingsActivity.class)));
        applyDim();
    }

    private void applyDim() {
        if (scrim != null) {
            scrim.setAlpha(prefs.dimLevel());
        }
    }

    private void scanInputsAsync() {
        if (scanningInputs || inputsScanned) {
            return;
        }
        scanningInputs = true;
        Async.NET.execute(() -> {
            TvInputManager manager =
                    (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> scanned = new ArrayList<>();
            if (manager != null) {
                try {
                    List<TvInputInfo> inputs = manager.getTvInputList();
                    if (inputs != null) {
                        scanned.addAll(inputs);
                    }
                } catch (Exception ignored) {
                    // dock stays without HDMI tiles
                }
            }
            final String tuner = findTunerInputId(scanned);
            synchronized (HomeActivity.class) {
                inputCache = scanned;
                inputsScanned = true;
                scanningInputs = false;
            }
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (tuner != null && liveTvTile != null) {
                    liveTvTile.setOnClickListener(v -> openInput(tuner));
                }
                if (hdmiInputs(scanned).isEmpty()) {
                    return;
                }
                int focused = indexIn(dockRow, getCurrentFocus());
                dockRow.removeAllViews();
                buildDock();
                if (focused >= 0 && focused < dockRow.getChildCount()) {
                    dockRow.getChildAt(focused).requestFocus();
                }
            });
        });
    }

    /** The built-in tuner, "Live TV", is the TCL TDTV input or any TYPE_TUNER input. */
    private String findTunerInputId(List<TvInputInfo> inputs) {
        for (TvInputInfo info : inputs) {
            if (info.getType() == TvInputInfo.TYPE_TUNER) {
                return info.getId();
            }
            if (info.getServiceInfo() != null
                    && info.getServiceInfo().name != null
                    && info.getServiceInfo().name.contains("TDTVInputService")) {
                return info.getId();
            }
        }
        for (TvInputInfo info : inputs) {
            if (info.getServiceInfo() != null
                    && "com.tcl.channelplus".equals(info.getServiceInfo().packageName)) {
                return info.getId();
            }
        }
        return null;
    }

    private String cachedTunerInputId() {
        return inputCache == null ? null : findTunerInputId(inputCache);
    }

    /** HDMI inputs only, sorted numerically so HDMI 1 comes before HDMI 2. */
    private List<TvInputInfo> hdmiInputs(List<TvInputInfo> inputs) {
        List<TvInputInfo> hdmi = new ArrayList<>();
        for (TvInputInfo info : inputs) {
            if (info != null && info.getType() == TvInputInfo.TYPE_HDMI) {
                hdmi.add(info);
            }
        }
        Collections.sort(hdmi, (a, b) -> {
            int left = portNumber(a);
            int right = portNumber(b);
            if (left != right) {
                return left - right;
            }
            return a.getId().compareTo(b.getId());
        });
        return hdmi;
    }

    private void appendHdmiTiles() {
        int added = 0;
        for (TvInputInfo info : hdmiInputs(inputCache)) {
            if (added >= MAX_HDMI) {
                break;
            }
            String label = null;
            Drawable icon = null;
            try {
                CharSequence text = info.loadLabel(this);
                if (text != null && text.length() > 0) {
                    label = text.toString();
                }
                icon = info.loadIcon(this);
            } catch (Exception ignored) {
                // fall back to the numbered default
            }
            int port = portNumber(info);
            String title = label != null ? label : "HDMI " + (port < 99 ? port : added + 1);
            final String inputId = info.getId();
            dockRow.addView(actionTile(title, icon, () -> openInput(inputId)));
            added++;
        }
    }

    /** Trailing number in an input label, so "HDMI 2" sorts after "HDMI 1". */
    private int portNumber(TvInputInfo info) {
        try {
            CharSequence text = info.loadLabel(this);
            if (text != null) {
                Matcher matcher = Pattern.compile("(\\d+)\\s*$").matcher(text.toString());
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return 99;
    }

    private void openInput(String inputId) {
        Intent intent = new Intent(this, TvInputActivity.class);
        intent.putExtra(TvInputActivity.EXTRA_INPUT_ID, inputId);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void launchLiveTv() {
        if (!launchPackage("com.tcl.tvinput") && !launchPackage("com.tcl.channelplus")) {
            toast("Live TV not found");
        }
    }

    private View playStoreTile() {
        LinearLayout box = baseTile(this::launchPlayStore);
        final ImageView icon = new ImageView(this);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                dp(18 * tileScale), dp(18 * tileScale));
        iconParams.setMarginEnd(dp(7));
        box.addView(icon, iconParams);
        TextView label = new TextView(this);
        label.setText("Play Store");
        label.setTextSize(12f * tileScale);
        label.setTextColor(getColor(R.color.text));
        box.addView(label);

        Async.NET.execute(() -> {
            Drawable drawable = null;
            try {
                drawable = getPackageManager().getApplicationIcon(PLAY_STORE_PACKAGE);
            } catch (Exception ignored) {
                // text-only tile
            }
            final Drawable iconDrawable = drawable;
            ui.post(() -> {
                if (!isFinishing() && !isDestroyed() && box.isAttachedToWindow()
                        && iconDrawable != null) {
                    icon.setImageDrawable(iconDrawable);
                }
            });
        });
        return box;
    }

    private void launchPlayStore() {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(PLAY_STORE_PACKAGE);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(PLAY_STORE_PACKAGE);
        }
        if (intent == null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=" + PLAY_STORE_PACKAGE))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Exception ignored) {
                // fall through to the error toast
            }
            toast("Play Store not found");
            return;
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("Can't open Play Store");
        }
    }

    /** Shared box for the dock tiles: focusable glass card with the tvOS zoom. */
    private LinearLayout baseTile(Runnable onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setBackground(Ui.card(this, smallRadius()));
        Ui.zoom(box);
        box.setFocusable(true);
        box.setFocusableInTouchMode(true);
        box.setClickable(true);
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        box.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(106 * tileScale), dp(62 * tileScale));
        params.setMarginStart(dp(8));
        box.setLayoutParams(params);
        return box;
    }

    private View actionTile(String text, int iconResource, Runnable onClick) {
        Drawable drawable = null;
        try {
            drawable = getDrawable(iconResource);
        } catch (Exception ignored) {
            // text-only tile
        }
        return actionTile(text, drawable, onClick);
    }

    private View actionTile(String text, Drawable iconDrawable, Runnable onClick) {
        LinearLayout box = baseTile(onClick);
        if (iconDrawable != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(iconDrawable);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    dp(18 * tileScale), dp(18 * tileScale));
            iconParams.setMarginEnd(dp(7));
            box.addView(icon, iconParams);
        }
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12f * tileScale);
        label.setTextColor(getColor(R.color.text));
        box.addView(label);
        return box;
    }

    private void open(Class<?> activity) {
        startActivity(new Intent(this, activity));
        overridePendingTransition(0, 0);
    }

    // ------------------------------------------------------------------ spotify / media card

    private void buildSpotifyCard() {
        spotifyOpen = findViewById(R.id.spotifyOpen);
        Ui.zoom(spotifyOpen);
        spotifyOpen.setOnClickListener(v -> launchSpotify());
        spotifyPrev = findViewById(R.id.spotifyPrev);
        spotifyPlay = findViewById(R.id.spotifyPlay);
        spotifyNext = findViewById(R.id.spotifyNext);
        if (spotifyPrev != null) {
            Ui.zoom(spotifyPrev);
            spotifyPrev.setOnClickListener(v -> {
                if (mediaController != null) {
                    mediaController.getTransportControls().skipToPrevious();
                }
            });
        }
        if (spotifyPlay != null) {
            Ui.zoom(spotifyPlay);
            spotifyPlay.setOnClickListener(v -> {
                if (mediaController == null) {
                    launchSpotify();
                    return;
                }
                PlaybackState state = mediaController.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    mediaController.getTransportControls().pause();
                } else {
                    mediaController.getTransportControls().play();
                }
            });
        }
        if (spotifyNext != null) {
            Ui.zoom(spotifyNext);
            spotifyNext.setOnClickListener(v -> {
                if (mediaController != null) {
                    mediaController.getTransportControls().skipToNext();
                }
            });
        }
        spotifyTitle.setText("Spotify");
        spotifySub.setText("Not playing — press OK to open");
        startMediaTracking();
    }

    /** Media sessions need a granted notification listener to be visible; without it the card stays static. */
    private void startMediaTracking() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        mediaSessionManager =
                (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) {
            return;
        }
        final ComponentName nl = new ComponentName(this, MediaListenerService.class);
        sessionsListener = sessions -> {
            MediaController chosen = null;
            if (sessions != null) {
                for (MediaController session : sessions) {
                    if (session == null) {
                        continue;
                    }
                    if (SPOTIFY_PACKAGE.equals(session.getPackageName())) {
                        chosen = session;
                        break;
                    }
                }
                if (chosen == null && !sessions.isEmpty()) {
                    chosen = sessions.get(0);
                }
            }
            final MediaController pick = chosen;
            ui.post(() -> attachMediaController(pick));
        };
        mediaSessionManager.addOnActiveSessionsChangedListener(sessionsListener, nl, ui);
    }

    /** Re-sync after returning from another app (Spotify may have started or stopped). */
    private void refreshMediaSessions() {
        if (mediaSessionManager == null || Build.VERSION.SDK_INT < 26) {
            return;
        }
        final ComponentName nl = new ComponentName(this, MediaListenerService.class);
        List<MediaController> sessions;
        try {
            sessions = mediaSessionManager.getActiveSessions(nl);
        } catch (Exception e) {
            return;
        }
        MediaController chosen = null;
        if (sessions != null) {
            for (MediaController session : sessions) {
                if (session == null) {
                    continue;
                }
                if (SPOTIFY_PACKAGE.equals(session.getPackageName())) {
                    chosen = session;
                    break;
                }
            }
            if (chosen == null && !sessions.isEmpty()) {
                chosen = sessions.get(0);
            }
        }
        final MediaController pick = chosen;
        ui.post(() -> attachMediaController(pick));
    }

    private void attachMediaController(MediaController controller) {
        if (mediaController != null && mediaCallback != null) {
            mediaController.unregisterCallback(mediaCallback);
        }
        mediaController = controller;
        if (controller == null) {
            spotifyTitle.setText("Spotify");
            spotifySub.setText("Not playing — press OK to open");
            if (spotifyPlay != null) {
                spotifyPlay.setText("▶");
            }
            return;
        }
        mediaCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                renderMediaMeta(metadata);
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                renderMediaState(state);
            }
        };
        controller.registerCallback(mediaCallback, ui);
        renderMediaMeta(controller.getMetadata());
        renderMediaState(controller.getPlaybackState());
    }

    private void renderMediaMeta(MediaMetadata metadata) {
        String title = metadata == null
                ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = metadata == null
                ? null : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(title)) {
            spotifyTitle.setText("Spotify");
            spotifySub.setText("Not playing — press OK to open");
        } else {
            spotifyTitle.setText(title);
            spotifySub.setText(TextUtils.isEmpty(artist) ? "Now playing" : artist);
        }
    }

    private void renderMediaState(PlaybackState state) {
        if (spotifyPlay == null) {
            return;
        }
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        spotifyPlay.setText(playing ? "⏸" : "▶");
    }

    private void launchSpotify() {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(SPOTIFY_PACKAGE);
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(SPOTIFY_PACKAGE);
        }
        if (intent == null) {
            toast("Spotify not installed");
            return;
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("Can't open Spotify");
        }
    }

    // ------------------------------------------------------------------ cricket widget

    private void refreshCricket() {
        if (!prefs.cricketOn()) {
            ui.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    tickerRow.setVisibility(View.GONE);
                }
            });
            return;
        }
        if (System.currentTimeMillis() < cricketBlockedUntil) {
            return; // daily quota exhausted; the chip already says so
        }
        if (!cricketFetching.compareAndSet(false, true)) {
            return; // a fetch is already in flight; don't double-spend the quota
        }
        final int feed = prefs.cricketFeed();
        Async.NET.execute(() -> {
            try {
                List<Cricket.Match> matches = feed == 0
                        ? Cricket.fetchLive()
                        : Cricket.fetchAll();
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String text = cricketWidgetText(matches);
                    if (text.equals(lastCricketText)) {
                        return;
                    }
                    lastCricketText = text;
                    renderCricketWidget(matches);
                });
            } catch (Exception e) {
                android.util.Log.e("TVHOME_CRICKET", "fetch failed", e);
                String message = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
                boolean quota = message.contains("hit")
                        || message.contains("blocked")
                        || message.contains("limit");
                if (quota) {
                    cricketBlockedUntil = nextQuotaReset();
                }
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    String state = quota ? "quota" : "error";
                    if (state.equals(lastCricketText)) {
                        return;
                    }
                    lastCricketText = state;
                    renderCricketError(quota
                            ? "Cricket daily limit reached — scores resume after midnight UTC"
                            : "Cricket feed unavailable — check the network connection");
                });
            } finally {
                cricketFetching.set(false);
            }
        });
    }

    /** CricAPI's free tier allows 100 hits a day; retry just after the next UTC midnight. */
    private long nextQuotaReset() {
        long now = System.currentTimeMillis();
        return now - (now % 86400000L) + 86400000L + 5L * 60L * 1000L;
    }

    private String cricketWidgetText(List<Cricket.Match> matches) {
        StringBuilder out = new StringBuilder();
        for (Cricket.Match match : matches) {
            out.append(match.chip).append('|').append(match.live).append(';');
        }
        return out.toString();
    }

    /** Up to two compact score chips: "PAK 180/4 (20)  vs  AUS 120/2 (14.2)  • LIVE". */
    private void renderCricketWidget(List<Cricket.Match> matches) {
        tickerRow.setVisibility(View.VISIBLE);
        String badge = Cricket.badgeFor(matches);
        tickerBadge.setText(badge);
        tickerBadge.setTextColor(getColor("LIVE".equals(badge)
                ? R.color.green : R.color.muted));
        tickerChips.removeAllViews();
        if (matches.isEmpty()) {
            tickerChips.addView(cricketChip(
                    "No international cricket right now — fixtures appear automatically", false));
            return;
        }
        for (Cricket.Match match : matches) {
            tickerChips.addView(cricketChip(
                    match.chip + (match.live ? "  • LIVE" : ""), match.live));
        }
    }

    private void renderCricketError(String message) {
        tickerRow.setVisibility(View.VISIBLE);
        tickerBadge.setText("CRICKET");
        tickerBadge.setTextColor(getColor(R.color.muted));
        tickerChips.removeAllViews();
        tickerChips.addView(cricketChip(message, false));
    }

    private View cricketChip(String text, boolean live) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(12f);
        chip.setSingleLine(true);
        chip.setIncludeFontPadding(false);
        chip.setGravity(Gravity.CENTER);
        chip.setFocusable(false);
        chip.setBackgroundResource(R.drawable.card_bg_sm);
        chip.setTextColor(getColor(live ? R.color.green : R.color.text));
        chip.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(10));
        chip.setLayoutParams(params);
        return chip;
    }

    // ------------------------------------------------------------------ network health

    private void refreshNet() {
        Async.NET.execute(() -> {
            try {
                final NetMonitor.Status status = NetMonitor.probe(HomeActivity.this);
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    netDot.setTextColor(NetMonitor.dotColor(status));
                    String latency = status.latencyMs >= 0 ? status.latencyMs + " ms" : "—";
                    netText.setText(status.online ? status.type + " · " + latency : "Offline");
                });
            } catch (Exception ignored) {
                // keep the previous reading
            }
        });
    }

    // ------------------------------------------------------------------ focus memory

    private void saveFocus() {
        View focus = getCurrentFocus();
        String key = "fav:0";
        if (focus != null) {
            if (focus == spotifyOpen || focus == spotifyPrev
                    || focus == spotifyPlay || focus == spotifyNext) {
                key = "spotify";
            } else {
                int index = indexIn(favouritesRow, focus);
                if (index >= 0) {
                    key = "fav:" + index;
                } else {
                    index = indexIn(dockRow, focus);
                    if (index >= 0) {
                        key = "dock:" + index;
                    }
                }
            }
        }
        prefs.setFocusKey(key);
    }

    private void restoreFocus() {
        String key = prefs.focusKey();
        if ("spotify".equals(key)) {
            spotifyOpen.post(spotifyOpen::requestFocus);
            return;
        }
        int colon = key == null ? -1 : key.indexOf(':');
        int index = -1;
        String row = "fav";
        if (colon >= 0) {
            row = key.substring(0, colon);
            try {
                index = Integer.parseInt(key.substring(colon + 1));
            } catch (NumberFormatException e) {
                index = -1;
            }
        }
        LinearLayout target = "fav".equals(row) ? favouritesRow
                : "dock".equals(row) ? dockRow : null;
        if (target == null || target.getChildCount() == 0) {
            target = favouritesRow;
        }
        View child = null;
        if (index >= 0 && index < target.getChildCount()) {
            child = target.getChildAt(index);
        } else if (target.getChildCount() > 0) {
            child = target.getChildAt(0);
        }
        if (child != null) {
            final View restore = child;
            restore.post(restore::requestFocus);
        }
    }

    private int indexIn(LinearLayout row, View view) {
        if (row == null || view == null) {
            return -1;
        }
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i) == view) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ wallpaper / weather

    private void applyWallpaper() {
        Bitmap bitmap = WallpaperStore.load(this, prefs.wallpaperKey(), 1280);
        if (bitmap != null) {
            wallpaper.setImageBitmap(bitmap);
        } else {
            wallpaper.setImageResource(android.R.color.black);
        }
    }

    private void showCachedWeather() {
        weatherCity.setText(prefs.city());
        String cached = prefs.weatherCache();
        if (cached == null) {
            return;
        }
        try {
            render(Weather.parse(cached));
        } catch (Exception ignored) {
            // keep placeholders
        }
    }

    private void refreshWeather(boolean force) {
        long age = System.currentTimeMillis() - prefs.weatherAt();
        if (!force && age < WEATHER_MAX_AGE_MS) {
            return;
        }
        final String city = prefs.city();
        final float lat = prefs.lat();
        final float lon = prefs.lon();
        Async.NET.execute(() -> {
            try {
                String json = Weather.fetch(lat, lon);
                final Weather weather = Weather.parse(json);
                prefs.setWeather(json, System.currentTimeMillis());
                ui.post(() -> {
                    if (!isFinishing() && !isDestroyed() && city.equals(prefs.city())) {
                        render(weather);
                    }
                });
            } catch (Exception ignored) {
                // offline: cached values stay on screen
            }
        });
    }

    private void render(Weather weather) {
        weatherTemp.setText(weather.temp);
        weatherDesc.setText(weather.detail.isEmpty()
                ? weather.desc
                : weather.desc + "  ·  " + weather.detail);
        weatherCity.setText(prefs.city());
        weatherIcon.setImageResource(weather.icon);
    }

    // ------------------------------------------------------------------ helpers

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    /** Smaller radius for the compact action tiles. */
    private int smallRadius() {
        return Math.max(4, cornerDp - 4);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
