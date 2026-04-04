package com.example.thereminglovestest2;

/**
 * Small test-facing pattern routing contract used by JVM tests.
 *
 * Production playback is handled directly inside DrumEngine. The unit tests still verify custom
 * grid row-routing behavior in isolation, so this interface keeps that contract available without
 * pulling Android-specific classes into the test logic.
 */
interface PatternSource {

    interface TriggerDispatcher {
        void fire(int snd, float vol);
        void fireBass(int slot);
        void firePiano(int midiNote);
    }

    void query(int step16, int bar, boolean drumsOn, boolean bassOn, TriggerDispatcher dispatcher);
}
