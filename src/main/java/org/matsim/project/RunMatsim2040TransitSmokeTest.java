package org.matsim.project;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.events.PersonArrivalEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonArrivalEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Runs a deliberately small iteration-zero integration test, never the production population. */
public final class RunMatsim2040TransitSmokeTest {
    private RunMatsim2040TransitSmokeTest() { }

    public static void main(String[] args) {
        if (args.length != 1 || !(args[0].equals("bau") || args[0].equals("fast-track"))) {
            throw new IllegalArgumentException("Use bau or fast-track");
        }
        String scenarioName = args[0];
        Path root = Path.of("").toAbsolutePath().normalize();
        Path scenarioDir = root.resolve("scenarios/munich_" + (scenarioName.equals("bau") ? "bau" : "fast_track") + "_2040");
        Path configFile = scenarioDir.resolve(scenarioName.equals("bau") ? "config_bau.xml" : "config_fast_track.xml");
        Config config = ConfigUtils.loadConfig(configFile.toString());
        config.plans().setInputFile(null);
        config.controller().setFirstIteration(0);
        config.controller().setLastIteration(0);
        config.controller().setOutputDirectory(scenarioDir.resolve("smoke-output").toString());
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.global().setRandomSeed(4711);
        config.qsim().setStartTime(9.5 * 3600);
        config.qsim().setEndTime(11 * 3600);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        TransitStopFacility from;
        TransitStopFacility to;
        if (scenarioName.equals("fast-track")) {
            from = stop(scenario, "FT_U9_MUENCHNER_FREIHEIT_D0");
            to = stop(scenario, "FT_U9_PINAKOTHEKEN_D0");
        } else {
            var u6 = scenario.getTransitSchedule().getTransitLines().values().stream()
                    .filter(line -> "MUC_U6_neu Prognose".equals(line.getName())).findFirst().orElseThrow();
            var route = u6.getRoutes().values().stream().filter(r -> r.getStops().stream().anyMatch(s -> "Dietlindenstraße".equals(s.getStopFacility().getName()))
                    && r.getStops().stream().anyMatch(s -> "Münchner Freiheit".equals(s.getStopFacility().getName()))).findFirst().orElseThrow();
            TransitStopFacility dietlinden = route.getStops().stream().map(s -> s.getStopFacility()).filter(s -> "Dietlindenstraße".equals(s.getName())).findFirst().orElseThrow();
            TransitStopFacility freiheit = route.getStops().stream().map(s -> s.getStopFacility()).filter(s -> "Münchner Freiheit".equals(s.getName())).findFirst().orElseThrow();
            var ordered = route.getStops().stream().map(s -> s.getStopFacility()).toList();
            from = ordered.indexOf(dietlinden) < ordered.indexOf(freiheit) ? dietlinden : freiheit;
            to = ordered.indexOf(dietlinden) < ordered.indexOf(freiheit) ? freiheit : dietlinden;
        }
        addPerson(scenario, "smoke-pt", TransportMode.pt, from, to);
        addPerson(scenario, "smoke-car", TransportMode.car, from, to);

        SmokeEvents events = new SmokeEvents();
        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new SwissRailRaptorModule());
        controler.getEvents().addHandler(events);
        controler.run();
        if (!events.arrivals.contains("smoke-pt") || !events.arrivals.contains("smoke-car") || !events.boarded.contains("smoke-pt")) {
            throw new IllegalStateException("Smoke agents did not complete correctly: arrivals=" + events.arrivals + ", boarded=" + events.boarded);
        }
        System.out.println("SMOKE PASS " + scenarioName + ": PT boarded and arrived; car arrived; output=" + config.controller().getOutputDirectory());
    }

    private static TransitStopFacility stop(Scenario scenario, String id) {
        TransitStopFacility exact = scenario.getTransitSchedule().getFacilities().get(Id.create(id, TransitStopFacility.class));
        if (exact != null) return exact;
        return scenario.getTransitSchedule().getFacilities().values().stream().filter(s -> s.getId().toString().startsWith(id + "."))
                .findFirst().orElseThrow(() -> new IllegalStateException("Missing smoke stop " + id));
    }

    private static void addPerson(Scenario scenario, String id, String mode, TransitStopFacility from, TransitStopFacility to) {
        Person person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId(id));
        Plan plan = scenario.getPopulation().getFactory().createPlan();
        Activity home = scenario.getPopulation().getFactory().createActivityFromCoord("home", from.getCoord());
        home.setEndTime(10 * 3600);
        plan.addActivity(home);
        plan.addLeg(scenario.getPopulation().getFactory().createLeg(mode));
        plan.addActivity(scenario.getPopulation().getFactory().createActivityFromCoord("work", to.getCoord()));
        person.addPlan(plan);
        scenario.getPopulation().addPerson(person);
    }

    private static final class SmokeEvents implements PersonArrivalEventHandler, PersonEntersVehicleEventHandler {
        private final Set<String> arrivals = new HashSet<>();
        private final Set<String> boarded = new HashSet<>();
        @Override public void handleEvent(PersonArrivalEvent event) {
            if (event.getPersonId().toString().startsWith("smoke-")) arrivals.add(event.getPersonId().toString());
        }
        @Override public void handleEvent(PersonEntersVehicleEvent event) {
            if (event.getPersonId().toString().startsWith("smoke-")) boarded.add(event.getPersonId().toString());
        }
    }
}
