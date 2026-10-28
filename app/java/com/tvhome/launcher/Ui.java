package com.tvhome.launcher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Shared tile styling so size, corner roundness and focus zoom stay consistent. */
public final class Ui {

    // Frosted-glass fills: a light translucent gradient plus a hairline highlight stroke.
    private static final int GLASS_TOP = 0x36FFFFFF;
    private static final int GLASS_BOTTOM = 0x0FFFFFFF;
    private static final int GLASS_STROKE = 0x26FFFFFF;
    private static final int FOCUS_FILL_TOP = 0x46FFFFFF;
    private static final int FOCUS_FILL_BOTTOM = 0x1FFFFFFF;
    private static final int GREEN = 0xFF00E676;
    private static final int GREEN_GLOW = 0x3D00E676;

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Rounded glass card that draws the green focus ring itself, using the requested radius. */
    public static Drawable card(Context context, int radiusDp) {
        float radius = dp(context, radiusDp);

        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{GLASS_TOP, GLASS_BOTTOM});
        rest.setShape(GradientDrawable.RECTANGLE);
        rest.setCornerRadius(radius);
        rest.setStroke(dp(context, 1), GLASS_STROKE);

        GradientDrawable glow = new GradientDrawable();
        glow.setShape(GradientDrawable.RECTANGLE);
        glow.setCornerRadius(radius + dp(context, 2));
        glow.setColor(0x00000000);
        glow.setStroke(dp(context, 6), GREEN_GLOW);

        GradientDrawable ring = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{FOCUS_FILL_TOP, FOCUS_FILL_BOTTOM});
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setCornerRadius(radius);
        ring.setStroke(dp(context, 2), GREEN);

        LayerDrawable focused = new LayerDrawable(new Drawable[]{glow, ring});
        int inset = dp(context, 4);
        focused.setLayerInset(1, inset, inset, inset, inset);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, rest);
        return states;
    }

    /**
     * tvOS-style focus treatment: 1.05x zoom plus a soft elevation shadow on focus,
     * animated quickly on a hardware layer so D-pad navigation stays at 60fps.
     */
    public static void zoom(View view) {
        view.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            v.animate()
                    .scaleX(focused ? 1.05f : 1f)
                    .scaleY(focused ? 1.05f : 1f)
                    .setDuration(120L)
                    .setInterpolator(new DecelerateInterpolator())
                    .withLayer()
                    .start();
            v.setElevation(dp(v.getContext(), focused ? 10 : 2));
        });
    }

    /** The corner radius to use for the current user preference. */
    public static int radius(Context context) {
        return new Prefs(context).cornerDp();
    }

    /** The tile scale to use for the current user preference. */
    public static float scale(Context context) {
        return new Prefs(context).tileScale();
    }
}
