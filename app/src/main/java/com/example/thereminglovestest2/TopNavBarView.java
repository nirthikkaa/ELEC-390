package com.example.thereminglovestest2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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

import com.google.android.material.color.MaterialColors;

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
        int p = dp(8);
        setPadding(p, p, p, p);

        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);

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
        titleView.setText(getDefaultTitle());
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

        backButton.setOnClickListener(v -> {
            Context c = getContext();
            if (c instanceof Activity) {
                Activity a = (Activity) c;
                a.finish();
                a.overridePendingTransition(0, 0); // no back animation
            }
        });

        menuButton.setOnClickListener(this::showMenu);
    }

    private void showMenu(View anchor) {
        Context c = getContext();
        PopupMenu popup = new PopupMenu(c, anchor);

        popup.getMenu().add(0, 1, 1, "Play");
        popup.getMenu().add(0, 2, 2, "Connect Gloves");
        popup.getMenu().add(0, 3, 3, "Calibration");
        popup.getMenu().add(0, 4, 4, "Library");
        popup.getMenu().add(0, 5, 5, "Settings");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
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

        Activity current = (Activity) c;
        if (current.getClass().equals(target)) return;

        Intent intent = new Intent(current, target);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        current.startActivity(intent);
        current.overridePendingTransition(0, 0); // no forward animation
    }

    public void setTitleText(String title) {
        if (titleView != null) titleView.setText(title);
    }

    private String getDefaultTitle() {
        Context c = getContext();
        if (c instanceof MainActivity) return "Play";
        if (c instanceof ConnectGlovesActivity) return "Connect Gloves";
        if (c instanceof CalibrationActivity) return "Calibration";
        if (c instanceof LibraryActivity) return "Library";
        if (c instanceof SettingsActivity) return "Settings";
        return "Theremin Gloves";
    }

    private int getBorderlessSelectableResId() {
        TypedValue tv = new TypedValue();
        boolean ok = getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, tv, true
        );
        return ok ? tv.resourceId : android.R.color.transparent;
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}