package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.AnalysisResult;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.DistanceSource;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.GroupKey;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.MetricSnapshot;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.PlanEligibility;
import org.matsim.project.prepare.ModeChoiceCalibrationAnalysis.SpatialScope;

class ResidentModeChoiceCalibrationRound1Test {

    @Test
    void formulaReproducesApprovedConstantsAndInitialAnalysisIsComplete() throws Exception {
        var validation = ResidentModeChoiceRound1Specification.validate();
        assertEquals(21, validation.iterations());
        assertEquals(137_540, validation.residentTrips());
        assertEquals(54, validation.affectedTrips());
        assertEquals(0.3918166796685144,
                ResidentModeChoiceRound1Specification.dampedUpdate("pt"), 1e-14);
        assertEquals(-0.10473495494051893,
                ResidentModeChoiceRound1Specification.dampedUpdate("bike"), 1e-14);
        assertEquals(0.5835223895513888,
                ResidentModeChoiceRound1Specification.dampedUpdate("walk"), 1e-14);
    }

    @Test
    void roundConfigContainsOnlyApprovedDifferences() throws Exception {
        Config production = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(false);
        Config round = ValidateResidentModeChoiceCalibrationRound1Config
                .loadAndValidateStructure(true);
        Set<String> differences = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(
                        RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(production),
                        RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round));
        assertEquals(ValidateResidentModeChoiceCalibrationRound1Config.APPROVED_DIFFERENCES,
                differences);
        assertEquals(40, round.controller().getLastIteration());
        assertEquals(48 * 3600.0, round.qsim().getEndTime().seconds(), 1e-12);
        assertEquals(ResidentModeChoiceRound1Specification.APPLIED_CONSTANTS,
                ValidateResidentModeChoiceCalibrationRound1Config.constants(round));
        assertFalse(Files.exists(ValidateResidentModeChoiceCalibrationRound1Config.OUTPUT));

        round.global().setRandomSeed(12);
        Set<String> unsafe = RunMatsim2019ResidentModeChoiceIteration0Validation.differences(
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(production),
                RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(round));
        assertTrue(unsafe.contains("module[global]/@randomSeed"));
        assertFalse(unsafe.equals(
                ValidateResidentModeChoiceCalibrationRound1Config.APPROVED_DIFFERENCES));
    }

    @Test
    void outputProtectionAndRunConfigurationsAreFailClosedAndSyntactic(
            @TempDir Path temp) throws Exception {
        assertThrows(IllegalStateException.class,
                () -> ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(temp));
        var builder = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder();
        for (String name : List.of(
                "R1A Validate 2019 Resident Mode Choice Calibration Round 1.run.xml",
                "R1B Run 2019 Resident Mode Choice Calibration Round 1.run.xml",
                "R1C Analyze 2019 Resident Mode Choice Calibration Round 1.run.xml")) {
            assertEquals("component", builder.parse(Path.of(".run", name).toFile())
                    .getDocumentElement().getTagName());
        }
    }

    @Test
    void lateWindowUsesExactlyIterations31To40AndStandaloneReportKeepsTen(
            @TempDir Path temp) throws Exception {
        List<AnalysisResult> results = new ArrayList<>();
        for (int iteration = 0; iteration <= 40; iteration++) {
            results.add(result(iteration, 33.5 + 0.01 * iteration));
        }
        ResidentModeChoiceCalibrationAnalysisWriter writer =
                new ResidentModeChoiceCalibrationAnalysisWriter(temp);
        writer.write(results, true);
        writer.writeStandaloneFinal(results.getLast());

        String late = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_late_iteration_statistics.csv"));
        assertTrue(late.contains("resident_trip_share,car,31,40,10,"));
        assertTrue(late.startsWith("metric,mode,first_iteration,last_iteration,iterations,"
                + "mean,minimum,maximum,range,linear_trend_per_iteration,final_value,"));
        String report = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_report.md"));
        assertTrue(report.contains("last 10 available iterations"));
        assertFalse(report.contains("last 1 available iterations"));
    }

    @Test
    void roundReviewUsesThesisCriteriaAndReportsSecondaryPkm(@TempDir Path temp)
            throws Exception {
        List<AnalysisResult> results = new ArrayList<>();
        for (int iteration = 0; iteration <= 40; iteration++) {
            results.add(result(iteration, 34.0));
        }
        new ResidentModeChoiceCalibrationAnalysisWriter(temp).write(results, true);
        Path stuck = temp.resolve("analysis/resident_stuck_events_by_iteration_and_mode.csv");
        StringBuilder csv = new StringBuilder(
                "iteration,routing_mode,event_count,unique_affected_residents,affected_resident_main_trips,resident_person_share_percent,resident_main_trip_share_percent,difference_from_iteration_0_events,difference_from_iteration_0_persons,difference_from_iteration_0_trips,cumulative_resident_events,review_status\n");
        for (int iteration = 0; iteration <= 40; iteration++) {
            csv.append(iteration).append(",all,1,1,1,0.001,0.001,0,0,0,")
                    .append(iteration + 1).append(",PASS\n");
        }
        Files.writeString(stuck, csv);
        var review = ResidentModeChoiceRound1Review.validateAndWrite(temp);
        assertEquals("PASS", review.status());
        assertTrue(Files.readString(temp.resolve(
                        "analysis/resident_mode_choice_round_1_convergence_review.csv"))
                .contains("car,34.000000000"));
        assertTrue(Files.readString(temp.resolve(
                        "analysis/resident_mode_choice_round_1_convergence_report.md"))
                .contains("iterations 31--40"));
    }

    @Test
    void convergenceOrStuckThresholdViolationRequiresReview(@TempDir Path temp)
            throws Exception {
        List<AnalysisResult> results = new ArrayList<>();
        for (int iteration = 0; iteration <= 40; iteration++) {
            results.add(result(iteration, 34.0 + 0.2 * iteration));
        }
        new ResidentModeChoiceCalibrationAnalysisWriter(temp).write(results, true);
        Path stuck = temp.resolve("analysis/resident_stuck_events_by_iteration_and_mode.csv");
        StringBuilder csv = new StringBuilder(
                "iteration,routing_mode,event_count,unique_affected_residents,affected_resident_main_trips,resident_person_share_percent,resident_main_trip_share_percent,difference_from_iteration_0_events,difference_from_iteration_0_persons,difference_from_iteration_0_trips,cumulative_resident_events,review_status\n");
        for (int iteration = 0; iteration <= 40; iteration++) {
            double share = iteration == 40 ? 1.000000001 : 0.5;
            csv.append(iteration).append(",all,1,1,1,0.001,").append(share)
                    .append(",0,0,0,").append(iteration + 1)
                    .append(iteration == 40 ? ",REVIEW_REQUIRED\n" : ",PASS\n");
        }
        Files.writeString(stuck, csv);
        var review = ResidentModeChoiceRound1Review.validateAndWrite(temp);
        assertEquals("REVIEW_REQUIRED", review.status());
        assertEquals("REVIEW_REQUIRED",
                review.modes().get("car").convergenceStatus());
    }

    private static AnalysisResult result(int iteration, double carShare) {
        long total = 10_000;
        long car = Math.round(carShare * 100);
        long pt = 2_400;
        long bike = 1_800;
        long walk = total - car - pt - bike;
        Map<String, Long> trips = Map.of(
                "car", car, "pt", pt, "bike", bike, "walk", walk);
        Map<String, Double> metres = Map.of(
                "car", car * 1_000.0, "pt", pt * 1_000.0,
                "bike", bike * 1_000.0, "walk", walk * 1_000.0);
        MetricSnapshot metrics = new MetricSnapshot(1, total, trips, trips, Map.of(), 0,
                trips, metres, metres,
                new EnumMap<>(Map.of(DistanceSource.ROUTE_REPORTED, total)), 0, 0);
        return new AnalysisResult(iteration,
                Map.of(new GroupKey(SpatialScope.ALL_TRIPS, PlanEligibility.ALL_PLANS),
                        metrics), 1, 0, Set.of());
    }
}
