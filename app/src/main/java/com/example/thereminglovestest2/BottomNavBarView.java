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

public class BottomNavBarView extends LinearLayout {

    private static final NavItem[] ITEMS = {
            new NavItem("Play", android.R.drawable.ic_media_play, MainActivity.class),
            new NavItem("Connect", android.R.drawable.stat_sys_data_bluetooth, ConnectGlovesActivity.class),
            new NavItem("Cal", android.R.drawable.ic_menu_compass, CalibrationActivity.class),
            new NavItem("Library", android.R.drawable.ic_menu_slideshow, LibraryActivity.class),
            new NavItem("Settings", android.R.drawable.ic_menu_manage, SettingsActivity.class)
    };

    private final int colorPrimary;
    private final int colorOnSurfaceVariant;
    private final int colorActiveBackground;

    public BottomNavBarView(Context context) { this(context, null); }
    public BottomNavBarView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }

    public BottomNavBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        colorPrimary = ContextCompat.getColor(context, R.color.app_primary);
        colorOnSurfaceVariant = ContextCompat.getColor(context, R.color.app_on_surface_variant);
        colorActiveBackground = withAlpha(colorPrimary, 46);

        int pad = dp(4);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setBackgroundColor(withAlpha(ContextCompat.getColor(context, R.color.app_surface), 90));
        setElevation(dp(8));
        setClipToPadding(false);
        setPadding(pad, pad, pad, pad);
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(pad + sys.left, pad, pad + sys.right, pad + sys.bottom);
            return insets;
        });

        for (NavItem item : ITEMS) addView(buildItem(item), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ViewCompat.requestApplyInsets(this);
    }

    private LinearLayout buildItem(NavItem item) {
        boolean active = getContext() instanceof Activity && getContext().getClass().equals(item.target);
        int itemColor = active ? colorPrimary : colorOnSurfaceVariant;

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setClickable(true);
        layout.setFocusable(true);
        layout.setPadding(dp(4), dp(6), dp(4), dp(6));
        layout.setBackgroundResource(selectableRes());
        if (active) layout.setBackgroundColor(colorActiveBackground);

        ImageView icon = new ImageView(getContext());
        icon.setImageResource(item.iconRes);
        icon.setColorFilter(itemColor);
        layout.addView(icon, new LayoutParams(dp(22), dp(22)));

        TextView label = new TextView(getContext());
        label.setText(item.label);
        label.setTextSize(11f);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(itemColor);
        if (active) label.setTypeface(label.getTypeface(), Typeface.BOLD);
        LayoutParams textLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        textLp.topMargin = dp(2);
        layout.addView(label, textLp);

        layout.setOnClickListener(v -> open(item.target));
        return layout;
    }

    private void open(Class<? extends Activity> target) {
        if (getContext() instanceof Activity) NavigationUtils.openScreen((Activity) getContext(), target);
    }

    private int selectableRes() {
        TypedValue tv = new TypedValue();
        return getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                ? tv.resourceId : android.R.color.transparent;
    }

    private static int withAlpha(int color, int alpha255) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha255)) << 24);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class NavItem {
        final String label;
        final int iconRes;
        final Class<? extends Activity> target;

        NavItem(String label, int iconRes, Class<? extends Activity> target) {
            this.label = label;
            this.iconRes = iconRes;
            this.target = target;
        }
    }
}
