package com.example.thereminglovestest2;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Persistent bottom navigation bar with exactly 5 items (one row).
 * Reuses existing activities when possible to reduce lifecycle churn.
 */
public class BottomNavBarView extends LinearLayout {

    private int colorSurface;
    private int colorPrimary;
    private int colorOnSurfaceVariant;
    private int colorActiveBackground;

    public BottomNavBarView(Context context) {
        super(context);
        init(context);
    }

    public BottomNavBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BottomNavBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        colorSurface = ContextCompat.getColor(context, R.color.app_surface);
        colorPrimary = ContextCompat.getColor(context, R.color.app_primary);
        colorOnSurfaceVariant = ContextCompat.getColor(context, R.color.app_on_surface_variant);
        colorActiveBackground = withAlpha(colorPrimary, 46);

        // Plain translucent fill only. Lowered alpha for more transparency.
        setBackgroundColor(withAlpha(colorSurface, 90));
        setElevation(dp(8));

        int hPad = dp(4);
        int vPad = dp(4);
        setPadding(hPad, vPad, hPad, vPad);
        setClipToPadding(false);

        final int baseLeft = getPaddingLeft();
        final int baseTop = getPaddingTop();
        final int baseRight = getPaddingRight();
        final int baseBottom = getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    baseLeft + sys.left,
                    baseTop,
                    baseRight + sys.right,
                    baseBottom + sys.bottom
            );
            return insets;
        });

        addNavItem(context, "Play", android.R.drawable.ic_media_play, MainActivity.class);
        addNavItem(context, "Connect", android.R.drawable.stat_sys_data_bluetooth, ConnectGlovesActivity.class);
        addNavItem(context, "Cal", android.R.drawable.ic_menu_compass, CalibrationActivity.class);
        addNavItem(context, "Library", android.R.drawable.ic_menu_slideshow, LibraryActivity.class);
        addNavItem(context, "Settings", android.R.drawable.ic_menu_manage, SettingsActivity.class);

        ViewCompat.requestApplyInsets(this);
    }

    private void addNavItem(Context context, String label, int iconRes, Class<? extends Activity> target) {
        boolean isActive = isCurrentActivity(target);

        LinearLayout item = new LinearLayout(context);
        item.setOrientation(VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setClickable(true);
        item.setFocusable(true);
        item.setPadding(dp(4), dp(6), dp(4), dp(6));
        item.setBackgroundResource(getSelectableItemBackgroundResId());

        if (isActive) {
            item.setBackgroundColor(colorActiveBackground);
        }

        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setColorFilter(isActive ? colorPrimary : colorOnSurfaceVariant);

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        item.addView(icon, iconLp);

        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(11f);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(isActive ? colorPrimary : colorOnSurfaceVariant);
        if (isActive) {
            tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        }

        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT
        );
        textLp.topMargin = dp(2);
        item.addView(tv, textLp);

        item.setOnClickListener(v -> openScreen(target));

        LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                0, LayoutParams.WRAP_CONTENT, 1f
        );
        addView(item, itemLp);
    }

    private boolean isCurrentActivity(Class<? extends Activity> target) {
        Context c = getContext();
        return (c instanceof Activity) && ((Activity) c).getClass().equals(target);
    }

    private void openScreen(Class<? extends Activity> target) {
        Context c = getContext();
        if (!(c instanceof Activity)) return;
        NavigationUtils.openScreen((Activity) c, target);
    }

    private int getSelectableItemBackgroundResId() {
        TypedValue tv = new TypedValue();
        boolean ok = getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true
        );
        return ok ? tv.resourceId : android.R.color.transparent;
    }

    private static int withAlpha(int color, int alpha255) {
        alpha255 = Math.max(0, Math.min(255, alpha255));
        return (color & 0x00FFFFFF) | (alpha255 << 24);
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}