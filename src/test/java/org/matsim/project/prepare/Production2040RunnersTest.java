package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

class Production2040RunnersTest {
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    @TempDir Path temporaryDirectory;

    @Test
    void acceptsOnlyTheTwoScenarioArguments() {
        assertEquals("BAU", Production2040RunSupport.scenario("BAU").argument());
        assertEquals("FAST_TRACK",
                Production2040RunSupport.scenario("FAST_TRACK").argument());
        assertThrows(IllegalArgumentException.class,
                () -> Production2040RunSupport.scenario("legacy"));
    }

    @Test
    void selectsApprovedConfigsRunIdsAndProtectedOutputs() {
        var bau = Production2040RunSupport.scenario("BAU");
        var fast = Production2040RunSupport.scenario("FAST_TRACK");
        assertEquals(Production2040Contract.BAU.configPath(), bau.contract().configPath());
        assertEquals("munich-bau-2040-mode-choice", bau.contract().runId());
        assertEquals("munich-bau-2040-production-smoke-r5", bau.smokeRunId());
        assertTrue(bau.smokeOutput().toString().replace('\\', '/')
                .endsWith("scenarios/munich_bau_2040/output/smoke-production-r5"));
        assertEquals(Production2040Contract.FAST_TRACK.configPath(),
                fast.contract().configPath());
        assertEquals("munich-fast-track-2040-mode-choice", fast.contract().runId());
        assertFalse(bau.productionOutput().equals(fast.productionOutput()));
        assertFalse(bau.smokeOutput().equals(fast.smokeOutput()));
    }

    @Test
    void smokeDerivationAllowsOnlyIdentityOutputIterationAndPopulationReplacement() {
        var definition = Production2040RunSupport.scenario("BAU");
        Config production = Production2040RunSupport.productionConfig(definition);
        Config smoke = Production2040RunSupport.smokeConfig(definition);
        assertEquals(60, production.controller().getLastIteration());
        assertEquals(0, smoke.controller().getLastIteration());
        assertEquals(4711, smoke.global().getRandomSeed());
        assertEquals(production.qsim().getEndTime().seconds(), smoke.qsim().getEndTime().seconds());
        assertEquals(production.scoring().getModes().get("car").getConstant(),
                smoke.scoring().getModes().get("car").getConstant());
        assertEquals(null, smoke.plans().getInputFile());
        Production2040RunSupport.validateSmokeOverrides(definition, smoke);
        smoke.global().setRandomSeed(1);
        assertThrows(IllegalStateException.class,
                () -> Production2040RunSupport.validateSmokeOverrides(definition, smoke));
    }

    @Test
    void runnerArchitectureInstallsCommonModulesExactlyOnce() {
        var production = Production2040RunSupport.productionArchitecture();
        assertEquals(1, production.swissRailRaptorInstallations());
        assertEquals(1, production.analysisListenerInstallations());
        assertEquals(0, production.firstIteration());
        assertEquals(60, production.lastIteration());
        assertTrue(production.transitEnabled());
        assertTrue(production.postprocessAfterNormalShutdown());
        var smoke = Production2040RunSupport.smokeArchitecture();
        assertEquals(1, smoke.swissRailRaptorInstallations());
        assertEquals(0, smoke.analysisListenerInstallations());
        assertEquals(0, smoke.lastIteration());
    }

    @Test
    void productionConfigsAndProtectedInputsRemainByteUnchanged() throws Exception {
        var contract = Production2040Contract.loadAndValidate();
        var before = Production2040Contract.protectedInputSnapshot(contract);
        byte[] bau = Files.readAllBytes(Production2040Contract.BAU.configPath());
        byte[] fast = Files.readAllBytes(Production2040Contract.FAST_TRACK.configPath());
        Production2040RunSupport.productionConfig(Production2040RunSupport.scenario("BAU"));
        Production2040RunSupport.smokeConfig(Production2040RunSupport.scenario("FAST_TRACK"));
        assertTrue(java.util.Arrays.equals(bau,
                Files.readAllBytes(Production2040Contract.BAU.configPath())));
        assertTrue(java.util.Arrays.equals(fast,
                Files.readAllBytes(Production2040Contract.FAST_TRACK.configPath())));
        assertEquals(before, Production2040Contract.protectedInputSnapshot(contract));
    }

    @Test
    void createsTheFourInMemorySmokePersonsFromActualScheduleFacilities() {
        Scenario bau = smokeFixture(false);
        var bauFixture = Production2040SmokePopulation.install(bau,
                Production2040RunSupport.scenario("BAU"));
        assertEquals(Set.of("smoke-car", "smoke-pt", "smoke-walk", "smoke-bike"),
                bau.getPopulation().getPersons().keySet().stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals("subway", bauFixture.routeMode());

        Scenario fast = smokeFixture(true);
        var fastFixture = Production2040SmokePopulation.install(fast,
                Production2040RunSupport.scenario("FAST_TRACK"));
        assertEquals("FT_U9", fastFixture.lineId());
    }

    @Test
    void recoverySourceContainsNoControllerOrQsimEntryPoint() throws Exception {
        String source = Files.readString(ROOT.resolve(
                "src/main/java/org/matsim/project/prepare/AnalyzeExistingMatsim2040ProductionOutput.java"));
        assertFalse(source.contains("new Controler"));
        assertFalse(source.contains("controler.run"));
        assertFalse(source.contains("ScenarioUtils.loadScenario"));
        assertTrue(source.contains("ValidateProduction2040AnalysisOutput.validate"));
    }

    @Test
    void incompleteAndWrongScenarioOutputEvidenceIsRejected() throws Exception {
        var base = Production2040AnalysisSpec.scenario("BAU");
        Path incomplete = temporaryDirectory.resolve("incomplete");
        Files.createDirectories(incomplete);
        var definition = new Production2040AnalysisSpec.ScenarioDefinition("BAU_2040",
                base.contract(), incomplete, incomplete.resolve("analysis-runtime"),
                incomplete.resolve("analysis"));
        assertThrows(IllegalStateException.class,
                () -> ValidateProduction2040AnalysisOutput.validate(definition, false));

        Path rows = temporaryDirectory.resolve("iterations.csv");
        Files.writeString(rows, "scenario_id,sample_factor,unit,iteration,both_inside_main_trips,car_sample_trips,car_share_percent,pt_sample_trips,pt_share_percent,bike_sample_trips,bike_share_percent,walk_sample_trips,walk_share_percent,unexpected_mode_sample_trips,unexpected_modes,definition\n"
                + "FAST_TRACK_2040,0.05,main_trips,0,4,1,25,1,25,1,25,1,25,0,{},test\n",
                StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class,
                () -> ValidateProduction2040AnalysisOutput.readIterations(rows, "BAU_2040"));
    }

    @Test
    void allEightIntellijConfigurationsAreWellFormedAndProtected() throws Exception {
        List<String> names = List.of(
                "P1 Validate BAU 2040 Production Input",
                "P2 Validate Fast Track 2040 Production Input",
                "P3 Run BAU 2040 Production Smoke Test",
                "P4 Run Fast Track 2040 Production Smoke Test",
                "P7 Run BAU 2040 Production", "P8 Run Fast Track 2040 Production",
                "P7B Analyze Existing BAU 2040 Production Output",
                "P8B Analyze Existing Fast Track 2040 Production Output");
        for (String name : names) {
            Path file = ROOT.resolve(".run/" + name + ".run.xml");
            assertTrue(Files.isRegularFile(file));
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
            String xml = Files.readString(file);
            assertTrue(xml.contains("$PROJECT_DIR$"));
            assertTrue(xml.contains("shortenClasspath name=\"ARGS_FILE\""));
            assertTrue(xml.contains("-Djava.awt.headless=true"));
            assertTrue(xml.contains("option name=\"Make\" enabled=\"true\""));
            if (name.startsWith("P3") || name.startsWith("P4")
                    || name.startsWith("P7 Run") || name.startsWith("P8 Run"))
                assertTrue(xml.contains("-Xms4g -Xmx16g"));
            else assertTrue(xml.contains("-Xms2g -Xmx8g"));
        }
    }

    private static Scenario smokeFixture(boolean fastTrack) {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Node n1 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n1"),
                new Coord(4_470_000, 5_330_000));
        Node n2 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n2"),
                new Coord(4_470_100, 5_330_000));
        Node n3 = NetworkUtils.createAndAddNode(scenario.getNetwork(), Id.createNodeId("n3"),
                new Coord(4_470_200, 5_330_000));
        Id<Link> l1 = Id.createLinkId("l1");
        Id<Link> l2 = Id.createLinkId("l2");
        NetworkUtils.createAndAddLink(scenario.getNetwork(), l1, n1, n2, 100, 10, 1000, 1);
        NetworkUtils.createAndAddLink(scenario.getNetwork(), l2, n2, n3, 100, 10, 1000, 1);
        var factory = scenario.getTransitSchedule().getFactory();
        TransitStopFacility s1 = factory.createTransitStopFacility(
                Id.create("s1", TransitStopFacility.class), n1.getCoord(), false);
        TransitStopFacility s2 = factory.createTransitStopFacility(
                Id.create("s2", TransitStopFacility.class), n2.getCoord(), false);
        TransitStopFacility s3 = factory.createTransitStopFacility(
                Id.create("s3", TransitStopFacility.class), n3.getCoord(), false);
        s1.setLinkId(l1); s2.setLinkId(l1); s3.setLinkId(l2);
        scenario.getTransitSchedule().addStopFacility(s1);
        scenario.getTransitSchedule().addStopFacility(s2);
        scenario.getTransitSchedule().addStopFacility(s3);
        Id<TransitLine> lineId = Id.create(fastTrack ? "FT_U9" : "BAU_U6",
                TransitLine.class);
        TransitLine line = factory.createTransitLine(lineId);
        if (fastTrack) line.setName("FT_U9");
        TransitRoute route = factory.createTransitRoute(Id.create("route", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(l1, l2),
                List.of(factory.createTransitRouteStop(s1, 0, 0),
                        factory.createTransitRouteStop(s2, 60, 60),
                        factory.createTransitRouteStop(s3, 120, 120)), "subway");
        Departure departure = factory.createDeparture(Id.create("dep", Departure.class), 3600);
        route.addDeparture(departure);
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);
        return scenario;
    }
}
