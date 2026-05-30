package com.bg7yoz.ft8cn.decisions;

import android.util.Log;

import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.GeneralVariables;

/**
 * HybridScorer: multi-criteria decision scoring for CQ selection.
 * [SCORING ENGINE] Combines signal quality, rarity, propagation, goals, and limits.
 *
 * Score = Sum(weight[c] * dynamic_factor[c] * value[c]) for all criteria c
 *
 * @author BG7YOZ
 * @date 2026-05-10
 */
public class HybridScorer {
    private static final String TAG = "HybridScorer";

    // === Scoring criteria (ordered by priority) ===
    public enum Criterion {
        SNR(0.25f),           // Signal-to-noise ratio: stronger = better
        RARITY(0.35f),        // DX/new band: rarer = higher priority
        PROPAGATION(0.20f),   // Propagation conditions: better = more reliable
        PERSONAL_GOAL(0.15f), // User's personal goals (e.g., specific countries)
        TECH_LIMIT(0.05f);    // Technical limits (power, antenna, etc.)

        public final float baseWeight;
        Criterion(float weight) { this.baseWeight = weight; }
    }

    /**
     * Score a single station record against current context.
     * @param s Station to evaluate
     * @param ctx Current decision context
     * @return Score in range [0.0, 1.0+] (higher = better candidate)
     */
    public float score(DatabaseOpr.StationRecord s, DecisionContext ctx) {
        if (s == null) return 0f;

        // [FIX 2026-05-23] Exclude own callsign from scoring to prevent self-calling
        // This prevents the state machine from selecting our own CQ as a target
        if (s.callsign != null && GeneralVariables.myCallsign != null &&
                s.callsign.equals(GeneralVariables.myCallsign)) {
            Log.d(TAG, "score: SKIP " + s.callsign + " (own callsign) -> 0.0");
            return 0.0f;
        }
        // [END FIX]

        /* OLD CODE COMMENTED OUT
        if (s == null) return 0f;
        */

        float totalScore = 0f;

        for (Criterion c : Criterion.values()) {
            float value = getValueForCriterion(c, s, ctx);
            float dynamicFactor = getDynamicFactor(c, s, ctx);
            float weight = ctx.weights[c.ordinal()];

            float contribution = weight * dynamicFactor * value;
            Log.d(TAG, String.format("score: %s weight=%.2f dyn=%.2f val=%.2f -> contrib=%.3f",
                    c.name(), weight, dynamicFactor, value, contribution));

            totalScore += contribution;
        }

        Log.d(TAG, String.format("Final score for %s: %.3f", s.callsign, totalScore));
        return totalScore;
    }

    /**
     * Get normalized value (0.0-1.0) for a specific criterion.
     */
    private float getValueForCriterion(Criterion c, DatabaseOpr.StationRecord s, DecisionContext ctx) {
        switch (c) {
            case SNR:
                // Normalize SNR: -20dB -> 0.0, -5dB -> 1.0
                return Math.max(0f, Math.min(1f, (s.lastSnr + 20f) / 15f));

            case RARITY:
                // New DX or new band = high priority
                if (s.isNewDx) return 1.0f;
                int currentBandBit = DatabaseOpr.freqToBandBit(GeneralVariables.band);
                return (s.bandsBitmap & (1L << currentBandBit)) == 0 ? 1.0f : 0.3f;

            case PROPAGATION:
                // Simple heuristic: bearing > 5000 = long distance = good propagation
                return s.lastBearing > 5000f ? 1.0f : 0.5f;

            case PERSONAL_GOAL:
                // Placeholder: user can set personal goals in settings
                return 0.5f;

            case TECH_LIMIT:
                // Assume all stations are technically reachable for now
                return 1.0f;

            default:
                return 0f;
        }
    }

    /**
     * Get dynamic factor (0.5-2.0) to adjust weight based on context.
     */
    private float getDynamicFactor(Criterion c, DatabaseOpr.StationRecord s, DecisionContext ctx) {
        switch (c) {
            case SNR:
                // Boost weak signals if no strong ones available
                return ctx.visibleStations.size() < 3 ? 1.2f : 0.9f;

            case RARITY:
                return ctx.acceptDxCalls ? 1.2f : 1.0f;

            case PROPAGATION:
                // Boost if propagation is stable (simplified)
                return 1.1f;

            case PERSONAL_GOAL:
                // User goals are fixed priority
                return 1.0f;

            case TECH_LIMIT:
                // No dynamic adjustment for now
                return 1.0f;

            default:
                return 1.0f;
        }
    }

    /**
     * Find best candidate from list using scoring.
     * @param candidates List of station records to evaluate
     * @param ctx Current decision context
     * @return Best station or null if list empty
     */
    public DatabaseOpr.StationRecord findBestCandidate(
            java.util.List<DatabaseOpr.StationRecord> candidates,
            DecisionContext ctx) {
        if (candidates == null || candidates.isEmpty()) return null;

        DatabaseOpr.StationRecord best = null;
        float bestScore = -1f;

        for (DatabaseOpr.StationRecord s : candidates) {
            float sScore = score(s, ctx);
            if (sScore > bestScore) {
                bestScore = sScore;
                best = s;
            }
        }

        return best;
    }
}