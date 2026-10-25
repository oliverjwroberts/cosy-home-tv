package com.cosyhome.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The TV apps to show as tiles: every LEANBACK_LAUNCHER activity, one per package, dock apps
 * first in their configured order, then the pinned ones, then the rest alphabetically.
 * Banner loading follows cleanyourtv's AppAdapter (MIT, https://github.com/Gathin23/cleanyourtv).
 */
final class Apps {

    static final class App {
        final String label;
        final String pkg;
        final ComponentName component;
        final Drawable banner;
        final Drawable icon;

        App(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.pkg = component.getPackageName();
            this.component = component;
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** Decoded artwork, kept across rebuilds: the package list rarely changes, the banners even less. */
    private static final java.util.Map<String, Cached> CACHE = new java.util.HashMap<>();

    private static final class Cached {
        final long lastUpdateTime;
        final Drawable banner, icon;

        Cached(long lastUpdateTime, Drawable banner, Drawable icon) {
            this.lastUpdateTime = lastUpdateTime;
            this.banner = banner;
            this.icon = icon;
        }
    }

    private Apps() {
    }

    private static long lastUpdateTime(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /**
     * @param eager how many banners to decode before returning: the dock and the first grid row, the
     *              tiles a TV shows straight away. The others are decoded the first time they are
     *              drawn, so start-up does not pay for banners nobody has scrolled to yet.
     */
    static List<App> load(Context ctx, List<String> dock, List<String> pinned, Set<String> hidden, int eager) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Candidate> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || !seen.add(pkg)) continue;
            found.add(new Candidate(ri, pkg, String.valueOf(ri.loadLabel(pm)).trim(),
                    new ComponentName(pkg, ri.activityInfo.name)));
        }
        Collections.sort(found, (a, b) -> {
            int ra = rank(a.pkg, dock, pinned), rb = rank(b.pkg, dock, pinned);
            return ra != rb ? Integer.compare(ra, rb) : a.label.compareToIgnoreCase(b.label);
        });

        int[] size = tileSize(ctx);
        int w = size[0], h = size[1];
        List<App> apps = new ArrayList<>(found.size());
        for (int i = 0; i < found.size(); i++) {
            Candidate c = found.get(i);
            long updated = lastUpdateTime(pm, c.pkg);
            Cached cached = CACHE.get(c.pkg);
            if (cached != null && cached.lastUpdateTime == updated) {
                apps.add(new App(c.label, c.component, cached.banner, cached.icon));
                continue;
            }
            Drawable banner = null, icon = null;
            if (hasBanner(c.ri)) {
                if (i < eager) {
                    banner = loadBanner(ctx, pm, c.ri);
                    // A declared banner can still fail to decode: keep the icon the tile would show.
                    if (banner == null) icon = c.ri.loadIcon(pm);
                } else {
                    banner = new LazyBanner(ctx, pm, c.ri, w, h);
                }
            } else {
                icon = c.ri.loadIcon(pm);
            }
            apps.add(new App(c.label, c.component, banner, icon));
            CACHE.put(c.pkg, new Cached(updated, banner, icon));
        }
        // Forget the apps that are gone, so a reinstall always decodes fresh artwork.
        java.util.Set<String> current = new java.util.HashSet<>();
        for (App a : apps) current.add(a.pkg);
        CACHE.keySet().retainAll(current);
        return apps;
    }

    private static final class Candidate {
        final ResolveInfo ri;
        final String pkg;
        final String label;
        final ComponentName component;

        Candidate(ResolveInfo ri, String pkg, String label, ComponentName component) {
            this.ri = ri;
            this.pkg = pkg;
            this.label = label;
            this.component = component;
        }
    }

    /** The largest tile in pixels: 120x68dp at the focus zoom, for shrink() and the lazy bounds. */
    private static int[] tileSize(Context ctx) {
        float density = ctx.getResources().getDisplayMetrics().density;
        return new int[]{Math.round(120 * density * Tile.FOCUS_SCALE),
                Math.round(68 * density * Tile.FOCUS_SCALE)};
    }

    private static int rank(String pkg, List<String> dock, List<String> pinned) {
        int d = dock.indexOf(pkg);
        if (d >= 0) return d;
        int p = pinned.indexOf(pkg);
        return p >= 0 ? 100 + p : 1000;
    }

    /** Whether this activity has a banner, without decoding it. */
    private static boolean hasBanner(ResolveInfo ri) {
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        return id != 0;
    }

    /** A banner decoded the first time it is drawn, so start-up only pays for the tiles on screen. */
    static final class LazyBanner extends Drawable {
        private final Context ctx;
        private final PackageManager pm;
        private final ResolveInfo ri;
        private final int w, h;
        private Drawable real;
        private volatile Runnable onMissing;

        LazyBanner(Context ctx, PackageManager pm, ResolveInfo ri, int w, int h) {
            this.ctx = ctx;
            this.pm = pm;
            this.ri = ri;
            this.w = w;
            this.h = h;
        }

        /** Called when the banner cannot be decoded at all: the tile then switches to the icon and name. */
        void setOnMissing(Runnable r) {
            onMissing = r;
        }

        /** Decodes now, so a later draw on the UI thread costs nothing. */
        void resolve() {
            real();
        }

        private synchronized Drawable real() {
            if (real == null) {
                real = loadBanner(ctx, pm, ri);
                if (real == null) {
                    // Same fallback as an eager tile: a banner that fails to decode shows the icon and
                    // the name, so tell the tile, which was built in banner mode.
                    real = ri.loadIcon(pm);
                    Runnable r = onMissing;
                    if (r != null) r.run();
                }
            }
            return real;
        }

        @Override
        public void draw(Canvas c) {
            Drawable d = real();
            if (d == null) return;
            d.setBounds(getBounds());
            d.draw(c);
        }

        @Override
        public void setAlpha(int alpha) {
            Drawable d = real();
            if (d != null) d.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            Drawable d = real();
            if (d != null) d.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }
    }

    private static Drawable loadBanner(Context ctx, PackageManager pm, ResolveInfo ri) {
        Drawable d = null;
        int id = ri.activityInfo.banner;
        if (id == 0) id = ri.activityInfo.applicationInfo.banner;
        if (id != 0) {
            try {
                Resources res = pm.getResourcesForApplication(ri.activityInfo.applicationInfo);
                // At least xhdpi, so the artwork stays sharp through the focus zoom.
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, ctx.getResources().getDisplayMetrics().densityDpi);
                d = res.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
            }
        }
        if (d == null) d = ri.activityInfo.loadBanner(pm);
        return d == null ? null : shrink(ctx, d);
    }

    /**
     * Redraws a banner at tile size. Some apps ship 1080p banners: kept as they are, twenty of them
     * cost about 40 MB of bitmaps plus the same again as GPU textures, for tiles 240 px wide.
     */
    private static Drawable shrink(Context ctx, Drawable d) {
        int[] size = tileSize(ctx);
        int w = size[0], h = size[1];
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw > 0 && ih > 0) {
            // Centre-crop, as the tile's ImageView would.
            float scale = Math.max(w / (float) iw, h / (float) ih);
            int sw = Math.round(iw * scale), sh = Math.round(ih * scale);
            d.setBounds((w - sw) / 2, (h - sh) / 2, (w - sw) / 2 + sw, (h - sh) / 2 + sh);
        } else {
            d.setBounds(0, 0, w, h);
        }
        d.draw(c);
        return new BitmapDrawable(ctx.getResources(), bmp);
    }
}
