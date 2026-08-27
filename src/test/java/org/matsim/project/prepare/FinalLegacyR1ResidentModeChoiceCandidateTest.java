package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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

class FinalLegacyR1ResidentModeChoiceCandidateTest {

    @Test
    void legacyEvidenceFixesConstantsWithoutLogarithmicRecalculation() throws Exception {
        var evidence = ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .validateLegacyEvidence();
        assertEquals(68_770, evidence.residents());
        assertEquals(137_540, evidence.residentTrips());
        assertEquals(31.900828850, evidence.sumAbsoluteDeviation(), 1e-12);
        assertEquals(Map.of("car", 0.0, "pt", 0.89,
                        "bike", -0.21, "walk", 0.78),
                ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.FIXED_CONSTANTS);
        String validator = Files.readString(Path.of("src/main/java/org/matsim/project/prepare/"
                + "ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.java"));
        assertFalse(validator.contains("Math.log"));
    }

    @Test
    void candidateDiffersFromRound4OnlyInRunControlAndFixedConstants()
            throws Exception {
        Config baseline = ValidateResidentModeChoiceCalibrationRound4Config
                .loadAndValidateStructure(false);
        Config candidate = ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .loadAndValidateStructure(true);
        Set<String> differences = RunMatsim2019ResidentModeChoiceIteration0Validation
                .differences(
                        RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(baseline),
                        RunMatsim2019ResidentModeChoiceIteration0Validation.snapshot(candidate));
        assertEquals(ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .APPROVED_DIFFERENCES, differences);
        assertEquals(baseline.plans().getInputFile(), candidate.plans().getInputFile());
        assertEquals("../munich_base_2023/munich-v1.0-5pct.plans.xml",
                candidate.plans().getInputFile());
        assertFalse(candidate.plans().getInputFile().contains("output"));
        assertEquals(0, candidate.controller().getFirstIteration());
        assertEquals(60, candidate.controller().getLastIteration());
        assertEquals(48 * 3600.0, candidate.qsim().getEndTime().seconds(), 1e-12);
        assertEquals(0.8,
                candidate.replanning().getFractionOfIterationsToDisableInnovation(), 1e-12);
        assertEquals(4, candidate.replanning().getMaxAgentPlanMemorySize());
        assertEquals(48, ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .INNOVATION_DISABLE_AFTER_ITERATION);
        assertEquals(51, ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .LATE_FIRST_ITERATION);
        assertEquals(60, ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .LATE_LAST_ITERATION);
        assertEquals(ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.FIXED_CONSTANTS,
                ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.constants(candidate));
        assertEquals(Map.of("car", 34.0, "pt", 24.0,
                        "bike", 18.0, "walk", 24.0),
                ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT);
        assertFalse(Files.exists(
                ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.OUTPUT));
    }

    @Test
    void outputProtectionAndF1RunConfigurationsAreFailClosedAndSyntactic(
            @TempDir Path temp) throws Exception {
        assertThrows(IllegalStateException.class,
                () -> ValidateResidentModeChoiceCalibrationConfig
                        .requireOutputAbsent(temp));
        var builder = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder();
        for (String name : List.of(
                "F1A Validate Final Legacy-R1 Resident Mode Choice Candidate.run.xml",
                "F1B Run Final Legacy-R1 Resident Mode Choice Candidate.run.xml",
                "F1C Validate and Summarize Final Legacy-R1 Resident Mode Choice Candidate.run.xml")) {
            assertEquals("component", builder.parse(Path.of(".run", name).toFile())
                    .getDocumentElement().getTagName());
        }
    }

    @Test
    void finalReviewUsesLateWindowAndRequiresCandidateSpecificFiles(
            @TempDir Path temp) throws Exception {
        Path incomplete = Files.createDirectories(temp.resolve("incomplete"));
        for (String name : List.of("resident_mode_choice_iteration_metrics.csv",
                "resident_mode_choice_late_iteration_statistics.csv",
                "resident_mode_choice_final_summary.csv",
                "resident_mode_choice_report.md",
                "resident_stuck_events_by_iteration_and_mode.csv")) {
            Files.writeString(incomplete.resolve(name), "generic");
        }
        assertThrows(IllegalStateException.class,
                () -> ResidentModeChoiceRound2Review
                        .requireFinalCandidateAnalysisFiles(incomplete));

        Path output = temp.resolve("candidate");
        List<AnalysisResult> results = new ArrayList<>();
        for (int iteration = 0; iteration <= 60; iteration++) {
            results.add(result(iteration));
        }
        new ResidentModeChoiceCalibrationAnalysisWriter(output).write(results, true);
        writeStuckHistory(output);
        for (String name : List.of("resident_mode_choice_final_primary.csv",
                "resident_mode_choice_final_stuck_sensitivity.csv",
                "resident_mode_choice_final_sensitivity_comparison.csv",
                "resident_mode_choice_final_sensitivity_report.md")) {
            Files.writeString(output.resolve("analysis").resolve(name), "fixture");
        }
        var review = ResidentModeChoiceRound2Review.validateAndWriteFinalCandidate(
                output, ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                        .LEGACY_REANALYSIS.resolve(
                        "legacy_resident_mode_choice_comparison.csv"));
        assertEquals("CALIBRATED", review.overallStatus());
        ResidentModeChoiceRound2Review.requireFinalCandidateAnalysisFiles(
                output.resolve("analysis"));
        String comparison = Files.readString(output.resolve(
                "analysis/resident_mode_choice_final_candidate_comparison.csv"));
        assertTrue(comparison.contains("LEGACY_ROUND_1,preserved_comparison"));
        assertTrue(comparison.contains(
                "FINAL_LEGACY_R1_RESIDENT_CANDIDATE,fixed_legacy_r1_constants"));
    }

    @Test
    void validationDoesNotModifyPreviousConfigsOrAnalysisEvidence() throws Exception {
        Map<Path, String> before = previousArtifactHashes();
        ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig
                .loadAndValidateStructure(true);
        assertEquals(before, previousArtifactHashes());
    }

    private static Map<Path, String> previousArtifactHashes() throws Exception {
        LinkedHashMap<Path, String> hashes = new LinkedHashMap<>();
        for (Path config : List.of(
                ValidateResidentModeChoiceCalibrationConfig.CONFIG,
                ValidateResidentModeChoiceCalibrationRound1Config.CONFIG,
                ValidateResidentModeChoiceCalibrationRound2Config.CONFIG,
                ValidateResidentModeChoiceCalibrationRound3Config.CONFIG,
                ValidateResidentModeChoiceCalibrationRound4Config.CONFIG)) {
            hashes.put(config, ValidateResidentModeChoiceCalibrationConfig.sha256(config));
        }
        for (Path root : List.of(
                ValidateFinalLegacyR1ResidentModeChoiceCandidateConfig.LEGACY_REANALYSIS,
                ValidateResidentModeChoiceCalibrationConfig.OUTPUT.resolve("analysis"),
                ValidateResidentModeChoiceCalibrationRound2Config.OUTPUT.resolve("analysis"),
                ValidateResidentModeChoiceCalibrationRound3Config.OUTPUT.resolve("analysis"),
                ValidateResidentModeChoiceCalibrationRound4Config.OUTPUT.resolve("analysis"))) {
            try (var paths = Files.list(root)) {
                for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                    hashes.put(path, ValidateResidentModeChoiceCalibrationConfig.sha256(path));
                }
            }
        }
        return Map.copyOf(hashes);
    }

    private static void writeStuckHistory(Path output) throws Exception {
        Path stuck = output.resolve(
                "analysis/resident_stuck_events_by_iteration_and_mode.csv");
        StringBuilder csv = new StringBuilder(
                "iteration,routing_mode,event_count,unique_affected_residents,affected_resident_main_trips,resident_person_share_percent,resident_main_trip_share_percent,difference_from_iteration_0_events,difference_from_iteration_0_persons,difference_from_iteration_0_trips,cumulative_resident_events,review_status\n");
        for (int iteration = 0; iteration <= 60; iteration++) {
            csv.append(iteration).append(",all,1,1,1,0.001,0.001,0,0,0,")
                    .append(iteration + 1).append(",PASS\n");
        }
        Files.writeString(stuck, csv);
    }

    private static AnalysisResult result(int iteration) {
        long total = 10_000;
        Map<String, Long> trips = Map.of(
                "car", 3_400L, "pt", 2_400L, "bike", 1_800L, "walk", 2_400L);
        Map<String, Double> metres = Map.of(
                "car", 3_400_000.0, "pt", 2_400_000.0,
                "bike", 1_800_000.0, "walk", 2_400_000.0);
        MetricSnapshot metrics = new MetricSnapshot(1, total, trips, trips, Map.of(), 0,
                trips, metres, metres,
                new EnumMap<>(Map.of(DistanceSource.ROUTE_REPORTED, total)), 0, 0);
        return new AnalysisResult(iteration,
                Map.of(new GroupKey(SpatialScope.ALL_TRIPS, PlanEligibility.ALL_PLANS),
                        metrics), 1, 0, Set.of());
    }
}
