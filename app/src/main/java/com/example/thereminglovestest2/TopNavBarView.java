package com.example.thereminglovestest2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
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

    private static final MenuTarget[] EXTRA_MENU_TARGETS = {
            new MenuTarget("Setup", HomeActivity.class),
            new MenuTarget("Launch", LaunchActivity.class)
    };

    private final TextView titleView;
    private ImageButton overflowButton;
    private final View backButton;
    private final GradientDrawable pitchDotDrawable = new GradientDrawable();
    private final GradientDrawable volDotDrawable   = new GradientDrawable();

    public TopNavBarView(Context context) { this(context, null); }
    public TopNavBarView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }

    public TopNavBarView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        int pad = dp(8), onSurface = ContextCompat.getColor(context, R.color.app_on_surface);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(pad, pad, pad, pad);
        setMinimumHeight(dp(56));
        setClipToPadding(false);
        setElevation(dp(6));
        setBackgroundColor(withAlpha(ContextCompat.getColor(context, R.color.app_surface), 90));

        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(pad + sys.left, pad + sys.top, pad + sys.right, pad);
            return insets;
        });

        backButton = iconButton(androidx.appcompat.R.drawable.abc_ic_ab_back_material, "Back", v -> handleBackPressed(), onSurface);
        addView(backButton, new LayoutParams(dp(40), dp(40)));

        // Glove connection indicator dots (vol then pitch, left-to-right after back button)
        int dotSize = dp(11);
        int dotMargin = dp(5);
        volDotDrawable.setShape(GradientDrawable.OVAL);
        volDotDrawable.setColor(0xFFFF647D);
        View volDot = new View(context);
        volDot.setBackground(volDotDrawable);
        LayoutParams volDotLp = new LayoutParams(dotSize, dotSize);
        volDotLp.leftMargin = dotMargin;
        addView(volDot, volDotLp);

        pitchDotDrawable.setShape(GradientDrawable.OVAL);
        pitchDotDrawable.setColor(0xFFFF647D);
        View pitchDot = new View(context);
        pitchDot.setBackground(pitchDotDrawable);
        LayoutParams pitchDotLp = new LayoutParams(dotSize, dotSize);
        pitchDotLp.leftMargin = dp(3);
        addView(pitchDot, pitchDotLp);

        titleView = new TextView(context);
        titleView.setTextSize(18f);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setTextColor(onSurface);
        titleView.setText(NavigationUtils.resolveScreenTitle(context));
        LayoutParams titleLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(8);
        titleLp.rightMargin = dp(10);
        addView(titleView, titleLp);

        overflowButton = iconButton(androidx.appcompat.R.drawable.abc_ic_menu_overflow_material, "More options", this::showMenu, onSurface);
        addView(overflowButton, new LayoutParams(dp(40), dp(40)));
        ViewCompat.requestApplyInsets(this);
    }

    public void setTitleText(String title) { titleView.setText(title); }

    /** Show or hide the back arrow. Pass false on screens where back navigation is irrelevant. */
    public void setBackButtonVisible(boolean visible) {
        backButton.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * Update the glove connection indicator dot colors.
     * @param pitchColor ARGB color for the pitch (right) glove dot
     * @param volColor   ARGB color for the volume (left) glove dot
     */
    public void setGloveStatus(int pitchColor, int volColor) {
        pitchDotDrawable.setColor(pitchColor);
        volDotDrawable.setColor(volColor);
    }

    /** Override the 3-dot button's click listener. Pass null to restore the default menu. */
    public void setMenuClickListener(View.OnClickListener listener) {
        overflowButton.setOnClickListener(listener != null ? listener : this::showMenu);
    }

    /**
     * Insert an icon button immediately before the overflow (3-dot) button.
     * Call multiple times to add several buttons — they appear left-to-right in call order.
     */
    public void addActionButton(int iconRes, String desc, View.OnClickListener listener) {
        int onSurface = ContextCompat.getColor(getContext(), R.color.app_on_surface);
        ImageButton btn = iconButton(iconRes, desc, listener, onSurface);
        // getChildCount()-1 inserts just before the overflow button (always last child)
        addView(btn, getChildCount() - 1, new LayoutParams(dp(40), dp(40)));
    }

    private ImageButton iconButton(int iconRes, String desc, OnClickListener click, int tint) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(iconRes);
        button.setContentDescription(desc);
        button.setBackgroundResource(selectableRes());
        button.setColorFilter(tint);
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setOnClickListener(click);
        return button;
    }

    private void handleBackPressed() {
        Activity current = activity();
        if (current == null) return;
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
        Activity current = activity();
        if (current == null) return;

        PopupMenu popup = new PopupMenu(current, anchor);
        for (int i = 0; i < EXTRA_MENU_TARGETS.length; i++) {
            MenuTarget target = EXTRA_MENU_TARGETS[i];
            if (!current.getClass().equals(target.screen)) popup.getMenu().add(0, i, i, target.title);
        }
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id < 0 || id >= EXTRA_MENU_TARGETS.length) return false;
            NavigationUtils.openScreen(current, EXTRA_MENU_TARGETS[id].screen);
            return true;
        });
        popup.show();
    }

    private Activity activity() { return getContext() instanceof Activity ? (Activity) getContext() : null; }

    private int selectableRes() {
        TypedValue tv = new TypedValue();
        return getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                ? tv.resourceId : android.R.color.transparent;
    }

    private static int withAlpha(int color, int alpha255) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha255)) << 24);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class MenuTarget {
        final String title;
        final Class<? extends Activity> screen;

        MenuTarget(String title, Class<? extends Activity> screen) {
            this.title = title;
            this.screen = screen;
        }
    }
}

final class NavigationUtils {

    private NavigationUtils() {}

    static void openScreen(Activity current, Class<? extends Activity> target) { navigate(current, target, false); }
    static void replaceWithScreen(Activity current, Class<? extends Activity> target) { navigate(current, target, true); }

    static String resolveScreenTitle(Context context) {
        if (context instanceof LaunchActivity) return "Launch";
        if (context instanceof HomeActivity) return "Setup";
        if (context instanceof MainActivity) return "Play";
        if (context instanceof ConnectGlovesActivity) return "Connect Gloves";
        if (context instanceof CalibrationActivity) return "Calibration";
        if (context instanceof LibraryActivity) return "Library";
        if (context instanceof SettingsActivity) return "Settings";
        return "Theremin Gloves";
    }

    static final class Poller {
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final long intervalMs;
        private final Runnable task;
        private final Runnable loop = new Runnable() {
            @Override public void run() {
                task.run();
                handler.postDelayed(this, intervalMs);
            }
        };

        Poller(long intervalMs, Runnable task) {
            this.intervalMs = intervalMs;
            this.task = task;
        }

        void start() {
            stop();
            handler.post(loop);
        }

        void stop() { handler.removeCallbacks(loop); }
    }

    private static void navigate(Activity current, Class<? extends Activity> target, boolean finishCurrent) {
        if (current == null || target == null) return;
        if (!current.getClass().equals(target)) {
            current.startActivity(new Intent(current, target).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        }
        current.overridePendingTransition(0, 0);
        if (!finishCurrent) return;
        current.finish();
        current.overridePendingTransition(0, 0);
    }
}
