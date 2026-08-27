package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared fail-closed convergence, target-fit and stuck-trip review for rounds 2 and 3. */
final class ResidentModeChoiceRound2Review {
    static final int FIRST_LATE_ITERATION = 51;
    static final int LAST_LATE_ITERATION = 60;

    private ResidentModeChoiceRound2Review() { }

    static Result validateAndWrite(Path output) throws IOException {
        return validateAndWrite(output, 2);
    }

    static Result validateAndWriteRound3(Path output, Path round2Analysis)
            throws IOException {
        Result result = validateAndWrite(output, 3);
        writeRound2Comparison(output.resolve("analysis"), round2Analysis, result);
        return result;
    }

    private static Result validateAndWrite(Path output, int round) throws IOException {
        Path analysis = output.resolve("analysis");
        validateIterationHistory(analysis);
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
                    "Resident calibration trip target changed for " + mode);
            require(Math.abs(review.pkmTarget()
                            - ResidentModeChoiceCalibrationTargets
                            .NORMALIZED_PKM_SHARE_PERCENT.get(mode)) < 1e-9,
                    "Resident calibration Pkm target changed for " + mode);
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
                "Resident calibration stuck history must contain exactly iterations 51..60");
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
                        "resident_mode_choice_round_" + round
                                + "_calibration_review.csv"),
                csv(result), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_" + round
                                + "_calibration_report.md"),
                report(result, round), StandardCharsets.UTF_8);
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

    private static String report(Result result, int round) {
        StringBuilder out = new StringBuilder(
                "# Resident mode-choice calibration Round " + round + " review\n\n")
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

    private static void validateIterationHistory(Path analysis) throws IOException {
        Csv history = Csv.read(analysis.resolve(
                "resident_mode_choice_iteration_metrics.csv"));
        Map<Integer, Set<String>> keysByIteration = new LinkedHashMap<>();
        for (Map<String, String> row : history.rows()) {
            int iteration = Integer.parseInt(row.get("iteration"));
            String key = row.get("metric") + "/" + row.get("dimension");
            require(keysByIteration.computeIfAbsent(iteration,
                            ignored -> new LinkedHashSet<>()).add(key),
                    "Duplicate resident round history row at iteration "
                            + iteration + ": " + key);
        }
        require(keysByIteration.size() == 61
                        && keysByIteration.keySet().stream().mapToInt(Integer::intValue)
                        .min().orElseThrow() == 0
                        && keysByIteration.keySet().stream().mapToInt(Integer::intValue)
                        .max().orElseThrow() == 60,
                "Resident round history must contain exactly iterations 0..60");
        for (int iteration = 0; iteration <= 60; iteration++) {
            Set<String> keys = keysByIteration.get(iteration);
            require(keys != null, "Missing resident history iteration " + iteration);
            require(keys.contains("resident_persons/all")
                            && keys.contains("resident_main_trips/all")
                            && keys.contains("resident_physical_main_trips/all")
                            && keys.contains("resident_pt_request_walk_only_physical_route/"
                            + "physical_walk_choice_pt")
                            && keys.contains("background_persons_excluded_from_targets/"
                            + "regional_background")
                            && keys.contains("background_persons_excluded_from_targets/"
                            + "unresolved_background"),
                    "Resident round history is missing required shared metrics at "
                            + "iteration " + iteration);
            for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
                for (String metric : List.of("resident_main_trips",
                        "resident_trip_share", "resident_physical_main_trips",
                        "resident_physical_trip_share", "resident_choice_main_trips",
                        "resident_choice_trip_share", "raw_simulated_daily_sample_pkm",
                        "five_percent_annualised_pkm_diagnostic",
                        "resident_pkm_share")) {
                    require(keys.contains(metric + "/" + mode),
                            "Resident round history is missing " + metric + "/"
                                    + mode + " at iteration " + iteration);
                }
            }
            for (String spatial : List.of("BOTH_INSIDE", "ORIGIN_ONLY",
                    "DESTINATION_ONLY", "BOTH_OUTSIDE",
                    "INVALID_OR_MISSING_COORDINATE")) {
                require(keys.contains("resident_spatial_main_trips/" + spatial)
                                && keys.contains("resident_spatial_trip_share/" + spatial),
                        "Resident round history is missing spatial category "
                                + spatial + " at iteration " + iteration);
            }
        }
    }

    private static void writeRound2Comparison(Path analysis, Path round2Analysis,
                                              Result round3) throws IOException {
        Csv round2 = Csv.read(round2Analysis.resolve(
                "resident_mode_choice_round_2_calibration_review.csv"));
        StringBuilder csv = new StringBuilder(
                "mode,round_2_constant,round_3_constant,round_2_late_mean_trip_share_percent,round_3_late_mean_trip_share_percent,late_mean_difference_pp,round_2_final_trip_share_percent,round_3_final_trip_share_percent,final_difference_pp,round_2_convergence_status,round_3_convergence_status,round_2_target_fit_status,round_3_target_fit_status\n");
        StringBuilder report = new StringBuilder(
                "# Resident mode-choice calibration Round 2 versus Round 3\n\n")
                .append("Both runs use iterations 0--60, the unchanged original population, seed 4711 and innovation switch-off after iteration 48. Their only behavioral differences are the three non-reference mode constants. Physical resident trip shares are compared over the common late window 51--60.\n\n")
                .append("| Mode | R2 constant | R3 constant | R2 late mean | R3 late mean | Difference | R2 final | R3 final | Difference | R2 convergence | R3 convergence | R2 target fit | R3 target fit |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            Map<String, String> old = uniqueMode(round2, mode);
            ModeReview current = round3.modes().get(mode);
            double oldLate = number(old, "late_mean_trip_share_percent");
            double oldFinal = number(old, "final_trip_share_percent");
            double oldConstant = ResidentModeChoiceRound3Specification
                    .ROUND_2_CONSTANTS.get(mode);
            double newConstant = ResidentModeChoiceRound3Specification
                    .ROUND_3_CONSTANTS.get(mode);
            csv.append(mode).append(',').append(number(oldConstant)).append(',')
                    .append(number(newConstant)).append(',').append(number(oldLate))
                    .append(',').append(number(current.lateMeanTripShare())).append(',')
                    .append(number(current.lateMeanTripShare() - oldLate)).append(',')
                    .append(number(oldFinal)).append(',')
                    .append(number(current.finalTripShare())).append(',')
                    .append(number(current.finalTripShare() - oldFinal)).append(',')
                    .append(old.get("convergence_status")).append(',')
                    .append(current.convergenceStatus()).append(',')
                    .append(old.get("target_fit_status")).append(',')
                    .append(current.targetFitStatus()).append('\n');
            report.append("| ").append(mode).append(" | ")
                    .append(number(oldConstant)).append(" | ")
                    .append(number(newConstant)).append(" | ")
                    .append(number(oldLate)).append("% | ")
                    .append(number(current.lateMeanTripShare())).append("% | ")
                    .append(number(current.lateMeanTripShare() - oldLate)).append(" pp | ")
                    .append(number(oldFinal)).append("% | ")
                    .append(number(current.finalTripShare())).append("% | ")
                    .append(number(current.finalTripShare() - oldFinal)).append(" pp | ")
                    .append(old.get("convergence_status")).append(" | ")
                    .append(current.convergenceStatus()).append(" | ")
                    .append(old.get("target_fit_status")).append(" | ")
                    .append(current.targetFitStatus()).append(" |\n");
        }
        report.append("\nRound 3 is not declared calibrated because a single iteration is close to a target. The shared convergence, target-fit and stuck-trip criteria apply to the complete late window.\n");
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_2_vs_round_3.csv"),
                csv, StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_2_vs_round_3.md"),
                report, StandardCharsets.UTF_8);
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

    private static Map<String, String> uniqueMode(Csv csv, String mode) {
        List<Map<String, String>> matches = csv.rows().stream()
                .filter(row -> mode.equals(row.get("mode"))).toList();
        require(matches.size() == 1,
                "Expected one Round-2 comparison row for " + mode
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
            require(Files.isRegularFile(path), "Missing calibration analysis file: " + path);
            List<String> lines = Files.readAllLines(path);
            require(!lines.isEmpty(), "Empty calibration analysis file: " + path);
            List<String> header = List.of(lines.getFirst().split(",", -1));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int line = 1; line < lines.size(); line++) {
                if (lines.get(line).isBlank()) continue;
                String[] fields = lines.get(line).split(",", -1);
                require(fields.length == header.size(),
                        "Malformed calibration CSV row " + (line + 1) + " in " + path);
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
