package com.example.thereminglovestest2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.graphics.PorterDuff;
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

    private final TextView    titleView;
    private final LinearLayout leftContainer;
    private final LinearLayout rightContainer;
    private final View        backButton;
    private       ImageButton overflowButton;
    private       ImageButton volHandBtn;    // left hand  — volume glove
    private       ImageButton pitchHandBtn;  // right hand — pitch glove
    // Stage mode: mini VOL / HZ cards that mirror the play screen aesthetic
    private LinearLayout stageVolCard;
    private LinearLayout stageFreqCard;
    private TextView stageVolValue;
    private TextView stageFreqValue;

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

        leftContainer = new LinearLayout(context);
        leftContainer.setOrientation(HORIZONTAL);
        leftContainer.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        addView(leftContainer, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        backButton = iconButton(androidx.appcompat.R.drawable.abc_ic_ab_back_material, "Back", v -> handleBackPressed(), onSurface);
        leftContainer.addView(backButton, new LayoutParams(dp(40), dp(40)));

        titleView = new TextView(context);
        titleView.setTextSize(18f);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        titleView.setTextColor(onSurface);
        titleView.setGravity(Gravity.CENTER);
        titleView.setText(NavigationUtils.resolveScreenTitle(context));
        LayoutParams titleLp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(4);
        titleLp.rightMargin = dp(4);
        addView(titleView, titleLp);

        rightContainer = new LinearLayout(context);
        rightContainer.setOrientation(HORIZONTAL);
        rightContainer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addView(rightContainer, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        // Glove hand icons — hidden by default, shown via setGloveStatus()
        int handSize = dp(34);
        volHandBtn = handIconButton(context, false); // left hand (mirrored)
        LayoutParams volLp = new LayoutParams(handSize, handSize);
        volLp.rightMargin = dp(2);
        rightContainer.addView(volHandBtn, volLp);

        pitchHandBtn = handIconButton(context, true); // right hand
        LayoutParams pitchLp = new LayoutParams(handSize, handSize);
        pitchLp.rightMargin = dp(2);
        rightContainer.addView(pitchHandBtn, pitchLp);

        overflowButton = iconButton(androidx.appcompat.R.drawable.abc_ic_menu_overflow_material, "More options", this::showMenu, onSurface);
        rightContainer.addView(overflowButton, new LayoutParams(dp(40), dp(40)));

        // Stage mode: VOL card on left, HZ card on right — same aesthetic as play screen pills
        stageVolCard  = stageMiniCard(context, "VOL", 0xFF221A3F, 0xFFFF8ED1);
        stageVolValue = stageValView(stageVolCard, 0xFFFFF7FC);
        leftContainer.addView(stageVolCard, 0,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        stageFreqCard  = stageMiniCard(context, "HZ", 0xFF16203B, 0xFF7FC0FF);
        stageFreqValue = stageValView(stageFreqCard, 0xFFF5FAFF);
        rightContainer.addView(stageFreqCard, 0,
                new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        ViewCompat.requestApplyInsets(this);
    }

    public void setTitleText(String title) { titleView.setText(title); }

    /**
     * Enter or exit stage mode. Title becomes invisible (still holds center space so cards
     * stay on opposite edges of the camera cutout). VOL and HZ mini-cards become visible.
     * Does NOT touch the overflow button — callers manage that independently.
     */
    // In stage mode the glove buttons are hidden; setGloveStatus() updates colors but not visibility.
    private boolean inStageMode = false;
    private int     lastPitchColor = 0xFFFF4444; // default red = disconnected
    private int     lastVolColor   = 0xFFFF4444;

    public void setStageMode(boolean on) {
        inStageMode = on;
        // Keep titleView INVISIBLE (not GONE) so weight=1 still reserves center space between cards.
        titleView.setVisibility(on ? INVISIBLE : VISIBLE);
        if (stageVolCard  != null) stageVolCard.setVisibility(on ? VISIBLE : GONE);
        if (stageFreqCard != null) stageFreqCard.setVisibility(on ? VISIBLE : GONE);
        if (on) {
            // Hide glove icons — VOL/HZ cards occupy the nav bar in their place.
            volHandBtn.setVisibility(GONE);
            pitchHandBtn.setVisibility(GONE);
        } else {
            // Restore glove icons with the last colours received from the poller.
            pitchHandBtn.setColorFilter(lastPitchColor, PorterDuff.Mode.SRC_IN);
            volHandBtn.setColorFilter(lastVolColor,     PorterDuff.Mode.SRC_IN);
            pitchHandBtn.setVisibility(VISIBLE);
            volHandBtn.setVisibility(VISIBLE);
        }
    }

    /** Update the VOL / HZ readouts shown in the stage mode mini-cards. */
    public void setStageFreqVol(String vol, String freq) {
        if (stageVolValue  != null) stageVolValue.setText(vol);
        if (stageFreqValue != null) stageFreqValue.setText(freq);
    }

    /** Show or hide the back arrow. Pass false on screens where back navigation is irrelevant. */
    public void setBackButtonVisible(boolean visible) {
        backButton.setVisibility(visible ? VISIBLE : GONE);
    }

    /** Override the back button's click listener. Pass null to restore the default behaviour. */
    public void setOnBackClickListener(View.OnClickListener listener) {
        backButton.setOnClickListener(listener != null ? listener : v -> handleBackPressed());
    }

    /** Show or hide the 3-dot overflow menu button. */
    public void setOverflowButtonVisible(boolean visible) {
        overflowButton.setVisibility(visible ? VISIBLE : GONE);
    }

    /**
     * Set glove connection indicator hand icon tints.
     * In normal mode also makes the icons visible; in stage mode only caches the colours
     * so they are correct when stage mode exits.
     */
    public void setGloveStatus(int pitchColor, int volColor) {
        lastPitchColor = pitchColor;
        lastVolColor   = volColor;
        pitchHandBtn.setColorFilter(pitchColor, PorterDuff.Mode.SRC_IN);
        volHandBtn.setColorFilter(volColor,     PorterDuff.Mode.SRC_IN);
        if (!inStageMode) {
            pitchHandBtn.setVisibility(VISIBLE);
            volHandBtn.setVisibility(VISIBLE);
        }
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
        rightContainer.addView(btn, Math.max(0, rightContainer.indexOfChild(overflowButton)),
                new LayoutParams(dp(40), dp(40)));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        leftContainer.setMinimumWidth(0);
        rightContainer.setMinimumWidth(0);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int sideWidth = Math.max(leftContainer.getMeasuredWidth(), rightContainer.getMeasuredWidth());
        if (leftContainer.getMinimumWidth() != sideWidth || rightContainer.getMinimumWidth() != sideWidth) {
            leftContainer.setMinimumWidth(sideWidth);
            rightContainer.setMinimumWidth(sideWidth);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    /**
     * Creates a non-clickable hand icon button for glove status display.
     * @param isRight true = right hand (pitch glove); false = left hand (volume glove, mirrored)
     */
    private ImageButton handIconButton(Context ctx, boolean isRight) {
        ImageButton btn = new ImageButton(ctx);
        btn.setImageResource(R.drawable.ic_hand);
        btn.setBackground(null); // no ripple — it's an indicator, not a button
        btn.setClickable(false);
        btn.setFocusable(false);
        btn.setScaleType(ImageButton.ScaleType.FIT_CENTER);
        btn.setPadding(dp(4), dp(4), dp(4), dp(4));
        // Mirror horizontally for the left hand
        if (!isRight) btn.setScaleX(-1f);
        btn.setColorFilter(0xFFFF4444, PorterDuff.Mode.SRC_IN); // default red
        btn.setVisibility(GONE); // hidden until setGloveStatus() is called
        btn.setContentDescription(isRight ? "Pitch glove" : "Volume glove");
        return btn;
    }

    /** Creates the pill-shaped background card used in stage mode (matches play screen aesthetic). */
    private LinearLayout stageMiniCard(Context ctx, String label, int bgColor, int labelColor) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(8), dp(14), dp(8));
        card.setVisibility(GONE);

        TextView lbl = new TextView(ctx);
        lbl.setText(label);
        lbl.setTextSize(11f);
        lbl.setTypeface(lbl.getTypeface(), Typeface.BOLD);
        lbl.setTextColor(labelColor);
        lbl.setAllCaps(true);
        card.addView(lbl, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        return card;
    }

    /** Adds the value TextView to a stage mini-card and returns it. */
    private TextView stageValView(LinearLayout card, int textColor) {
        TextView val = new TextView(card.getContext());
        val.setTextSize(18f);
        val.setTypeface(val.getTypeface(), Typeface.BOLD);
        val.setTextColor(textColor);
        val.setText("—");
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(7);
        card.addView(val, lp);
        return val;
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
