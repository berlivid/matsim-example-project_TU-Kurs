package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fail-closed convergence, target-fit and stuck-trip review for productive Round 2. */
final class ResidentModeChoiceRound2Review {
    static final int FIRST_LATE_ITERATION = 51;
    static final int LAST_LATE_ITERATION = 60;

    private ResidentModeChoiceRound2Review() { }

    static Result validateAndWrite(Path output) throws IOException {
        Path analysis = output.resolve("analysis");
        Csv late = Csv.read(analysis.resolve(
                "resident_mode_choice_late_iteration_statistics.csv"));
        Csv stuck = Csv.read(analysis.resolve(
                "resident_stuck_events_by_iteration_and_mode.csv"));
        LinkedHashMap<String, ModeReview> modes = new LinkedHashMap<>();
        boolean allConverged = true;
        boolean allWithinTarget = true;
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            Map<String, String> trip = unique(late, "resident_trip_share", mode);
            Map<String, String> pkm = unique(late, "resident_pkm_share", mode);
            validateLateIdentity(trip);
            validateLateIdentity(pkm);
            double tripTarget = number(trip, "target_value");
            double lateMean = number(trip, "mean");
            double range = number(trip, "range");
            double trend = number(trip, "linear_trend_per_iteration");
            double finalTripShare = number(trip, "final_value");
            String convergenceStatus = Math.abs(trend)
                    <= ResidentModeChoiceCalibrationAnalysisWriter
                    .LATE_TREND_REVIEW_THRESHOLD_PP_PER_ITERATION
                    && range <= ResidentModeChoiceCalibrationAnalysisWriter
                    .LATE_RANGE_REVIEW_THRESHOLD_PP
                    ? "CONVERGED" : "NOT_CONVERGED";
            String targetFitStatus = Math.abs(finalTripShare - tripTarget)
                    <= ResidentModeChoiceCalibrationAnalysisWriter.TARGET_FIT_TOLERANCE_PP
                    ? "WITHIN_TARGET_TOLERANCE" : "OUTSIDE_TARGET_TOLERANCE";
            ModeReview review = new ModeReview(mode, lateMean,
                    number(trip, "minimum"), number(trip, "maximum"), range, trend,
                    finalTripShare, tripTarget, lateMean - tripTarget,
                    finalTripShare - tripTarget, number(pkm, "mean"),
                    number(pkm, "final_value"), number(pkm, "target_value"),
                    number(pkm, "final_value") - number(pkm, "target_value"),
                    convergenceStatus, targetFitStatus);
            require(Math.abs(review.tripTarget()
                            - ResidentModeChoiceCalibrationTargets
                            .TRIP_SHARE_PERCENT.get(mode)) < 1e-9,
                    "Round-2 trip target changed for " + mode);
            require(Math.abs(review.pkmTarget()
                            - ResidentModeChoiceCalibrationTargets
                            .NORMALIZED_PKM_SHARE_PERCENT.get(mode)) < 1e-9,
                    "Round-2 Pkm target changed for " + mode);
            allConverged &= "CONVERGED".equals(convergenceStatus);
            allWithinTarget &= "WITHIN_TARGET_TOLERANCE".equals(targetFitStatus);
            modes.put(mode, review);
        }

        List<Map<String, String>> lateStuck = stuck.rows().stream()
                .filter(row -> "all".equals(row.get("routing_mode")))
                .filter(row -> {
                    int iteration = Integer.parseInt(row.get("iteration"));
                    return iteration >= FIRST_LATE_ITERATION
                            && iteration <= LAST_LATE_ITERATION;
                }).toList();
        require(lateStuck.size() == 10,
                "Round-2 stuck history must contain exactly iterations 51..60");
        double maximumStuckTripShare = lateStuck.stream()
                .mapToDouble(row -> number(row, "resident_main_trip_share_percent"))
                .max().orElseThrow();
        boolean stuckWithinThreshold = maximumStuckTripShare
                <= ResidentModeChoiceStuckEventListener.STUCK_TRIP_REVIEW_THRESHOLD_PERCENT;
        String overall = allConverged && allWithinTarget && stuckWithinThreshold
                ? "CALIBRATED" : "REVIEW_REQUIRED";
        Result result = new Result(Map.copyOf(modes), maximumStuckTripShare,
                stuckWithinThreshold ? "WITHIN_STUCK_THRESHOLD"
                        : "OUTSIDE_STUCK_THRESHOLD", overall);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_2_calibration_review.csv"),
                csv(result), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_2_calibration_report.md"),
                report(result), StandardCharsets.UTF_8);
        return result;
    }

    static String csv(Result result) {
        StringBuilder out = new StringBuilder(
                "mode,late_mean_trip_share_percent,late_minimum_trip_share_percent,late_maximum_trip_share_percent,late_range_pp,late_trend_pp_per_iteration,final_trip_share_percent,trip_target_percent,late_mean_trip_difference_pp,final_trip_difference_pp,late_mean_pkm_share_percent,final_pkm_share_percent,pkm_target_percent,final_pkm_difference_pp,convergence_status,target_fit_status\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            ModeReview row = result.modes().get(mode);
            out.append(mode).append(',').append(number(row.lateMeanTripShare()))
                    .append(',').append(number(row.lateMinimumTripShare()))
                    .append(',').append(number(row.lateMaximumTripShare()))
                    .append(',').append(number(row.lateRange())).append(',')
                    .append(number(row.lateTrend())).append(',')
                    .append(number(row.finalTripShare())).append(',')
                    .append(number(row.tripTarget())).append(',')
                    .append(number(row.lateMeanTripDifference())).append(',')
                    .append(number(row.finalTripDifference())).append(',')
                    .append(number(row.lateMeanPkmShare())).append(',')
                    .append(number(row.finalPkmShare())).append(',')
                    .append(number(row.pkmTarget())).append(',')
                    .append(number(row.finalPkmDifference())).append(',')
                    .append(row.convergenceStatus()).append(',')
                    .append(row.targetFitStatus()).append('\n');
        }
        return out.toString();
    }

    private static String report(Result result) {
        StringBuilder out = new StringBuilder(
                "# Resident mode-choice calibration Round 2 review\n\n")
                .append("Late evaluation uses exactly iterations 51--60. Physical trip shares are the primary calibration metric. Normalized physical Pkm shares remain secondary plausibility indicators; choice/routing modes and StuckEvent sensitivity are reported by the shared analysis outputs.\n\n")
                .append("| Mode | Late mean trip share | Final trip share | Target | Final difference | Range | Trend/iteration | Convergence | Target fit | Late mean Pkm share | Final Pkm share | Pkm target |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---|---|---:|---:|---:|\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            ModeReview row = result.modes().get(mode);
            out.append("| ").append(mode).append(" | ")
                    .append(number(row.lateMeanTripShare())).append("% | ")
                    .append(number(row.finalTripShare())).append("% | ")
                    .append(number(row.tripTarget())).append("% | ")
                    .append(number(row.finalTripDifference())).append(" pp | ")
                    .append(number(row.lateRange())).append(" pp | ")
                    .append(number(row.lateTrend())).append(" pp | ")
                    .append(row.convergenceStatus()).append(" | ")
                    .append(row.targetFitStatus()).append(" | ")
                    .append(number(row.lateMeanPkmShare())).append("% | ")
                    .append(number(row.finalPkmShare())).append("% | ")
                    .append(number(row.pkmTarget())).append("% |\n");
        }
        out.append("\nMaximum resident stuck-trip share in iterations 51--60: ")
                .append(number(result.maximumLateStuckTripSharePercent()))
                .append("% (`").append(result.stuckStatus()).append("`). Overall status: `")
                .append(result.overallStatus()).append("`.\n\n")
                .append("`CONVERGED` requires |trend| <= 0.10 percentage points per iteration and range <= 1.0 percentage point. `WITHIN_TARGET_TOLERANCE` requires an absolute final physical trip-share difference <= 1.0 percentage point. `CALIBRATED` additionally requires the maximum late resident stuck-trip share <= 1.0%. These are thesis-specific review rules; failure requests review and never changes constants automatically. Absolute annual Pkm remain outside this calibration step.\n");
        return out.toString();
    }

    private static void validateLateIdentity(Map<String, String> row) {
        require(Integer.parseInt(row.get("first_iteration")) == FIRST_LATE_ITERATION
                        && Integer.parseInt(row.get("last_iteration"))
                        == LAST_LATE_ITERATION
                        && Integer.parseInt(row.get("iterations")) == 10,
                "Late statistics must use exactly iterations 51..60: " + row);
    }

    private static Map<String, String> unique(Csv csv, String metric, String mode) {
        List<Map<String, String>> matches = csv.rows().stream()
                .filter(row -> metric.equals(row.get("metric"))
                        && mode.equals(row.get("mode"))).toList();
        require(matches.size() == 1,
                "Expected one late-statistics row for " + metric + "/" + mode
                        + ", found " + matches.size());
        return matches.getFirst();
    }

    private static double number(Map<String, String> row, String field) {
        String value = row.get(field);
        require(value != null && !value.isBlank(), "Missing numeric field " + field);
        return Double.parseDouble(value);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.9f", value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Result(Map<String, ModeReview> modes, double maximumLateStuckTripSharePercent,
                  String stuckStatus, String overallStatus) { }

    record ModeReview(String mode, double lateMeanTripShare,
                      double lateMinimumTripShare, double lateMaximumTripShare,
                      double lateRange, double lateTrend, double finalTripShare,
                      double tripTarget, double lateMeanTripDifference,
                      double finalTripDifference, double lateMeanPkmShare,
                      double finalPkmShare, double pkmTarget, double finalPkmDifference,
                      String convergenceStatus, String targetFitStatus) { }

    private record Csv(List<Map<String, String>> rows) {
        static Csv read(Path path) throws IOException {
            require(Files.isRegularFile(path), "Missing Round-2 analysis file: " + path);
            List<String> lines = Files.readAllLines(path);
            require(!lines.isEmpty(), "Empty Round-2 analysis file: " + path);
            List<String> header = List.of(lines.getFirst().split(",", -1));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int line = 1; line < lines.size(); line++) {
                if (lines.get(line).isBlank()) continue;
                String[] fields = lines.get(line).split(",", -1);
                require(fields.length == header.size(),
                        "Malformed Round-2 CSV row " + (line + 1) + " in " + path);
                LinkedHashMap<String, String> row = new LinkedHashMap<>();
                for (int column = 0; column < fields.length; column++) {
                    row.put(header.get(column), fields[column]);
                }
                rows.add(Map.copyOf(row));
            }
            return new Csv(List.copyOf(rows));
        }
    }
}
