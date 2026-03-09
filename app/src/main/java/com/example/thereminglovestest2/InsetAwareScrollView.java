package com.example.thereminglovestest2;

/**
 * File guide:
 * Small ScrollView helper that adds padding for system bars so content does not get hidden behind insets.
 */

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class InsetAwareScrollView extends ScrollView {

    public InsetAwareScrollView(@NonNull Context c) { super(c); init(); }
    public InsetAwareScrollView(@NonNull Context c, @Nullable AttributeSet a) { super(c, a); init(); }
    public InsetAwareScrollView(@NonNull Context c, @Nullable AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        int left = getPaddingLeft(), top = getPaddingTop(), right = getPaddingRight(), bottom = getPaddingBottom();
        setClipToPadding(false);
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(left + sys.left, top, right + sys.right, bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(this);
    }
}
