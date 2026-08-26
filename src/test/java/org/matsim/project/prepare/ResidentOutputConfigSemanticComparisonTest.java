package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings;

class ResidentOutputConfigSemanticComparisonTest {

    @Test
    void approvedOverridesAndRuntimeSwissDefaultsAreAccepted() throws Exception {
        assertDoesNotThrow(() -> ResidentOutputConfigSemanticComparison
                .requireEquivalent(expected(), actualOutput()));
    }

    @Test
    void harmlessParameterSetReorderingIsAccepted() throws Exception {
        Config expected = expected();
        Config actual = actualOutput();
        List<ConfigGroup> strategies = new ArrayList<>(
                actual.replanning().getParameterSets("strategysettings"));
        strategies.forEach(actual.replanning()::removeParameterSet);
        Collections.reverse(strategies);
        strategies.forEach(actual.replanning()::addParameterSet);

        assertDoesNotThrow(() -> ResidentOutputConfigSemanticComparison
                .requireEquivalent(expected, actual));
    }

    @Test
    void changedStrategyWeightFailsWithExactKey() throws Exception {
        Config actual = actualOutput();
        strategy(actual, "SubtourModeChoice",
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT).setWeight(0.2);

        IllegalStateException failure = assertRejected(expected(), actual);
        assertTrue(failure.getMessage().contains("strategyName=SubtourModeChoice"));
        assertTrue(failure.getMessage().contains("/@weight"));
        assertTrue(failure.getMessage().contains("expected=0.1, actual=0.2"));
    }

    @Test
    void changedStrategySubpopulationFails() throws Exception {
        Config actual = actualOutput();
        strategy(actual, "SubtourModeChoice",
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT)
                .setSubpopulation(ResidentCalibrationSubpopulations.REGIONAL_BACKGROUND);

        assertRejected(expected(), actual);
    }

    @Test
    void missingStrategyFails() throws Exception {
        Config actual = actualOutput();
        actual.replanning().removeParameterSet(strategy(actual, "SubtourModeChoice",
                ResidentCalibrationSubpopulations.MUNICH_RESIDENT));

        assertRejected(expected(), actual);
    }

    @Test
    void duplicatedStrategyFails() throws Exception {
        Config actual = actualOutput();
        actual.replanning().addParameterSet(new StrategySettings()
                .setStrategyName("SubtourModeChoice")
                .setSubpopulation(ResidentCalibrationSubpopulations.MUNICH_RESIDENT)
                .setWeight(0.1));

        IllegalStateException failure = assertRejected(expected(), actual);
        assertTrue(failure.getMessage().contains("#duplicate"));
    }

    @Test
    void changedScoringConstantFails() throws Exception {
        Config actual = actualOutput();
        actual.scoring().getModes().get("car").setConstant(1.0);

        assertRejected(expected(), actual);
    }

    @Test
    void changedInputPathFails() throws Exception {
        Config actual = actualOutput();
        actual.plans().setInputFile("different-population.xml.gz");

        assertRejected(expected(), actual);
    }

    @Test
    void changedSeedCapacityOrSimulationHorizonFails() throws Exception {
        Config seed = actualOutput();
        seed.global().setRandomSeed(seed.global().getRandomSeed() + 1);
        assertRejected(expected(), seed);

        Config capacity = actualOutput();
        capacity.qsim().setFlowCapFactor(capacity.qsim().getFlowCapFactor() + 0.01);
        assertRejected(expected(), capacity);

        Config horizon = actualOutput();
        horizon.qsim().setEndTime(horizon.qsim().getEndTime().seconds() + 3600);
        assertRejected(expected(), horizon);
    }

    @Test
    void additionalUnexpectedParameterSetFails() throws Exception {
        Config expected = expected();
        Config actual = actualOutput();
        ConfigGroup expectedFixture = new ConfigGroup("comparisonFixture");
        expectedFixture.addParam("flag", "unchanged");
        expected.addModule(expectedFixture);
        ConfigGroup actualFixture = new ConfigGroup("comparisonFixture");
        actualFixture.addParam("flag", "unchanged");
        ConfigGroup unexpected = new ConfigGroup("unexpectedSet");
        unexpected.addParam("id", "extra");
        actualFixture.addParameterSet(unexpected);
        actual.addModule(actualFixture);

        IllegalStateException failure = assertRejected(expected, actual);
        assertTrue(failure.getMessage().contains("unexpectedSet"));
    }

    private static Config expected() throws Exception {
        return RunMatsim2019ResidentModeChoiceIteration0Validation
                .applyApprovedOverrides(ValidateResidentModeChoiceCalibrationConfig
                        .loadAndValidateStructure(true));
    }

    private static Config actualOutput() throws Exception {
        Config actual = expected();
        actual.addModule(new SwissRailRaptorConfigGroup());
        return actual;
    }

    private static StrategySettings strategy(Config config, String name,
                                             String subpopulation) {
        return config.replanning().getStrategySettings().stream()
                .filter(setting -> name.equals(setting.getStrategyName()))
                .filter(setting -> subpopulation.equals(setting.getSubpopulation()))
                .findFirst().orElseThrow();
    }

    private static IllegalStateException assertRejected(Config expected, Config actual) {
        return assertThrows(IllegalStateException.class, () ->
                ResidentOutputConfigSemanticComparison.requireEquivalent(expected, actual));
    }
}
