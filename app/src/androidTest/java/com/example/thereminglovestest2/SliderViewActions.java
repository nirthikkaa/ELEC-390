package com.example.thereminglovestest2;

import android.view.View;

import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;

import com.google.android.material.slider.Slider;

import org.hamcrest.Matcher;

import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;

/**
 * Small Espresso helpers for Material Slider interactions that are awkward to drag deterministically.
 */
final class SliderViewActions {
    private SliderViewActions() {}

    static ViewAction setValue(float value) {
        return new ViewAction() {
            @Override
            public Matcher<View> getConstraints() {
                return isAssignableFrom(Slider.class);
            }

            @Override
            public String getDescription() {
                return "set slider value to " + value;
            }

            @Override
            public void perform(UiController uiController, View view) {
                Slider slider = (Slider) view;
                slider.setValue(value);
                uiController.loopMainThreadUntilIdle();
            }
        };
    }
}
