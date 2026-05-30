package com.bg7yoz.ft8cn.decisions;

/**
 * Shared state enums for the station state machine.
 * [STATE MACHINE] Extracted to avoid circular dependencies with DecisionEngine.
 *
 * @author BG7YOZ
 * @date 2026-05-10
 */
public class StationState {

    public enum OperationalMode {
        SCANNING_RF,    // Searching for best RF frequency, ignore responses
        SCANNING_AUDIO, // Audio calibration mode, test transmissions only
        OPERATING       // Main operating mode (contains dialogue substates)
    }

    public enum OperatingSubState {
        SEEKING,        // Looking for a station to start dialogue with
        IN_DIALOGUE,    // Actively conducting a QSO (steps 1-6)
        PILEUP_MODE,    // Multiple stations calling us simultaneously
        MANUAL_TARGET,  // User manually selected a target (override)
        SOFT_FINISH,    // Stopped transmitting but listening for resume
        NOMADIC         // [NEW] Blending through preset frequencies
    }

    public enum DialogueStep {
        IDLE(0, "Not in dialogue / Waiting"),
        CALLING(1, "Called/Answered, waiting for report"),
        REPORT(2, "Report sent (-XX), waiting for R-report"),
        CONFIRM(3, "R-report sent, waiting for RR73"),
        CLOSING(4, "RR73 sent, waiting for 73"),
        FINISHED(5, "73 sent, QSO complete"),
        CQ_MODE(6, "Sending CQ, waiting for replies");

        public final int id;
        public final String description;

        DialogueStep(int id, String description) {
            this.id = id;
            this.description = description;
        }
    }
}