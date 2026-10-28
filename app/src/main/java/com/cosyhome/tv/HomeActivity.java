package com.cosyhome.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ChangedPackages;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Cosy Home: a clock over a valley that follows the sun, a dock of six apps, and on Down a
 * frosted panel with every app and the TV's inputs.
 */
public class HomeActivity extends Activity implements SceneView.Listener {

    private static final long MINUTE_MS = 60_000L;
    private static final int GRID_COLUMNS = 6;
    /** Dock tiles rest at this opacity until focused, as in the mockup. */
    private static final float DOCK_REST_ALPHA = 0.62f;
    /** OLED care: the clock layer moves through these dp offsets, one step a minute. */
    private static final int[][] SHIFT_DP = {{0, 0}, {3, 2}, {5, -1}, {2, -4}, {-2, -3}, {-4, 1}, {-1, 4}};

    private SceneView scene;
    private View homeLayer, appsLayer;
    private TextView clock, date, weather, headerTime, focusedName;
    private LinearLayout dock, inputs;
    private GridLayout grid;
    private GlassPanel appsPanel, inputsPanel;

    private List<String> dockApps, pinnedApps;
    private Set<String> hiddenApps;
    private String liveTvPkg, usbPkg, ambilightAction, place;
    private double lat, lon;
    private boolean located;
    private float density;

    private boolean appsOpen;
    /** The package sequence the tiles were built at, or -1 before the first build. */
    private int packageSequence = -1;
    /** Survives the activity being recreated while an app is open; resets only with the process. */
    private static int lastDock = 0;
    private static int lastGrid = 0;
    /** Preview a phase from adb: `am start -n com.cosyhome.tv/.HomeActivity --es phase day` ("auto" clears it). */
    private static SceneView.Phase forcedPhase;
    private int shiftStep;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            tick();
            shiftClockLayer();
            // Land just after the next minute boundary so the clock never shows the old minute.
            long now = System.currentTimeMillis();
            handler.postDelayed(this, MINUTE_MS - now % MINUTE_MS + 200);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            Weather.fetch(HomeActivity.this, lat, lon, text -> showWeather(text));
            handler.postDelayed(this, Weather.REFRESH_MS);
        }
    };
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Updates send REMOVED then ADDED; wait for the new APK instead of dropping the tile.
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            loadApps();
            restoreFocus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;

        // Built from cosy.defaults.properties and cosy.properties, see app/build.gradle.
        dockApps = list(R.string.dock);
        pinnedApps = list(R.string.pinned);
        hiddenApps = new HashSet<>(list(R.string.hidden));
        liveTvPkg = getString(R.string.live_tv_package);
        usbPkg = getString(R.string.usb_package);
        ambilightAction = getString(R.string.ambilight_action);
        place = getString(R.string.location_name);
        try {
            lat = Double.parseDouble(getString(R.string.location_latitude));
            lon = Double.parseDouble(getString(R.string.location_longitude));
            located = true;
        } catch (NumberFormatException e) {
            located = false;   // no location set: fixed phase times and no weather
        }

        scene = findViewById(R.id.scene);
        homeLayer = findViewById(R.id.homeLayer);
        appsLayer = findViewById(R.id.appsLayer);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        weather = findViewById(R.id.weather);
        headerTime = findViewById(R.id.headerTime);
        focusedName = findViewById(R.id.focusedName);
        dock = findViewById(R.id.dock);
        inputs = findViewById(R.id.inputs);
        grid = findViewById(R.id.appsGrid);
        appsPanel = findViewById(R.id.appsPanel);
        inputsPanel = findViewById(R.id.inputsPanel);
        scene.setListener(this);

        findViewById(R.id.back).setOnClickListener(v -> closeApps());
        View ambilight = findViewById(R.id.ambilight);
        if (ambilightAction.isEmpty()
                || getPackageManager().resolveActivity(new Intent(ambilightAction), 0) == null) {
            ambilight.setVisibility(View.GONE);
        }
        ambilight.setOnClickListener(v -> open(new Intent(ambilightAction)));
        findViewById(R.id.settings).setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        // The header row is the top of the screen: Up goes nowhere rather than wrapping around.
        View.OnKeyListener topRow = (v, keyCode, e) ->
                e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP;
        findViewById(R.id.back).setOnKeyListener(topRow);
        findViewById(R.id.ambilight).setOnKeyListener(topRow);
        findViewById(R.id.settings).setOnKeyListener(topRow);

        weather.setText(weatherLine(Weather.cached(this)));
        readPhaseOverride(getIntent());
    }

    private static void readPhaseOverride(Intent intent) {
        String p = intent == null ? null : intent.getStringExtra("phase");
        if (p == null) return;
        try {
            forcedPhase = SceneView.Phase.valueOf(p.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            forcedPhase = null;   // "auto" or anything else: follow the sun again
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }
        // Between two resumes the package list almost never changes, so keep the tiles unless
        // PackageManager reports a change. It returns null when nothing changed.
        if (packageSequence < 0 || getPackageManager().getChangedPackages(packageSequence) != null) {
            loadApps();
        }
        buildInputs();
        minuteTick.run();
        if (located) handler.postDelayed(weatherTick, Weather.stale(this) ? 0 : Weather.REFRESH_MS);
        scene.setRunning(true);
        restoreFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(packageReceiver);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(weatherTick);
        scene.setRunning(false);
    }

    /** Out of sight behind an app: give the scene's 8 MB back while Netflix and friends need it. */
    @Override
    protected void onStop() {
        super.onStop();
        scene.release();
    }

    /** Home pressed while already home: back to the clock with the first dock app focused. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        readPhaseOverride(intent);
        if (intent == null || !intent.hasCategory(Intent.CATEGORY_HOME)) return;
        lastDock = 0;
        if (appsOpen) closeApps();
        else focusDock(0);
    }

    @Override
    public void onBackPressed() {
        if (appsOpen) closeApps();
        // On the clock screen Back goes nowhere: this is home.
    }

    // ---- scene, clock and weather -----------------------------------------------------------------

    @Override
    public void onSceneChanged() {
        SceneView.Phase p = scene.phase();
        int glassTint = p == SceneView.Phase.DAWN ? 0x571E1A3C
                : p == SceneView.Phase.DAY ? 0x6B0E222E
                : p == SceneView.Phase.DUSK ? 0x5C1E1028 : 0x6B0A0E1E;
        GlassPanel.setSource(scene.blurred(), glassTint);
        appsPanel.invalidate();
        inputsPanel.invalidate();
        int text = p == SceneView.Phase.DAY ? 0xFF12222C
                : p == SceneView.Phase.DAWN ? 0xFFFFF8F0
                : p == SceneView.Phase.DUSK ? 0xFFFFF4EA : 0xFFEEF0F8;
        clock.setTextColor(text);
        date.setTextColor(text);
        weather.setTextColor((text & 0x00FFFFFF) | 0xD1000000);
    }

    private void tick() {
        Date now = new Date();
        Locale locale = Locale.getDefault();
        String hm = new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", locale).format(now);
        clock.setText(hm);
        headerTime.setText(hm);
        date.setText(new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now));
        scene.setPhase(forcedPhase != null ? forcedPhase : phaseAt(now.getTime()));
    }

    /**
     * Dawn and dusk straddle the real sunrise and sunset for the configured place, or 07:00 and
     * 19:00 when no place is set.
     */
    private SceneView.Phase phaseAt(long now) {
        long rise, set;
        if (located) {
            long[] sun = Sun.riseSet(now, lat, lon);
            rise = sun[0];
            set = sun[1];
        } else {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(now);
            c.set(Calendar.HOUR_OF_DAY, 7);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            rise = c.getTimeInMillis();
            set = rise + 12 * 60 * MINUTE_MS;
        }
        if (now >= rise - 40 * MINUTE_MS && now < rise + 50 * MINUTE_MS) return SceneView.Phase.DAWN;
        if (now >= rise + 50 * MINUTE_MS && now < set - 50 * MINUTE_MS) return SceneView.Phase.DAY;
        if (now >= set - 50 * MINUTE_MS && now < set + 40 * MINUTE_MS) return SceneView.Phase.DUSK;
        return SceneView.Phase.NIGHT;
    }

    private void showWeather(String text) {
        weather.setText(weatherLine(text));
    }

    private String weatherLine(String reading) {
        if (!located || reading.isEmpty()) return place;
        return place.isEmpty() ? reading : place + " · " + reading;
    }

    private void shiftClockLayer() {
        shiftStep = (shiftStep + 1) % SHIFT_DP.length;
        homeLayer.animate()
                .translationX(SHIFT_DP[shiftStep][0] * density)
                .translationY(SHIFT_DP[shiftStep][1] * density)
                .setDuration(2000).start();
    }

    // ---- tiles ------------------------------------------------------------------------------------

    private void loadApps() {
        // Read before the query, so a package that changes during the build triggers another one.
        ChangedPackages changed = getPackageManager().getChangedPackages(Math.max(packageSequence, 0));
        if (changed != null) packageSequence = changed.getSequenceNumber();
        else if (packageSequence < 0) packageSequence = 0;
        // The dock and the first grid row are on screen at once: their banners are decoded now, the rest
        // on a background thread once the tiles are up.
        List<Apps.App> apps = Apps.load(this, dockApps, pinnedApps, hiddenApps, dockApps.size() + GRID_COLUMNS);
        dock.removeAllViews();
        grid.removeAllViews();

        int dockW = dp(115), dockH = dp(65), dockGap = dp(15);
        List<Apps.App> docked = new ArrayList<>();
        for (Apps.App a : apps) if (dockApps.contains(a.pkg)) docked.add(a);
        for (int i = 0; i < docked.size(); i++) {
            final int index = i;
            Tile t = new Tile(this, docked.get(i), DOCK_REST_ALPHA);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dockW, dockH);
            if (i > 0) lp.setMarginStart(dockGap);
            t.setOnTileFocused(tile -> lastDock = index);
            final boolean first = i == 0, last = i == docked.size() - 1;
            t.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        openApps();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return first;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return last;
                    default:
                        return false;
                }
            });
            dock.addView(t, lp);
        }

        int cellW = dp(120), cellH = dp(68), colGap = dp(16), rowGap = dp(14);
        for (int i = 0; i < apps.size(); i++) {
            final int index = i;
            Tile t = new Tile(this, apps.get(i), 1f);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellW;
            lp.height = cellH;
            lp.setMargins(0, 0, i % GRID_COLUMNS == GRID_COLUMNS - 1 ? 0 : colGap, rowGap);
            t.setOnTileFocused(tile -> {
                lastGrid = index;
                focusedName.setText(tile.app.label);
            });
            final int col = i % GRID_COLUMNS;
            final boolean lastInRow = col == GRID_COLUMNS - 1 || i == apps.size() - 1;
            t.setOnKeyListener((v, keyCode, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                // Stay on the row at its ends instead of wrapping to the next one.
                return (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && col == 0)
                        || (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && lastInRow);
            });
            grid.addView(t, lp);
        }

        // The deferred banners are decoded once the tiles are up, off the main thread: the panel opens
        // without decoding on its first frame, and the tiles are ready before anyone scrolls to them.
        grid.post(() -> {
            List<Apps.LazyBanner> pending = new ArrayList<>();
            for (Apps.App a : apps) {
                if (a.banner instanceof Apps.LazyBanner) pending.add((Apps.LazyBanner) a.banner);
            }
            if (pending.isEmpty()) return;
            Thread t = new Thread(() -> {
                for (Apps.LazyBanner b : pending) b.resolve();
                grid.postInvalidate();
            }, "cosy-banners");
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        });
    }

    private void focusDock(int index) {
        if (dock.getChildCount() == 0) return;
        dock.getChildAt(Math.max(0, Math.min(index, dock.getChildCount() - 1))).requestFocus();
    }

    private void focusGrid(int index) {
        if (grid.getChildCount() == 0) {
            findViewById(R.id.back).requestFocus();
            return;
        }
        grid.getChildAt(Math.max(0, Math.min(index, grid.getChildCount() - 1))).requestFocus();
    }

    private void restoreFocus() {
        if (appsOpen) focusGrid(lastGrid);
        else focusDock(lastDock);
    }

    // ---- the apps panel ---------------------------------------------------------------------------

    private void openApps() {
        if (appsOpen) return;
        appsOpen = true;
        appsLayer.setAlpha(0f);
        appsLayer.setTranslationY(dp(16));
        appsLayer.setVisibility(View.VISIBLE);
        appsLayer.animate().alpha(1f).translationY(0f).setDuration(220).start();
        homeLayer.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> homeLayer.setVisibility(View.INVISIBLE)).start();
        // The grid starts with the dock apps, so the same app stays focused.
        lastGrid = lastDock;
        focusGrid(lastGrid);
    }

    private void closeApps() {
        if (!appsOpen) return;
        appsOpen = false;
        homeLayer.setVisibility(View.VISIBLE);
        homeLayer.animate().alpha(1f).setDuration(220).start();
        appsLayer.animate().alpha(0f).translationY(dp(16)).setDuration(160)
                .withEndAction(() -> appsLayer.setVisibility(View.GONE)).start();
        focusDock(lastDock);
    }

    // ---- inputs -----------------------------------------------------------------------------------

    /** Live TV, the HDMI ports in order, and USB media. Rebuilt on every resume. */
    private void buildInputs() {
        while (inputs.getChildCount() > 1) inputs.removeViewAt(1);

        Intent liveTv = getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) addInputChip(getString(R.string.live_tv), () -> open(liveTv));

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim != null) {
            List<TvInputInfo> hdmi = new ArrayList<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) hdmi.add(info);
            }
            Collections.sort(hdmi, (a, b) -> a.getId().compareTo(b.getId()));
            for (int i = 0; i < hdmi.size(); i++) {
                TvInputInfo info = hdmi.get(i);
                CharSequence l = info.loadLabel(this);
                String label = l == null || l.length() == 0 ? "HDMI " + (i + 1) : l.toString();
                if (!label.toUpperCase(Locale.ROOT).contains("HDMI")) label = "HDMI " + label;
                final String id = info.getId();
                addInputChip(label, () -> openInput(id));
            }
        }

        Intent usb = getPackageManager().getLeanbackLaunchIntentForPackage(usbPkg);
        if (usb != null) addInputChip(getString(R.string.usb), () -> open(usb));

        // Bottom of the screen: Down stays put.
        for (int i = 1; i < inputs.getChildCount(); i++) {
            inputs.getChildAt(i).setOnKeyListener((v, keyCode, e) ->
                    e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN);
        }
    }

    private void addInputChip(String label, Runnable action) {
        TextView chip = new TextView(this, null, 0, R.style.Chip);
        chip.setText(label);
        chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMarginStart(dp(8));
        inputs.addView(chip, lp);
    }

    /** The standard way to switch to an HDMI port: VIEW its passthrough channel, which Live TV handles. */
    private void openInput(String inputId) {
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        open(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel"));
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> list(int resId) {
        List<String> out = new ArrayList<>();
        for (String s : getString(resId).split(",")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
