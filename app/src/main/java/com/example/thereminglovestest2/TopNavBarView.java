package com.example.thereminglovestest2;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class TopNavBarView extends LinearLayout {

    private TextView titleView;
    private ImageButton backButton;
    private ImageButton menuButton;

    public TopNavBarView(Context context) {
        super(context);
        init(context);
    }

    public TopNavBarView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TopNavBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        final int baseLeft = dp(8);
        final int baseTop = dp(8);
        final int baseRight = dp(8);
        final int baseBottom = dp(8);

        setPadding(baseLeft, baseTop, baseRight, baseBottom);
        setMinimumHeight(dp(56));
        setClipToPadding(false);
        setElevation(dp(6));

        int surface = ContextCompat.getColor(context, R.color.app_surface);
        int onSurface = ContextCompat.getColor(context, R.color.app_on_surface);

        setBackgroundColor(withAlpha(surface, 90));

        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    baseLeft + sys.left,
                    baseTop + sys.top,
                    baseRight + sys.right,
                    baseBottom
            );
            return insets;
        });

        backButton = new ImageButton(context);
        backButton.setImageResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        backButton.setContentDescription("Back");
        backButton.setBackgroundResource(getBorderlessSelectableResId());
        backButton.setColorFilter(onSurface);
        backButton.setScaleType(ImageButton.ScaleType.CENTER);
        LayoutParams backLp = new LayoutParams(dp(40), dp(40));
        addView(backButton, backLp);

        titleView = new TextView(context);
        titleView.setTextSize(18f);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setText(NavigationUtils.resolveScreenTitle(context));
        titleView.setTextColor(onSurface);

        LayoutParams titleLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(10);
        titleLp.rightMargin = dp(10);
        addView(titleView, titleLp);

        menuButton = new ImageButton(context);
        menuButton.setImageResource(androidx.appcompat.R.drawable.abc_ic_menu_overflow_material);
        menuButton.setContentDescription("More options");
        menuButton.setBackgroundResource(getBorderlessSelectableResId());
        menuButton.setColorFilter(onSurface);
        menuButton.setScaleType(ImageButton.ScaleType.CENTER);
        LayoutParams menuLp = new LayoutParams(dp(40), dp(40));
        addView(menuButton, menuLp);

        backButton.setOnClickListener(v -> handleBackPressed());
        menuButton.setOnClickListener(this::showMenu);
        ViewCompat.requestApplyInsets(this);
    }

    private void handleBackPressed() {
        Context c = getContext();
        if (!(c instanceof Activity)) return;

        Activity current = (Activity) c;

        if (current instanceof HomeActivity) {
            NavigationUtils.replaceWithScreen(current, LaunchActivity.class);
            return;
        }

        if (current.isTaskRoot()) {
            NavigationUtils.replaceWithScreen(current, HomeActivity.class);
            return;
        }

        current.finish();
        current.overridePendingTransition(0, 0);
    }

    private void showMenu(View anchor) {
        Context c = getContext();
        PopupMenu popup = new PopupMenu(c, anchor);

        popup.getMenu().add(0, 100, 100, "Setup");
        popup.getMenu().add(0, 101, 101, "Launch");
        popup.getMenu().add(0, 1, 1, "Play");
        popup.getMenu().add(0, 2, 2, "Connect Gloves");
        popup.getMenu().add(0, 3, 3, "Calibration");
        popup.getMenu().add(0, 4, 4, "Library");
        popup.getMenu().add(0, 5, 5, "Settings");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 100:
                    openScreen(HomeActivity.class);
                    return true;
                case 101:
                    openScreen(LaunchActivity.class);
                    return true;
                case 1:
                    openScreen(MainActivity.class);
                    return true;
                case 2:
                    openScreen(ConnectGlovesActivity.class);
                    return true;
                case 3:
                    openScreen(CalibrationActivity.class);
                    return true;
                case 4:
                    openScreen(LibraryActivity.class);
                    return true;
                case 5:
                    openScreen(SettingsActivity.class);
                    return true;
                default:
                    return false;
            }
        });

        popup.show();
    }

    private void openScreen(Class<? extends Activity> target) {
        Context c = getContext();
        if (!(c instanceof Activity)) return;
        NavigationUtils.openScreen((Activity) c, target);
    }

    public void setTitleText(String title) {
        if (titleView != null) {
            titleView.setText(title);
        }
    }

    private int getBorderlessSelectableResId() {
        TypedValue tv = new TypedValue();
        boolean ok = getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true
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
