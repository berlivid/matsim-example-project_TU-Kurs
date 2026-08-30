package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.Config;
import org.matsim.core.controler.OutputDirectoryHierarchy;

class Production2040ConfigsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void transfersEveryFrozenParameterAndKeepsWalkAsReference() {
        Config bau = buildBau();
        Config fastTrack = buildFastTrack();
        ValidateProduction2040Configs.validateLoadedConfigs(bau, fastTrack, true);
        Map<String, String> actual = ValidateProduction2040Configs.extractConfigBackedSharedParameters(bau);
        assertEquals(135, actual.size());
        assertSameDouble("-0.35175057259662179", actual.get("calibration.asc.car"));
        assertSameDouble("0.16187543976517921", actual.get("calibration.asc.pt"));
        assertSameDouble("-1.2617442557140233", actual.get("calibration.asc.bike"));
        assertSameDouble("0.0", actual.get("calibration.asc.walk"));
        assertEquals(0.0, bau.scoring().getModes().get("walk").getConstant());
        assertEquals(Set.of("car", "pt", "walk", "bike"), Set.of(bau.subtourModeChoice().getModes()));
        assertFalse(Set.of(bau.subtourModeChoice().getModes()).contains("ride"));
    }

    @Test
    void selectsTheCorrectScenarioInputs() {
        Config bau = buildBau();
        assertEquals("population_2040.xml", bau.plans().getInputFile());
        assertEquals("input_transit/network-with-pt.xml.gz", bau.network().getInputFile());
        assertEquals("input_transit/transitSchedule.xml.gz", bau.transit().getTransitScheduleFile());
        assertEquals("input_transit/transitVehicles.xml.gz", bau.transit().getVehiclesFile());

        Config fastTrack = buildFastTrack();
        assertEquals("population_2040_fast_track.xml", fastTrack.plans().getInputFile());
        assertEquals("input_transit/network-with-pt.xml.gz", fastTrack.network().getInputFile());
        assertEquals("input_transit/transitSchedule.xml.gz", fastTrack.transit().getTransitScheduleFile());
        assertEquals("input_transit/transitVehicles.xml.gz", fastTrack.transit().getVehiclesFile());
    }

    @Test
    void acceptsOnlyTheDocumentedScenarioDifferences() {
        ValidateProduction2040Configs.validateLoadedConfigs(buildBau(), buildFastTrack(), true);
        assertEquals(13, Production2040Contract.loadAndValidate().allowedDifferences().size());
    }

    @Test
    void rejectsChangedScoring() {
        Config fastTrack = buildFastTrack();
        fastTrack.scoring().getModes().get("car").setConstant(-0.1);
        assertThrows(IllegalStateException.class,
                () -> ValidateProduction2040Configs.validateLoadedConfigs(buildBau(), fastTrack, true));
    }

    @Test
    void rejectsChangedSeed() {
        Config fastTrack = buildFastTrack();
        fastTrack.global().setRandomSeed(4712);
        assertThrows(IllegalStateException.class,
                () -> ValidateProduction2040Configs.validateLoadedConfigs(buildBau(), fastTrack, true));
    }

    @Test
    void rejectsAnUnknownAdditionalDifference() {
        Config fastTrack = buildFastTrack();
        fastTrack.createModule("unexpectedProductionDifference").addParam("value", "not-allowed");
        assertThrows(RuntimeException.class,
                () -> ValidateProduction2040Configs.validateLoadedConfigs(buildBau(), fastTrack, true));
    }

    @Test
    void rejectsSwappedScenarioInputs() {
        Config fastTrack = buildFastTrack();
        fastTrack.plans().setInputFile("population_2040.xml");
        assertThrows(IllegalStateException.class,
                () -> ValidateProduction2040Configs.validateLoadedConfigs(buildBau(), fastTrack, true));
    }

    @Test
    void rejectsAWrongHash() throws Exception {
        Path protectedCopy = temporaryDirectory.resolve("protected.bin");
        Files.writeString(protectedCopy, "protected", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> Production2040Contract.requireHash(protectedCopy,
                Production2040Contract.HashMethod.RAW_SHA256, "00".repeat(32)));
    }

    @Test
    void round5TextHashIsIdenticalForLfAndCrlf() throws Exception {
        String source = Files.readString(Production2040Contract.ROUND_5_CONFIG,
                StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        Path lf = temporaryDirectory.resolve("round5-lf.xml");
        Path crlf = temporaryDirectory.resolve("round5-crlf.xml");
        Files.writeString(lf, source, StandardCharsets.UTF_8);
        Files.writeString(crlf, source.replace("\n", "\r\n"), StandardCharsets.UTF_8);

        assertEquals(Production2040Contract.ROUND_5_SHA256,
                Production2040Contract.sha256(lf,
                        Production2040Contract.HashMethod.CANONICAL_UTF8_LF_SHA256));
        assertEquals(Production2040Contract.ROUND_5_SHA256,
                Production2040Contract.sha256(crlf,
                        Production2040Contract.HashMethod.CANONICAL_UTF8_LF_SHA256));
        Production2040Contract.requireRound5ConfigHash(lf);
        Production2040Contract.requireRound5ConfigHash(crlf);
    }

    @Test
    void round5TextHashStillRejectsAnXmlParameterChange() throws Exception {
        String source = Files.readString(Production2040Contract.ROUND_5_CONFIG,
                StandardCharsets.UTF_8);
        String changed = source.replace("value=\"-0.35175057259662179\"",
                "value=\"-0.35175057259662178\"");
        assertNotEquals(source, changed, "Test fixture must change the car ASC parameter");
        Path changedConfig = temporaryDirectory.resolve("round5-changed.xml");
        Files.writeString(changedConfig, changed, StandardCharsets.UTF_8);

        assertNotEquals(Production2040Contract.ROUND_5_SHA256,
                Production2040Contract.sha256(changedConfig,
                        Production2040Contract.HashMethod.CANONICAL_UTF8_LF_SHA256));
        assertThrows(IllegalStateException.class,
                () -> Production2040Contract.requireRound5ConfigHash(changedConfig));
    }

    @Test
    void protectsDistinctAbsentOutputDirectories() {
        Config bau = buildBau();
        Config fastTrack = buildFastTrack();
        assertEquals(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                bau.controller().getOverwriteFileSetting());
        assertEquals(OutputDirectoryHierarchy.OverwriteFileSetting.failIfDirectoryExists,
                fastTrack.controller().getOverwriteFileSetting());
        assertFalse(bau.controller().getOutputDirectory().equals(fastTrack.controller().getOutputDirectory()));
        assertFalse(Files.exists(Production2040Contract.path(bau.controller().getOutputDirectory())));
        assertFalse(Files.exists(Production2040Contract.path(fastTrack.controller().getOutputDirectory())));
    }

    @Test
    void generatesByteIdenticalConfigsTwice() throws Exception {
        Path bau = temporaryDirectory.resolve("bau.xml");
        Path fastTrack = temporaryDirectory.resolve("fast-track.xml");
        BuildProduction2040Configs.build(bau, fastTrack);
        byte[] firstBau = Files.readAllBytes(bau);
        byte[] firstFast = Files.readAllBytes(fastTrack);
        BuildProduction2040Configs.build(bau, fastTrack);
        assertArrayEquals(firstBau, Files.readAllBytes(bau));
        assertArrayEquals(firstFast, Files.readAllBytes(fastTrack));
    }

    @Test
    void generatorAndValidatorDoNotChangeProtectedInputs() {
        Production2040Contract.ContractData contract = Production2040Contract.loadAndValidate();
        Map<Path, String> before = Production2040Contract.protectedInputSnapshot(contract);
        Path bau = temporaryDirectory.resolve("bau-protected.xml");
        Path fastTrack = temporaryDirectory.resolve("fast-protected.xml");
        BuildProduction2040Configs.build(bau, fastTrack);
        ValidateProduction2040Configs.validateFiles(bau, fastTrack, true);
        assertEquals(before, Production2040Contract.protectedInputSnapshot(contract));
    }

    @Test
    void keepsRequiredPairedRunControls() {
        Config bau = buildBau();
        Config fastTrack = buildFastTrack();
        assertEquals(4711, bau.global().getRandomSeed());
        assertEquals(bau.global().getRandomSeed(), fastTrack.global().getRandomSeed());
        assertEquals(0, bau.controller().getFirstIteration());
        assertEquals(60, bau.controller().getLastIteration());
        assertEquals(bau.controller().getLastIteration(), fastTrack.controller().getLastIteration());
        assertEquals(48 * 3600.0, bau.qsim().getEndTime().seconds());
        assertEquals(bau.qsim().getEndTime().seconds(), fastTrack.qsim().getEndTime().seconds());
        assertTrue(bau.transit().isUseTransit() && fastTrack.transit().isUseTransit());
    }

    private static Config buildBau() {
        return BuildProduction2040Configs.buildConfig(Production2040Contract.BAU);
    }

    private static Config buildFastTrack() {
        return BuildProduction2040Configs.buildConfig(Production2040Contract.FAST_TRACK);
    }

    private static void assertSameDouble(String expected, String actual) {
        assertEquals(Double.doubleToLongBits(Double.parseDouble(expected)),
                Double.doubleToLongBits(Double.parseDouble(actual)));
    }
}
