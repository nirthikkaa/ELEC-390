package com.example.thereminglovestest2;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * ScrollView that only applies horizontal system insets.
 *
 * Top inset is handled by TopNavBarView.
 * Bottom inset is handled by BottomNavBarView.
 */
public class InsetAwareScrollView extends ScrollView {

    public InsetAwareScrollView(@NonNull Context context) {
        super(context);
        init();
    }

    public InsetAwareScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public InsetAwareScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        final int baseLeft = getPaddingLeft();
        final int baseTop = getPaddingTop();
        final int baseRight = getPaddingRight();
        final int baseBottom = getPaddingBottom();

        setClipToPadding(false);

        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    baseLeft + sys.left,
                    baseTop,
                    baseRight + sys.right,
                    baseBottom
            );
            return insets;
        });

        ViewCompat.requestApplyInsets(this);
    }
}