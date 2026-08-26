package org.matsim.project.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;

class ResidentModeChoiceStuckSensitivityTest {

    @Test
    void departuresMatchStageLegsToTheirEnclosingMainTrip() {
        Scenario scenario = scenarioWithResident();
        ResidentStuckMainTripTracker tracker = new ResidentStuckMainTripTracker(scenario);
        Id<Person> id = Id.createPersonId("resident");
        tracker.handleEvent(departure(id, "transit_walk", "pt"));
        tracker.handleEvent(departure(id, "pt", "pt"));
        tracker.handleEvent(stuck(id, "pt"));

        var snapshot = tracker.snapshot();
        assertEquals(1, snapshot.events());
        assertEquals(1, snapshot.uniquePersons());
        assertEquals(Set.of(new ResidentStuckMainTripTracker.MainTripKey(id, 0)),
                snapshot.affectedMainTrips());
        assertEquals("pt", snapshot.modes().getFirst().routingMode());
        assertEquals(1, snapshot.modes().getFirst().affectedMainTrips());
    }

    @Test
    void iterationCsvReportsPersonsTripsModesBaselineAndReviewThreshold() {
        Id<Person> id = Id.createPersonId("resident");
        var key = new ResidentStuckMainTripTracker.MainTripKey(id, 0);
        var tracked = new ResidentStuckMainTripTracker.Snapshot(1, 1, Set.of(key),
                List.of(new ResidentStuckMainTripTracker.ModeResult("pt", 1, 1, 1)),
                List.of());
        var zero = ResidentModeChoiceStuckEventListener.snapshot(0, tracked, 1);
        var one = ResidentModeChoiceStuckEventListener.snapshot(1,
                new ResidentStuckMainTripTracker.Snapshot(0, 0, Set.of(), List.of(), List.of()),
                1);
        String csv = ResidentModeChoiceStuckEventListener.csv(List.of(one, zero));
        assertTrue(csv.contains("0,all,1,1,1"));
        assertTrue(csv.contains("0,pt,1,1,1"));
        assertTrue(csv.contains("1,all,0,0,0"));
        assertTrue(csv.contains(",-1,-1,-1,1,PASS"));
    }

    @Test
    void primaryKeepsAllTripsAndSensitivityExcludesOnlyMatchedTrip(
            @TempDir Path temp) throws Exception {
        Scenario scenario = scenarioWithResident();
        MunichMunicipalBoundary boundary = MunichMunicipalBoundary.loadDefault();
        Person resident = scenario.getPopulation().getPersons().get(Id.createPersonId("resident"));
        Map<Id<Person>, Plan> plans = Map.of(resident.getId(), resident.getSelectedPlan());
        ModeChoiceCalibrationAnalysis analysis = new ModeChoiceCalibrationAnalysis(
                scenario, boundary);
        var primary = analysis.analyze(20, plans);
        var key = new ResidentStuckMainTripTracker.MainTripKey(resident.getId(), 0);
        var sensitivity = analysis.analyze(20, plans, Set.of(key));
        var stuck = new ResidentStuckMainTripTracker.Snapshot(1, 1, Set.of(key),
                List.of(new ResidentStuckMainTripTracker.ModeResult("pt", 1, 1, 1)),
                List.of());
        var result = new ResidentModeChoiceStuckSensitivityWriter(temp)
                .write(primary, sensitivity, stuck);

        assertEquals(2, metrics(primary).mainTrips());
        assertEquals(1, metrics(sensitivity).mainTrips());
        assertEquals(1, result.affectedMainTrips());
        assertEquals("REVIEW_REQUIRED", result.status());
        String primaryCsv = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_final_primary.csv"));
        String sensitivityCsv = Files.readString(temp.resolve(
                "analysis/resident_mode_choice_final_stuck_sensitivity.csv"));
        assertTrue(primaryCsv.contains("PRIMARY_ALL_RESIDENT_TRIPS,pt,1,"));
        assertTrue(sensitivityCsv.contains(
                "SENSITIVITY_EXCLUDING_STUCK_AFFECTED_TRIPS,car,1,"));
        assertTrue(Files.readString(temp.resolve(
                        "analysis/resident_mode_choice_final_sensitivity_comparison.csv"))
                .contains("total_main_mode_pkm,all"));
    }

    private static Scenario scenarioWithResident() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var factory = scenario.getPopulation().getFactory();
        Person person = factory.createPerson(Id.createPersonId("resident"));
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(4_468_000, 5_335_000)));
        Leg access = factory.createLeg("transit_walk");
        access.setRoutingMode("pt");
        route(access, 100);
        plan.addLeg(access);
        plan.addActivity(factory.createActivityFromCoord("pt interaction",
                new Coord(4_468_100, 5_335_000)));
        Leg pt = factory.createLeg("pt");
        pt.setRoutingMode("pt");
        route(pt, 900);
        plan.addLeg(pt);
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(4_469_000, 5_335_000)));
        Leg car = factory.createLeg("car");
        car.setRoutingMode("car");
        route(car, 2_000);
        plan.addLeg(car);
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(4_468_000, 5_335_000)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        PopulationUtils.putSubpopulation(person, ResidentCalibrationSubpopulations.MUNICH_RESIDENT);
        scenario.getPopulation().addPerson(person);
        return scenario;
    }

    private static void route(Leg leg, double distance) {
        var route = RouteUtils.createGenericRouteImpl(null, null);
        route.setDistance(distance);
        leg.setRoute(route);
    }

    private static PersonDepartureEvent departure(Id<Person> person, String legMode,
                                                   String routingMode) {
        return new PersonDepartureEvent(100, person, Id.createLinkId("link"), legMode,
                routingMode);
    }

    private static PersonStuckEvent stuck(Id<Person> person, String mode) {
        return new PersonStuckEvent(48 * 3600.0, person, Id.createLinkId("link"), mode);
    }

    private static ModeChoiceCalibrationAnalysis.MetricSnapshot metrics(
            ModeChoiceCalibrationAnalysis.AnalysisResult result) {
        return result.metrics(ModeChoiceCalibrationAnalysis.SpatialScope.ALL_TRIPS,
                ModeChoiceCalibrationAnalysis.PlanEligibility.ALL_PLANS);
    }
}
