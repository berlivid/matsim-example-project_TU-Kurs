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
    static final List<String> FINAL_CANDIDATE_ANALYSIS_FILES = List.of(
            "resident_mode_choice_iteration_metrics.csv",
            "resident_mode_choice_late_iteration_statistics.csv",
            "resident_mode_choice_final_summary.csv",
            "resident_mode_choice_report.md",
            "resident_stuck_events_by_iteration_and_mode.csv",
            "resident_mode_choice_final_primary.csv",
            "resident_mode_choice_final_stuck_sensitivity.csv",
            "resident_mode_choice_final_sensitivity_comparison.csv",
            "resident_mode_choice_final_sensitivity_report.md",
            "resident_mode_choice_final_candidate_calibration_review.csv",
            "resident_mode_choice_final_candidate_calibration_report.md",
            "resident_mode_choice_final_candidate_comparison.csv",
            "resident_mode_choice_final_candidate_comparison.md");

    private ResidentModeChoiceRound2Review() { }

    static Result validateAndWrite(Path output) throws IOException {
        return validateAndWrite(output, 2);
    }

    static Result validateAndWriteRound3(Path output, Path round2Analysis)
            throws IOException {
        Result result = validateAndWrite(output, 3);
        writeComparison(output.resolve("analysis"), round2Analysis, 2, 3,
                ResidentModeChoiceRound3Specification.ROUND_2_CONSTANTS,
                ResidentModeChoiceRound3Specification.ROUND_3_CONSTANTS, result);
        return result;
    }

    static Result validateAndWriteRound4(Path output, Path round3Analysis)
            throws IOException {
        Result result = validateAndWrite(output, 4);
        Path analysis = output.resolve("analysis");
        writeComparison(analysis, round3Analysis, 3, 4,
                ResidentModeChoiceRound4Specification.ROUND_3_CONSTANTS,
                ResidentModeChoiceRound4Specification.ROUND_4_CONSTANTS, result);
        writeCalibrationHistory(analysis,
                ResidentModeChoiceRound3Specification.ROUND_2_ANALYSIS,
                round3Analysis, result);
        return result;
    }

    static Result validateAndWriteFinalCandidate(Path output, Path legacyComparison)
            throws IOException {
        Result result = validateAndWrite(output, "final_candidate",
                "Final Legacy-R1 resident candidate");
        writeFinalCandidateComparison(output.resolve("analysis"), legacyComparison, result);
        requireFinalCandidateAnalysisFiles(output.resolve("analysis"));
        return result;
    }

    private static Result validateAndWrite(Path output, int round) throws IOException {
        return validateAndWrite(output, "round_" + round, "Round " + round);
    }

    private static Result validateAndWrite(Path output, String fileLabel,
                                           String reportLabel) throws IOException {
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
                        "resident_mode_choice_" + fileLabel
                                + "_calibration_review.csv"),
                csv(result), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_" + fileLabel
                                + "_calibration_report.md"),
                report(result, reportLabel), StandardCharsets.UTF_8);
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

    private static String report(Result result, String label) {
        StringBuilder out = new StringBuilder(
                "# Resident mode-choice calibration " + label + " review\n\n")
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

    private static void writeComparison(Path analysis, Path previousAnalysis,
                                        int previousRound, int currentRound,
                                        Map<String, Double> previousConstants,
                                        Map<String, Double> currentConstants,
                                        Result current) throws IOException {
        Csv previous = Csv.read(previousAnalysis.resolve(
                "resident_mode_choice_round_" + previousRound
                        + "_calibration_review.csv"));
        StringBuilder csv = new StringBuilder(
                "mode,round_" + previousRound + "_constant,round_" + currentRound
                        + "_constant,round_" + previousRound
                        + "_late_mean_trip_share_percent,round_" + currentRound
                        + "_late_mean_trip_share_percent,late_mean_difference_pp,round_"
                        + previousRound + "_final_trip_share_percent,round_" + currentRound
                        + "_final_trip_share_percent,final_difference_pp,round_"
                        + previousRound + "_convergence_status,round_" + currentRound
                        + "_convergence_status,round_" + previousRound
                        + "_target_fit_status,round_" + currentRound
                        + "_target_fit_status\n");
        StringBuilder report = new StringBuilder(
                "# Resident mode-choice calibration Round " + previousRound
                        + " versus Round " + currentRound + "\n\n")
                .append("Both runs use iterations 0--60, the unchanged original population, seed 4711 and innovation switch-off after iteration 48. Their only behavioral differences are the three non-reference mode constants. Physical resident trip shares are compared over the common late window 51--60.\n\n")
                .append("| Mode | R").append(previousRound).append(" constant | R")
                .append(currentRound).append(" constant | R").append(previousRound)
                .append(" late mean | R").append(currentRound)
                .append(" late mean | Difference | R").append(previousRound)
                .append(" final | R").append(currentRound)
                .append(" final | Difference | R").append(previousRound)
                .append(" convergence | R").append(currentRound)
                .append(" convergence | R").append(previousRound)
                .append(" target fit | R").append(currentRound).append(" target fit |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            Map<String, String> old = uniqueMode(previous, mode);
            ModeReview currentMode = current.modes().get(mode);
            double oldLate = number(old, "late_mean_trip_share_percent");
            double oldFinal = number(old, "final_trip_share_percent");
            double oldConstant = previousConstants.get(mode);
            double newConstant = currentConstants.get(mode);
            csv.append(mode).append(',').append(number(oldConstant)).append(',')
                    .append(number(newConstant)).append(',').append(number(oldLate))
                    .append(',').append(number(currentMode.lateMeanTripShare())).append(',')
                    .append(number(currentMode.lateMeanTripShare() - oldLate)).append(',')
                    .append(number(oldFinal)).append(',')
                    .append(number(currentMode.finalTripShare())).append(',')
                    .append(number(currentMode.finalTripShare() - oldFinal)).append(',')
                    .append(old.get("convergence_status")).append(',')
                    .append(currentMode.convergenceStatus()).append(',')
                    .append(old.get("target_fit_status")).append(',')
                    .append(currentMode.targetFitStatus()).append('\n');
            report.append("| ").append(mode).append(" | ")
                    .append(number(oldConstant)).append(" | ")
                    .append(number(newConstant)).append(" | ")
                    .append(number(oldLate)).append("% | ")
                    .append(number(currentMode.lateMeanTripShare())).append("% | ")
                    .append(number(currentMode.lateMeanTripShare() - oldLate)).append(" pp | ")
                    .append(number(oldFinal)).append("% | ")
                    .append(number(currentMode.finalTripShare())).append("% | ")
                    .append(number(currentMode.finalTripShare() - oldFinal)).append(" pp | ")
                    .append(old.get("convergence_status")).append(" | ")
                    .append(currentMode.convergenceStatus()).append(" | ")
                    .append(old.get("target_fit_status")).append(" | ")
                    .append(currentMode.targetFitStatus()).append(" |\n");
        }
        report.append("\nRound ").append(currentRound)
                .append(" is not declared calibrated because a single iteration is close to a target. The shared convergence, target-fit and stuck-trip criteria apply to the complete late window.\n");
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_" + previousRound
                                + "_vs_round_" + currentRound + ".csv"),
                csv, StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_" + previousRound
                                + "_vs_round_" + currentRound + ".md"),
                report, StandardCharsets.UTF_8);
    }

    private static void writeCalibrationHistory(Path analysis, Path round2Analysis,
                                                Path round3Analysis, Result round4)
            throws IOException {
        Csv round2 = Csv.read(round2Analysis.resolve(
                "resident_mode_choice_round_2_calibration_review.csv"));
        Csv round3 = Csv.read(round3Analysis.resolve(
                "resident_mode_choice_round_3_calibration_review.csv"));
        StringBuilder csv = new StringBuilder(
                "round,mode,constant,late_mean_trip_share_percent,final_trip_share_percent,trip_target_percent,late_mean_difference_pp,final_difference_pp,convergence_status,target_fit_status\n");
        StringBuilder report = new StringBuilder(
                "# Resident mode-choice calibration history, Rounds 2--4\n\n")
                .append("All three rounds use the unchanged original population, iterations 0--60 and the common late window 51--60. Physical resident trip shares are the primary metric.\n\n")
                .append("| Round | Mode | Constant | Late mean | Final | Target | Late difference | Final difference | Convergence | Target fit |\n")
                .append("|---:|---|---:|---:|---:|---:|---:|---:|---|---|\n");
        appendHistoryRound(csv, report, 2, round2,
                ResidentModeChoiceRound3Specification.ROUND_2_CONSTANTS);
        appendHistoryRound(csv, report, 3, round3,
                ResidentModeChoiceRound4Specification.ROUND_3_CONSTANTS);
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            appendHistoryRow(csv, report, 4, mode,
                    ResidentModeChoiceRound4Specification.ROUND_4_CONSTANTS.get(mode),
                    round4.modes().get(mode));
        }
        report.append("\nA round is calibrated only when every mode satisfies the shared late-window convergence and target-fit rules and the resident stuck-trip criterion.\n");
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_2_to_4_history.csv"),
                csv, StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_2_to_4_history.md"),
                report, StandardCharsets.UTF_8);
    }

    private static void appendHistoryRound(StringBuilder csv, StringBuilder report,
                                           int round, Csv review,
                                           Map<String, Double> constants) {
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            Map<String, String> row = uniqueMode(review, mode);
            ModeReview values = new ModeReview(mode,
                    number(row, "late_mean_trip_share_percent"),
                    number(row, "late_minimum_trip_share_percent"),
                    number(row, "late_maximum_trip_share_percent"),
                    number(row, "late_range_pp"),
                    number(row, "late_trend_pp_per_iteration"),
                    number(row, "final_trip_share_percent"),
                    number(row, "trip_target_percent"),
                    number(row, "late_mean_trip_difference_pp"),
                    number(row, "final_trip_difference_pp"),
                    number(row, "late_mean_pkm_share_percent"),
                    number(row, "final_pkm_share_percent"),
                    number(row, "pkm_target_percent"),
                    number(row, "final_pkm_difference_pp"),
                    row.get("convergence_status"), row.get("target_fit_status"));
            appendHistoryRow(csv, report, round, mode, constants.get(mode), values);
        }
    }

    private static void appendHistoryRow(StringBuilder csv, StringBuilder report,
                                         int round, String mode, double constant,
                                         ModeReview values) {
        csv.append(round).append(',').append(mode).append(',')
                .append(number(constant)).append(',')
                .append(number(values.lateMeanTripShare())).append(',')
                .append(number(values.finalTripShare())).append(',')
                .append(number(values.tripTarget())).append(',')
                .append(number(values.lateMeanTripDifference())).append(',')
                .append(number(values.finalTripDifference())).append(',')
                .append(values.convergenceStatus()).append(',')
                .append(values.targetFitStatus()).append('\n');
        report.append("| ").append(round).append(" | ").append(mode).append(" | ")
                .append(number(constant)).append(" | ")
                .append(number(values.lateMeanTripShare())).append("% | ")
                .append(number(values.finalTripShare())).append("% | ")
                .append(number(values.tripTarget())).append("% | ")
                .append(number(values.lateMeanTripDifference())).append(" pp | ")
                .append(number(values.finalTripDifference())).append(" pp | ")
                .append(values.convergenceStatus()).append(" | ")
                .append(values.targetFitStatus()).append(" |\n");
    }

    private static void writeFinalCandidateComparison(Path analysis,
                                                       Path legacyComparison,
                                                       Result candidate)
            throws IOException {
        ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .validateLegacyEvidence();
        Csv previous = Csv.read(legacyComparison);
        List<String> expectedRuns = List.of("LEGACY_ROUND_1", "LEGACY_ROUND_2",
                "RESIDENT_INITIAL", "RESIDENT_ROUND_2", "RESIDENT_ROUND_3",
                "RESIDENT_ROUND_4");
        List<String> actualRuns = previous.rows().stream()
                .map(row -> row.get("calibration_run")).toList();
        require(actualRuns.equals(expectedRuns),
                "Legacy resident comparison run set or order changed: " + actualRuns);

        StringBuilder csv = new StringBuilder(
                "calibration_run,configuration_basis,car_constant,pt_constant,"
                        + "bike_constant,walk_constant,final_iteration,qsim_horizon,"
                        + "original_calibration_cohort,resident_car_share_percent,"
                        + "resident_pt_share_percent,resident_bike_share_percent,"
                        + "resident_walk_share_percent,car_absolute_deviation_pp,"
                        + "pt_absolute_deviation_pp,bike_absolute_deviation_pp,"
                        + "walk_absolute_deviation_pp,sum_absolute_trip_share_deviation_pp,"
                        + "status_or_role\n");
        StringBuilder report = new StringBuilder(
                "# Final Legacy-R1 resident candidate comparison\n\n")
                .append("Physical trip shares of all Munich-resident main trips are the ")
                .append("primary comparison metric. Legacy outputs remain 43-hour ")
                .append("candidate evidence; the new row is the only 48-hour, 0--60 final ")
                .append("candidate using the fixed Legacy-R1 constants.\n\n")
                .append("| Run | Car | PT | Bike | Walk | Sum absolute deviation | Role/status |\n")
                .append("|---|---:|---:|---:|---:|---:|---|\n");
        for (Map<String, String> row : previous.rows()) {
            String run = row.get("calibration_run");
            csv.append(run).append(",preserved_comparison,")
                    .append(row.get("car_constant")).append(',')
                    .append(row.get("pt_constant")).append(',')
                    .append(row.get("bike_constant")).append(',')
                    .append(row.get("walk_constant")).append(',')
                    .append(row.get("final_iteration")).append(',')
                    .append(row.get("qsim_horizon")).append(',')
                    .append(row.get("original_calibration_cohort"));
            for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
                csv.append(',').append(row.get("resident_" + mode + "_share"));
            }
            for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
                csv.append(',').append(row.get(mode + "_absolute_deviation_pp"));
            }
            csv.append(',').append(row.get(
                            "sum_absolute_modal_share_deviation_pp"))
                    .append(',').append(row.get(
                            "candidate_for_one_final_48h_resident_validation"))
                    .append('\n');
            appendCandidateReportRow(report, run,
                    modeValue(row, "resident_car_share"),
                    modeValue(row, "resident_pt_share"),
                    modeValue(row, "resident_bike_share"),
                    modeValue(row, "resident_walk_share"),
                    number(row, "sum_absolute_modal_share_deviation_pp"),
                    row.get("candidate_for_one_final_48h_resident_validation"));
        }

        LinkedHashMap<String, Double> candidateShares = new LinkedHashMap<>();
        double sum = 0.0;
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            double share = candidate.modes().get(mode).finalTripShare();
            candidateShares.put(mode, share);
            sum += Math.abs(share - ResidentModeChoiceCalibrationTargets
                    .TRIP_SHARE_PERCENT.get(mode));
        }
        Map<String, Double> constants =
                ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.FIXED_CONSTANTS;
        csv.append("FINAL_LEGACY_R1_RESIDENT_CANDIDATE,fixed_legacy_r1_constants,")
                .append(number(constants.get("car"))).append(',')
                .append(number(constants.get("pt"))).append(',')
                .append(number(constants.get("bike"))).append(',')
                .append(number(constants.get("walk"))).append(",60,48:00:00,")
                .append("MUNICH_RESIDENT_ALL_TRIPS");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            csv.append(',').append(number(candidateShares.get(mode)));
        }
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            csv.append(',').append(number(Math.abs(candidateShares.get(mode)
                    - ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode))));
        }
        csv.append(',').append(number(sum)).append(',')
                .append(candidate.overallStatus()).append('\n');
        appendCandidateReportRow(report, "FINAL_LEGACY_R1_RESIDENT_CANDIDATE",
                candidateShares.get("car"), candidateShares.get("pt"),
                candidateShares.get("bike"), candidateShares.get("walk"), sum,
                candidate.overallStatus());
        report.append("\nFinal status: `").append(candidate.overallStatus())
                .append("`. `REVIEW_REQUIRED` denotes a technically valid run that does not ")
                .append("satisfy every convergence, target-fit and stuck-trip criterion. It ")
                .append("does not authorize another automatic constant update.\n");
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_final_candidate_comparison.csv"),
                csv, StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_final_candidate_comparison.md"),
                report, StandardCharsets.UTF_8);
    }

    private static double modeValue(Map<String, String> row, String field) {
        return number(row, field);
    }

    private static void appendCandidateReportRow(StringBuilder report, String run,
                                                 double car, double pt, double bike,
                                                 double walk, double sum, String status) {
        report.append("| ").append(run).append(" | ")
                .append(number(car)).append("% | ").append(number(pt)).append("% | ")
                .append(number(bike)).append("% | ").append(number(walk)).append("% | ")
                .append(number(sum)).append(" pp | ").append(status).append(" |\n");
    }

    static void requireFinalCandidateAnalysisFiles(Path analysis) {
        List<String> missing = FINAL_CANDIDATE_ANALYSIS_FILES.stream()
                .filter(name -> !Files.isRegularFile(analysis.resolve(name))).toList();
        require(missing.isEmpty(),
                "Final candidate analysis package is incomplete: " + missing);
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
