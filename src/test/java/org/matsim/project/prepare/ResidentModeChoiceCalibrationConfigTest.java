package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;

class ResidentModeChoiceCalibrationConfigTest {
    private static Config config;

    @BeforeAll
    static void loadStructure() throws Exception {
        config = ValidateResidentModeChoiceCalibrationConfig
                .loadAndValidateStructure(false);
    }

    @Test
    void strategiesAreExactlyScopedByRuntimeSubpopulation() {
        Map<String, Map<String, Double>> strategies =
                ValidateResidentModeChoiceCalibrationConfig.strategyMap(config);
        assertEquals(Map.of("ChangeExpBeta", 0.8, "ReRoute", 0.1,
                        "SubtourModeChoice", 0.1),
                strategies.get(ResidentCalibrationSubpopulations.MUNICH_RESIDENT));
        assertEquals(Map.of("ChangeExpBeta", 0.9, "ReRoute", 0.1),
                strategies.get(ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND));
        assertEquals(Map.of("ChangeExpBeta", 0.9, "ReRoute", 0.1),
                strategies.get(ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND));
        assertTrue(config.replanning().getStrategySettings().stream()
                .noneMatch(setting -> setting.getSubpopulation() == null));
        assertTrue(config.replanning().getStrategySettings().stream()
                .filter(setting -> "SubtourModeChoice".equals(setting.getStrategyName()))
                .allMatch(setting -> ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(
                        setting.getSubpopulation())));
    }

    @Test
    void everySubpopulationHasUnitStrategyWeightAndBackgroundCannotChangeMode() {
        var strategies = ValidateResidentModeChoiceCalibrationConfig.strategyMap(config);
        strategies.forEach((subpopulation, settings) -> assertEquals(1.0,
                settings.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-12));
        for (String background : java.util.List.of(
                ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND,
                ResidentCalibrationSubpopulations.UNRESOLVED_BACKGROUND)) {
            assertEquals(java.util.Set.of("ChangeExpBeta", "ReRoute"),
                    strategies.get(background).keySet());
        }
    }

    @Test
    void stableSubtourBehaviorIsConfiguredAndOpenTourRemainsRejected() throws Exception {
        assertEquals(java.util.List.of("car", "pt", "walk", "bike"),
                Arrays.asList(config.subtourModeChoice().getModes()));
        assertEquals(java.util.Set.of("car", "bike"),
                java.util.Set.of(config.subtourModeChoice().getChainBasedModes()));
        assertEquals("fromSpecifiedModesToSpecifiedModes",
                config.subtourModeChoice().getBehavior().toString());
        assertFalse(config.subtourModeChoice().considerCarAvailability());
        assertEquals(0.0, config.subtourModeChoice().getProbaForRandomSingleTripMode(), 1e-12);
        String xml = Files.readString(ValidateResidentModeChoiceCalibrationConfig.CONFIG)
                .toLowerCase(java.util.Locale.ROOT);
        assertFalse(xml.contains("betweenallandfewerconstraints"));
        assertFalse(xml.contains("open_tour"));
        assertFalse(xml.contains("open-tour"));
    }

    @Test
    void runSettingsInputsAndOutputProtectionAreFixed() {
        assertEquals("munich-calibration-2019-resident-initial",
                config.controller().getRunId());
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(20, config.controller().getLastIteration());
        assertEquals(4711, config.global().getRandomSeed());
        assertEquals(0.05, config.qsim().getFlowCapFactor(), 1e-12);
        assertEquals(0.05, config.qsim().getStorageCapFactor(), 1e-12);
        assertTrue(config.qsim().getEndTime().isDefined());
        assertEquals(48 * 3600.0, config.qsim().getEndTime().seconds(), 1e-12);
        assertEquals(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                config.controller().getOverwriteFileSetting());
        assertEquals(ValidateResidentModeChoiceCalibrationConfig.OUTPUT.toString()
                        .replace('\\', '/'),
                config.controller().getOutputDirectory().replace('\\', '/'));
        for (String mode : ResidentModeChoiceCalibrationTargets.MODES) {
            assertEquals(0.0, config.scoring().getModes().get(mode).getConstant(), 1e-12);
        }
    }

    @Test
    void authoritativeTargetsAreExactAndNormalized() {
        ResidentModeChoiceCalibrationTargets.validate();
        assertEquals(34.0, ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get("car"));
        assertEquals(24.0, ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get("pt"));
        assertEquals(18.0, ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get("bike"));
        assertEquals(24.0, ResidentModeChoiceCalibrationTargets.TRIP_SHARE_PERCENT.get("walk"));
        assertEquals(10_637.49,
                ResidentModeChoiceCalibrationTargets.ANNUAL_PKM_MILLION.get("car"));
        assertEquals(62.945329,
                ResidentModeChoiceCalibrationTargets.NORMALIZED_PKM_SHARE_PERCENT.get("car"));
        assertEquals(100.0, ResidentModeChoiceCalibrationTargets
                .NORMALIZED_PKM_SHARE_PERCENT.values().stream()
                .mapToDouble(Double::doubleValue).sum(), 1e-12);
    }

    @Test
    void outputProtectionRefusesAnyExistingPath(@TempDir Path temp) {
        assertThrows(IllegalStateException.class,
                () -> ValidateResidentModeChoiceCalibrationConfig.requireOutputAbsent(temp));
    }

    @Test
    void authoritativeCohortAndAllResidentTripsRemainExactAndSourceIsByteIdentical()
            throws Exception {
        Path population = AnalyzeMunichResidentCohort.resolvePopulation(config);
        String before = ValidateResidentModeChoiceCalibrationConfig.sha256(population);
        var result = ValidateResidentModeChoiceCalibrationConfig
                .validateAuthoritativeCohort(config);
        String after = ValidateResidentModeChoiceCalibrationConfig.sha256(population);
        assertEquals(before, after);
        assertEquals(68_770, result.residents());
        assertEquals(147_655, result.nonResidents());
        assertEquals(107_618, result.unresolvedPersons());
        assertEquals(137_540, result.residentMainTrips());
        assertEquals(137_540, result.spatialCategorySum());
    }
}
