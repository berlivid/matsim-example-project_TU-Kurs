package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;

class ModeChoiceCalibrationConfigTest {
    private static final Path BAU_CONFIG = Path.of("scenarios/munich_bau_2040/config_bau.xml");
    private static final Path FAST_TRACK_CONFIG = Path.of(
            "scenarios/munich_fast_track_2040/config_fast_track.xml");
    private static Config config;

    @BeforeAll
    static void loadValidatedConfig() throws Exception {
        config = ValidateModeChoiceCalibrationConfig.loadAndValidate();
    }

    @Test
    void calibrationConfigLoadsWithExpectedInputs() {
        assertTrue(Files.isRegularFile(ValidateModeChoiceCalibrationConfig.CONFIG));
        assertEquals("input_transit/network-with-pt.xml.gz", config.network().getInputFile());
        assertEquals("../munich_base_2023/munich-v1.0-5pct.plans.xml",
                config.plans().getInputFile());
        assertEquals("input_transit/transitSchedule.xml.gz",
                config.transit().getTransitScheduleFile());
        assertEquals("input_transit/transitVehicles.xml.gz",
                config.transit().getVehiclesFile());
        assertTrue(config.transit().isUseTransit());
    }

    @Test
    void exactlyExpectedStrategiesAndWeightsAreActive() {
        assertEquals(ValidateModeChoiceCalibrationConfig.STRATEGY_WEIGHTS,
                ValidateModeChoiceCalibrationConfig.strategyMap(config));
        assertEquals(1.0, config.replanning().getStrategySettings().stream()
                .mapToDouble(s -> s.getWeight()).sum(), 1e-12);
        assertEquals(4, config.replanning().getMaxAgentPlanMemorySize());
        assertEquals("WorstPlanSelector", config.replanning().getPlanSelectorForRemoval());
        assertEquals(0.8, config.replanning().getFractionOfIterationsToDisableInnovation(), 1e-12);
    }

    @Test
    void subtourModeChoiceOffersOnlyApprovedModes() {
        assertEquals(List.of("car", "pt", "walk", "bike"),
                Arrays.asList(config.subtourModeChoice().getModes()));
        assertEquals(Set.of("car", "bike"),
                Set.of(config.subtourModeChoice().getChainBasedModes()));
        assertEquals("fromSpecifiedModesToSpecifiedModes",
                config.subtourModeChoice().getBehavior().toString());
        assertFalse(config.subtourModeChoice().considerCarAvailability());
        assertEquals(0.0, config.subtourModeChoice().getProbaForRandomSingleTripMode(), 1e-12);
        assertEquals(0.0, config.subtourModeChoice().getCoordDistance(), 1e-12);
    }

    @Test
    void initialConstantsAreExplicitlyUncalibrated() {
        for (String mode : List.of("car", "pt", "walk", "bike")) {
            assertEquals(0.0, config.scoring().getModes().get(mode).getConstant(), 1e-12);
        }
        assertTrue(config.scoring().getModes().keySet().containsAll(Set.of("ride", "other")));
        assertFalse(Arrays.asList(config.subtourModeChoice().getModes()).contains("ride"));
        assertFalse(Arrays.asList(config.subtourModeChoice().getModes()).contains("other"));
    }

    @Test
    void seedIterationsCapacityAndOutputProtectionAreFixed() {
        assertEquals(4711, config.global().getRandomSeed());
        assertEquals(0, config.controller().getFirstIteration());
        assertEquals(20, config.controller().getLastIteration());
        assertEquals(4, config.global().getNumberOfThreads());
        assertEquals(2, config.qsim().getNumberOfThreads());
        assertEquals(0.05, config.qsim().getFlowCapFactor(), 1e-12);
        assertEquals(0.05, config.qsim().getStorageCapFactor(), 1e-12);
        assertEquals(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                config.controller().getOverwriteFileSetting());
        assertEquals("scenarios/munich_calibration_2019/output/mode-choice-initial",
                config.controller().getOutputDirectory());

        Config validation = ConfigUtils.loadConfig(
                ValidateModeChoiceCalibrationConfig.INPUT_VALIDATION_CONFIG.toString());
        assertNotEquals(validation.controller().getOutputDirectory(),
                config.controller().getOutputDirectory());
    }

    @Test
    void modeChoiceIsActiveOnlyInDedicatedCalibrationConfig() {
        Config validation = ConfigUtils.loadConfig(
                ValidateModeChoiceCalibrationConfig.INPUT_VALIDATION_CONFIG.toString());
        assertFalse(hasModeChoice(validation));
        assertTrue(hasModeChoice(config));
        assertFalse(hasModeChoice(ConfigUtils.loadConfig(BAU_CONFIG.toString())));
        assertFalse(hasModeChoice(ConfigUtils.loadConfig(FAST_TRACK_CONFIG.toString())));
    }

    @Test
    void bauAndFastTrackConfigsRetainApprovedHashes() throws Exception {
        assertEquals("650A560AC9B170EC11E63AF1E07C83810ABBD998C7AFC0BB51DBCDBA006D7DEB",
                sha256(BAU_CONFIG));
        assertEquals("7A0ABF75489FD657B10E4F4ED7172D55D60FFAC4DC6299BEF37711D731BAB6F4",
                sha256(FAST_TRACK_CONFIG));
    }

    private static boolean hasModeChoice(Config candidate) {
        return candidate.replanning().getStrategySettings().stream()
                .map(s -> s.getStrategyName() == null ? "" : s.getStrategyName())
                .anyMatch(name -> name.toLowerCase().contains("modechoice"));
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream input = new DigestInputStream(
                new BufferedInputStream(Files.newInputStream(file)), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }
}
