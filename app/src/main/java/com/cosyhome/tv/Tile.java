package com.cosyhome.tv;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.function.Consumer;

/** One app: its banner (or icon and name when it has none), zooming with a white ring on focus. */
@SuppressLint("ViewConstructor")
final class Tile extends FrameLayout {

    static final float FOCUS_SCALE = 1.1f;

    final Apps.App app;
    private final float restAlpha;
    private final float lift;
    private Consumer<Tile> onFocus;

    Tile(Context ctx, Apps.App app, float restAlpha) {
        super(ctx);
        this.app = app;
        this.restAlpha = restAlpha;
        float d = getResources().getDisplayMetrics().density;
        lift = 10 * d;
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(0xFF3A3A3C);
        setForeground(ctx.getDrawable(R.drawable.tile_ring));
        setAlpha(restAlpha);
        setContentDescription(app.label);
        final float radius = 9 * d;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);

        if (app.banner != null) {
            ImageView banner = new ImageView(ctx);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            banner.setImageDrawable(app.banner);
            addView(banner, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            if (app.banner instanceof Apps.LazyBanner) {
                // A deferred banner that fails to decode falls back to the icon and the name.
                ((Apps.LazyBanner) app.banner).setOnMissing(icon -> post(() -> showIconAndLabel(icon)));
            }
        } else {
            showIconAndLabel(app.icon);
        }

        setOnClickListener(v -> launch());
        setOnLongClickListener(v -> {
            open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)));
            return true;
        });
        setOnFocusChangeListener((v, focused) -> {
            animate().cancel();
            animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f)
                    .alpha(focused ? 1f : restAlpha)
                    .translationZ(focused ? lift : 0f)
                    .setDuration(focused ? 240 : 160)
                    .setInterpolator(new OvershootInterpolator(focused ? 1.4f : 0f))
                    .start();
            if (focused && onFocus != null) onFocus.accept(this);
        });
    }

    /** The tile for an app with no banner: the icon, and the name under it. */
    private void showIconAndLabel(Drawable icon) {
        float d = getResources().getDisplayMetrics().density;
        removeAllViews();
        ImageView iconView = new ImageView(getContext());
        iconView.setImageDrawable(icon);
        LayoutParams ilp = new LayoutParams((int) (28 * d), (int) (28 * d), Gravity.CENTER);
        ilp.bottomMargin = (int) (10 * d);
        addView(iconView, ilp);
        TextView label = new TextView(getContext());
        label.setText(app.label);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(11f);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setTypeface(getResources().getFont(R.font.outfit));
        LayoutParams llp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        llp.bottomMargin = (int) (6 * d);
        addView(label, llp);
    }

    void setOnTileFocused(Consumer<Tile> listener) {
        onFocus = listener;
    }

    private void launch() {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (!open(i)) {
            Toast.makeText(getContext(), getContext().getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
