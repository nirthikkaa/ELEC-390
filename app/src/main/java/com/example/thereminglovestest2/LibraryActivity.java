package com.example.thereminglovestest2;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityBaseNavPlaceholderBinding;

public class LibraryActivity extends AppCompatActivity {

    private ActivityBaseNavPlaceholderBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBaseNavPlaceholderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.topNavBar.setTitleText("Library");
        binding.tvPlaceholderTitle.setText("Library");
        binding.tvPlaceholderBody.setText("Your recordings, presets, and session history will appear here.");
    }
}
