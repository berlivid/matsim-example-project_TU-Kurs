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

/** Fail-closed late-iteration and stuck-trip review for productive Round 1. */
final class ResidentModeChoiceRound1Review {
    static final int FIRST_LATE_ITERATION = 31;
    static final int LAST_LATE_ITERATION = 40;

    private ResidentModeChoiceRound1Review() { }

    static Result validateAndWrite(Path output) throws IOException {
        Path analysis = output.resolve("analysis");
        Csv late = Csv.read(analysis.resolve(
                "resident_mode_choice_late_iteration_statistics.csv"));
        Csv stuck = Csv.read(analysis.resolve(
                "resident_stuck_events_by_iteration_and_mode.csv"));
        LinkedHashMap<String, ModeReview> modes = new LinkedHashMap<>();
        boolean reviewRequired = false;
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            Map<String, String> trip = unique(late, "resident_trip_share", mode);
            Map<String, String> pkm = unique(late, "resident_pkm_share", mode);
            validateLateIdentity(trip);
            validateLateIdentity(pkm);
            double range = number(trip, "range");
            double trend = number(trip, "linear_trend_per_iteration");
            String convergenceStatus = Math.abs(trend)
                    <= ResidentModeChoiceCalibrationAnalysisWriter
                    .LATE_TREND_REVIEW_THRESHOLD_PP_PER_ITERATION
                    && range <= ResidentModeChoiceCalibrationAnalysisWriter
                    .LATE_RANGE_REVIEW_THRESHOLD_PP
                    ? "CONVERGED" : "NOT_CONVERGED";
            ModeReview review = new ModeReview(mode,
                    number(trip, "mean"), number(trip, "minimum"),
                    number(trip, "maximum"), range, trend,
                    number(trip, "final_value"), number(trip, "target_value"),
                    number(trip, "difference_to_target"),
                    number(pkm, "final_value"), number(pkm, "target_value"),
                    number(pkm, "difference_to_target"), convergenceStatus);
            require(Math.abs(review.tripTarget()
                            - ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode))
                            < 1e-9,
                    "Round-1 trip target changed for " + mode);
            require(Math.abs(review.pkmTarget()
                            - ResidentModeChoiceCalibrationTargets
                            .NORMALIZED_PKM_SHARE_PERCENT.get(mode)) < 1e-9,
                    "Round-1 Pkm target changed for " + mode);
            reviewRequired |= "NOT_CONVERGED".equals(review.convergenceStatus());
            modes.put(mode, review);
        }

        double maximumStuckTripShare = stuck.rows().stream()
                .filter(row -> "all".equals(row.get("routing_mode")))
                .filter(row -> {
                    int iteration = Integer.parseInt(row.get("iteration"));
                    return iteration >= FIRST_LATE_ITERATION
                            && iteration <= LAST_LATE_ITERATION;
                }).mapToDouble(row -> number(row, "resident_main_trip_share_percent"))
                .max().orElseThrow(() -> new IllegalStateException(
                        "No all-mode resident stuck rows for iterations 31..40"));
        long lateStuckRows = stuck.rows().stream()
                .filter(row -> "all".equals(row.get("routing_mode")))
                .filter(row -> {
                    int iteration = Integer.parseInt(row.get("iteration"));
                    return iteration >= FIRST_LATE_ITERATION
                            && iteration <= LAST_LATE_ITERATION;
                }).count();
        require(lateStuckRows == 10,
                "Round-1 stuck history must contain exactly iterations 31..40");
        boolean stuckReview = maximumStuckTripShare
                > ResidentModeChoiceStuckEventListener.STUCK_TRIP_REVIEW_THRESHOLD_PERCENT;
        reviewRequired |= stuckReview;
        Result result = new Result(Map.copyOf(modes), maximumStuckTripShare,
                reviewRequired ? "REVIEW_REQUIRED" : "PASS");
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_1_convergence_review.csv"),
                csv(result), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_round_1_convergence_report.md"),
                report(result), StandardCharsets.UTF_8);
        return result;
    }

    static String csv(Result result) {
        StringBuilder out = new StringBuilder(
                "mode,late_mean_trip_share_percent,late_minimum_trip_share_percent,late_maximum_trip_share_percent,late_range_pp,late_trend_pp_per_iteration,final_trip_share_percent,trip_target_percent,trip_difference_pp,final_pkm_share_percent,pkm_target_percent,pkm_difference_pp,convergence_status\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            ModeReview row = result.modes().get(mode);
            out.append(mode).append(',').append(number(row.lateMean())).append(',')
                    .append(number(row.lateMinimum())).append(',')
                    .append(number(row.lateMaximum())).append(',')
                    .append(number(row.lateRange())).append(',')
                    .append(number(row.lateTrend())).append(',')
                    .append(number(row.finalTripShare())).append(',')
                    .append(number(row.tripTarget())).append(',')
                    .append(number(row.tripDifference())).append(',')
                    .append(number(row.finalPkmShare())).append(',')
                    .append(number(row.pkmTarget())).append(',')
                    .append(number(row.pkmDifference())).append(',')
                    .append(row.convergenceStatus()).append('\n');
        }
        return out.toString();
    }

    private static String report(Result result) {
        StringBuilder out = new StringBuilder("# Resident mode-choice calibration Round 1 review\n\n")
                .append("Late-iteration evaluation uses exactly iterations 31--40. Physical trip shares are primary; normalized physical Pkm shares are secondary plausibility indicators. Absolute annual Pkm are not used to adjust constants.\n\n")
                .append("| Mode | Late mean | Minimum | Maximum | Range | Trend/iteration | Final | Trip-target difference | Final Pkm share | Pkm-target difference | Status |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            ModeReview row = result.modes().get(mode);
            out.append("| ").append(mode).append(" | ").append(number(row.lateMean()))
                    .append("% | ").append(number(row.lateMinimum())).append("% | ")
                    .append(number(row.lateMaximum())).append("% | ")
                    .append(number(row.lateRange())).append(" pp | ")
                    .append(number(row.lateTrend())).append(" pp | ")
                    .append(number(row.finalTripShare())).append("% | ")
                    .append(number(row.tripDifference())).append(" pp | ")
                    .append(number(row.finalPkmShare())).append("% | ")
                    .append(number(row.pkmDifference())).append(" pp | ")
                    .append(row.convergenceStatus()).append(" |\n");
        }
        out.append("\nMaximum resident stuck-trip share in iterations 31--40: ")
                .append(number(result.maximumLateStuckTripSharePercent()))
                .append("%. Overall status: `").append(result.status()).append("`.\n\n")
                .append("The study-specific review criteria are |trend| <= 0.10 percentage points per iteration, late range <= 1.0 percentage point, and resident stuck-trip share <= 1.0%. A violation requires review and never changes constants automatically.\n");
        return out.toString();
    }

    private static void validateLateIdentity(Map<String, String> row) {
        require(Integer.parseInt(row.get("first_iteration")) == FIRST_LATE_ITERATION
                        && Integer.parseInt(row.get("last_iteration")) == LAST_LATE_ITERATION
                        && Integer.parseInt(row.get("iterations")) == 10,
                "Late statistics must use exactly iterations 31..40: " + row);
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
                  String status) { }

    record ModeReview(String mode, double lateMean, double lateMinimum,
                      double lateMaximum, double lateRange, double lateTrend,
                      double finalTripShare, double tripTarget, double tripDifference,
                      double finalPkmShare, double pkmTarget, double pkmDifference,
                      String convergenceStatus) { }

    private record Csv(List<Map<String, String>> rows) {
        static Csv read(Path path) throws IOException {
            require(Files.isRegularFile(path), "Missing Round-1 analysis file: " + path);
            List<String> lines = Files.readAllLines(path);
            require(!lines.isEmpty(), "Empty Round-1 analysis file: " + path);
            List<String> header = List.of(lines.getFirst().split(",", -1));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int line = 1; line < lines.size(); line++) {
                if (lines.get(line).isBlank()) continue;
                String[] fields = lines.get(line).split(",", -1);
                require(fields.length == header.size(),
                        "Malformed Round-1 CSV row " + (line + 1) + " in " + path);
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
