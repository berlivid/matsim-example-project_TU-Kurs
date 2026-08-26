package org.matsim.project.prepare;

import java.util.List;
import java.util.Map;

/** Authoritative Schröder targets for the resident-based 2019 calibration. */
public final class ResidentModeChoiceCalibrationTargets {
    public static final List<String> MODES = List.of("car", "pt", "bike", "walk");
    public static final Map<String, Double> TRIP_SHARE_PERCENT = Map.of(
            "car", 34.0,
            "pt", 24.0,
            "bike", 18.0,
            "walk", 24.0);
    public static final Map<String, Double> ANNUAL_PKM_MILLION = Map.of(
            "car", 10_637.49,
            "pt", 4_510.08,
            "bike", 1_131.50,
            "walk", 620.50);
    public static final Map<String, Double> NORMALIZED_PKM_SHARE_PERCENT = Map.of(
            "car", 62.945329,
            "pt", 26.687543,
            "bike", 6.695437,
            "walk", 3.671691);

    private ResidentModeChoiceCalibrationTargets() { }

    public static void validate() {
        requireExactModes(TRIP_SHARE_PERCENT, "trip-share targets");
        requireExactModes(ANNUAL_PKM_MILLION, "annual passenger-kilometre targets");
        requireExactModes(NORMALIZED_PKM_SHARE_PERCENT, "normalized Pkm-share targets");
        requireClose(TRIP_SHARE_PERCENT.values().stream().mapToDouble(Double::doubleValue).sum(),
                100.0, "Trip-share targets must sum to 100%");
        requireClose(NORMALIZED_PKM_SHARE_PERCENT.values().stream()
                        .mapToDouble(Double::doubleValue).sum(),
                100.0, "Exact normalized Pkm-share targets must sum to 100%");
        double annualTotal = ANNUAL_PKM_MILLION.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        for (String mode : MODES) {
            double derived = 100.0 * ANNUAL_PKM_MILLION.get(mode) / annualTotal;
            requireClose(derived, NORMALIZED_PKM_SHARE_PERCENT.get(mode), 0.0000006,
                    "Normalized Pkm share is inconsistent for " + mode);
        }
    }

    private static void requireExactModes(Map<String, Double> values, String description) {
        ValidateModeChoiceCalibrationConfig.require(values.keySet().equals(SetHolder.MODES),
                "Unexpected modes in " + description + ": " + values.keySet());
        ValidateModeChoiceCalibrationConfig.require(values.values().stream()
                        .allMatch(value -> value != null && Double.isFinite(value) && value >= 0.0),
                "Invalid numeric value in " + description);
    }

    private static void requireClose(double actual, double expected, String message) {
        requireClose(actual, expected, 1e-9, message);
    }

    private static void requireClose(double actual, double expected, double tolerance,
                                     String message) {
        ValidateModeChoiceCalibrationConfig.require(Math.abs(actual - expected) <= tolerance,
                message + ": " + actual + " != " + expected);
    }

    private static final class SetHolder {
        private static final java.util.Set<String> MODES = java.util.Set.copyOf(
                ResidentModeChoiceCalibrationTargets.MODES);
    }
}
