package com.example.thereminglovestest2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

final class NavigationUtils {

    private NavigationUtils() {
        // Utility class
    }

    static void openScreen(Activity current, Class<? extends Activity> target) {
        navigate(current, target, false);
    }

    static void replaceWithScreen(Activity current, Class<? extends Activity> target) {
        navigate(current, target, true);
    }

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

    private static void navigate(Activity current, Class<? extends Activity> target, boolean finishCurrent) {
        if (current == null || target == null) return;

        if (!current.getClass().equals(target)) {
            Intent intent = new Intent(current, target);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
            );
            current.startActivity(intent);
        }

        current.overridePendingTransition(0, 0);

        if (finishCurrent) {
            current.finish();
            current.overridePendingTransition(0, 0);
        }
    }
}
