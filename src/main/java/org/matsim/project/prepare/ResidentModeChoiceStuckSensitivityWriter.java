package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.AnalysisResult;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.MetricSnapshot;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.PlanEligibility;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.SpatialScope;

/** Final Run-13 primary and stuck-affected-trip sensitivity outputs. */
final class ResidentModeChoiceStuckSensitivityWriter {
    static final double MODAL_SHARE_REVIEW_THRESHOLD_PP = 0.5;
    static final double TOTAL_PKM_REVIEW_THRESHOLD_PERCENT = 1.0;

    private final Path analysis;

    ResidentModeChoiceStuckSensitivityWriter(Path output) {
        analysis = output.resolve("analysis");
    }

    Result write(AnalysisResult primaryResult, AnalysisResult sensitivityResult,
                 ResidentStuckMainTripTracker.Snapshot stuck) throws IOException {
        MetricSnapshot primary = primary(primaryResult);
        MetricSnapshot sensitivity = primary(sensitivityResult);
        long excluded = primary.mainTrips() - sensitivity.mainTrips();
        require(excluded == stuck.affectedMainTripCount(),
                "Sensitivity exclusion count differs from event-matched stuck main trips: "
                        + excluded + " versus " + stuck.affectedMainTripCount());
        double stuckTripShare = percent(stuck.affectedMainTripCount(), primary.mainTrips());
        double totalPkmDifferencePercent = relativeDifference(
                sensitivity.totalMainModePkm(), primary.totalMainModePkm());
        boolean review = stuckTripShare
                > ResidentModeChoiceStuckEventListener.STUCK_TRIP_REVIEW_THRESHOLD_PERCENT
                || Math.abs(totalPkmDifferencePercent) > TOTAL_PKM_REVIEW_THRESHOLD_PERCENT;
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            if (Math.abs(sensitivity.modalSharePercent(mode)
                    - primary.modalSharePercent(mode)) > MODAL_SHARE_REVIEW_THRESHOLD_PP) {
                review = true;
            }
        }
        Result result = new Result(stuck.events(), stuck.uniquePersons(),
                stuck.affectedMainTripCount(), stuckTripShare, totalPkmDifferencePercent,
                review ? "REVIEW_REQUIRED" : "PASS");
        Files.createDirectories(analysis);
        Files.writeString(analysis.resolve("resident_mode_choice_final_primary.csv"),
                variantCsv("PRIMARY_ALL_RESIDENT_TRIPS", primary), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_final_stuck_sensitivity.csv"),
                variantCsv("SENSITIVITY_EXCLUDING_STUCK_AFFECTED_TRIPS", sensitivity),
                StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_final_sensitivity_comparison.csv"),
                comparisonCsv(primary, sensitivity, result), StandardCharsets.UTF_8);
        Files.writeString(analysis.resolve(
                        "resident_mode_choice_final_sensitivity_report.md"),
                report(primary, sensitivity, result), StandardCharsets.UTF_8);
        return result;
    }

    static String variantCsv(String variant, MetricSnapshot metrics) {
        StringBuilder out = new StringBuilder(
                "variant,mode,trip_count,trip_share_percent,trip_target_percent,trip_difference_pp,raw_daily_sample_pkm,five_percent_annualised_million_pkm,absolute_annual_target_million_pkm,absolute_annual_difference_million_pkm,pkm_share_percent,normalized_pkm_target_percent,pkm_difference_pp,mean_trip_distance_km\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            double tripShare = metrics.modalSharePercent(mode);
            double tripTarget = ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get(mode);
            double pkm = metrics.mainModePkm(mode);
            double pkmShare = pkmShare(metrics, mode);
            double pkmTarget = ResidentModeChoiceCalibrationTargets
                    .NORMALIZED_PKM_SHARE_PERCENT.get(mode);
            double annualised = annualisedMillion(pkm);
            double annualTarget = ResidentModeChoiceCalibrationTargets
                    .ANNUAL_PKM_MILLION.get(mode);
            out.append(variant).append(',').append(mode).append(',')
                    .append(metrics.mainTripsByMode().getOrDefault(mode, 0L)).append(',')
                    .append(number(tripShare)).append(',').append(number(tripTarget)).append(',')
                    .append(number(tripShare - tripTarget)).append(',').append(number(pkm))
                    .append(',').append(number(annualised)).append(',')
                    .append(number(annualTarget)).append(',')
                    .append(number(annualised - annualTarget)).append(',')
                    .append(number(pkmShare)).append(',').append(number(pkmTarget)).append(',')
                    .append(number(pkmShare - pkmTarget)).append(',')
                    .append(number(metrics.meanTripLengthKm(mode))).append('\n');
        }
        return out.toString();
    }

    static String comparisonCsv(MetricSnapshot primary, MetricSnapshot sensitivity,
                                Result result) {
        StringBuilder out = new StringBuilder(
                "metric,mode,primary_value,sensitivity_value,difference,unit,review_threshold,status\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            double before = primary.modalSharePercent(mode);
            double after = sensitivity.modalSharePercent(mode);
            double difference = after - before;
            out.append("trip_modal_share,").append(mode).append(',')
                    .append(number(before)).append(',').append(number(after)).append(',')
                    .append(number(difference)).append(",percentage_points,")
                    .append(number(MODAL_SHARE_REVIEW_THRESHOLD_PP)).append(',')
                    .append(Math.abs(difference) > MODAL_SHARE_REVIEW_THRESHOLD_PP
                            ? "REVIEW_REQUIRED" : "PASS").append('\n');
        }
        out.append("total_main_mode_pkm,all,")
                .append(number(primary.totalMainModePkm())).append(',')
                .append(number(sensitivity.totalMainModePkm())).append(',')
                .append(number(result.totalPkmDifferencePercent()))
                .append(",percent,").append(number(TOTAL_PKM_REVIEW_THRESHOLD_PERCENT))
                .append(',').append(Math.abs(result.totalPkmDifferencePercent())
                        > TOTAL_PKM_REVIEW_THRESHOLD_PERCENT
                        ? "REVIEW_REQUIRED" : "PASS").append('\n');
        out.append("resident_stuck_main_trip_share,all,")
                .append(number(result.stuckTripSharePercent())).append(',')
                .append(number(result.stuckTripSharePercent())).append(",0.000000000,percent,")
                .append(number(ResidentModeChoiceStuckEventListener
                        .STUCK_TRIP_REVIEW_THRESHOLD_PERCENT)).append(',')
                .append(result.stuckTripSharePercent()
                        > ResidentModeChoiceStuckEventListener.STUCK_TRIP_REVIEW_THRESHOLD_PERCENT
                        ? "REVIEW_REQUIRED" : "PASS").append('\n');
        return out.toString();
    }

    private static String report(MetricSnapshot primary, MetricSnapshot sensitivity,
                                 Result result) {
        StringBuilder out = new StringBuilder("# Productive resident calibration sensitivity\n\n")
                .append("The primary result retains all ").append(primary.mainTrips())
                .append(" selected-plan main trips of Munich residents. The sensitivity result excludes only the ")
                .append(result.affectedMainTrips())
                .append(" main trips deterministically matched to a `PersonStuckEvent` in the final iteration; it does not remove persons or change the simulation. Empirical Schröder targets are interpreted against the primary physical-mode result.\n\n")
                .append("Resident stuck events: ").append(result.stuckEvents())
                .append("; unique affected residents: ").append(result.uniqueAffectedResidents())
                .append("; affected resident-trip share: ")
                .append(number(result.stuckTripSharePercent())).append("%.\n\n")
                .append("| Mode | Primary physical share | Sensitivity physical share | Difference |\n")
                .append("|---|---:|---:|---:|\n");
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            double before = primary.modalSharePercent(mode);
            double after = sensitivity.modalSharePercent(mode);
            out.append("| ").append(mode).append(" | ").append(number(before))
                    .append("% | ").append(number(after)).append("% | ")
                    .append(number(after - before)).append(" pp |\n");
        }
        out.append("\nTotal physical main-mode Pkm sensitivity: ")
                .append(number(result.totalPkmDifferencePercent())).append("%. Overall status: `")
                .append(result.status()).append("`. The thesis-specific review criteria are 1.0% for affected resident trips, 0.5 percentage points per modal share and 1.0% for total Pkm. They are review criteria, not universal MATSim standards, and never alter the model automatically.\n");
        return out.toString();
    }

    private static MetricSnapshot primary(AnalysisResult result) {
        return result.metrics(SpatialScope.ALL_TRIPS, PlanEligibility.ALL_PLANS);
    }

    private static double pkmShare(MetricSnapshot metrics, String mode) {
        return metrics.totalMainModePkm() == 0.0 ? Double.NaN
                : 100.0 * metrics.mainModePkm(mode) / metrics.totalMainModePkm();
    }

    private static double annualisedMillion(double pkm) {
        return pkm * ResidentModeChoiceCalibrationAnalysisWriter.SAMPLE_TO_POPULATION_FACTOR
                * ResidentModeChoiceCalibrationAnalysisWriter.DAYS_PER_YEAR_DIAGNOSTIC
                / 1_000_000.0;
    }

    private static double percent(long numerator, long denominator) {
        return denominator == 0 ? Double.NaN : 100.0 * numerator / denominator;
    }

    private static double relativeDifference(double after, double before) {
        return before == 0.0 ? Double.NaN : 100.0 * (after - before) / before;
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.9f", value) : "";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record Result(long stuckEvents, long uniqueAffectedResidents, long affectedMainTrips,
                  double stuckTripSharePercent, double totalPkmDifferencePercent,
                  String status) { }
}
