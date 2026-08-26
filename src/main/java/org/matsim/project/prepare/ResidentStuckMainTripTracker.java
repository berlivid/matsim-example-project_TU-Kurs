package org.matsim.project.prepare;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.StageActivityTypeIdentifier;
import org.matsim.core.router.TripStructureUtils;

/**
 * Read-only event-to-selected-plan matcher for Munich-resident stuck main trips.
 * A departure's ordinal is matched to the corresponding selected-plan leg; stage legs
 * retain the index of their enclosing main trip.
 */
final class ResidentStuckMainTripTracker
        implements PersonDepartureEventHandler, PersonStuckEventHandler {
    static final long RESIDENT_MAIN_TRIPS = 137_540L;

    private final Scenario scenario;
    private final Map<Id<Person>, Integer> departureOrdinals = new HashMap<>();
    private final Map<Id<Person>, List<LegContext>> planLegs = new HashMap<>();
    private final Map<Id<Person>, LegContext> activeLegs = new HashMap<>();
    private final List<StuckRecord> records = new ArrayList<>();

    ResidentStuckMainTripTracker(Scenario scenario) {
        this.scenario = scenario;
    }

    @Override
    public synchronized void reset(int iteration) {
        departureOrdinals.clear();
        planLegs.clear();
        activeLegs.clear();
        records.clear();
    }

    @Override
    public synchronized void handleEvent(PersonDepartureEvent event) {
        Person person = resident(event.getPersonId());
        if (person == null) return;
        int ordinal = departureOrdinals.merge(event.getPersonId(), 0,
                (previous, ignored) -> previous + 1);
        List<LegContext> legs = planLegs.computeIfAbsent(event.getPersonId(),
                ignored -> indexedLegs(person.getSelectedPlan()));
        require(ordinal >= 0 && ordinal < legs.size(),
                "Resident departure cannot be matched to a selected-plan leg: person="
                        + event.getPersonId() + ", ordinal=" + ordinal
                        + ", planLegs=" + legs.size());
        LegContext planned = legs.get(ordinal);
        String routingMode = normalize(event.getRoutingMode(),
                planned.routingMode(), event.getLegMode(), planned.legMode());
        activeLegs.put(event.getPersonId(), new LegContext(planned.mainTripIndex(),
                planned.legMode(), routingMode));
    }

    @Override
    public synchronized void handleEvent(PersonStuckEvent event) {
        Person person = resident(event.getPersonId());
        if (person == null) return;
        LegContext active = activeLegs.get(event.getPersonId());
        require(active != null,
                "Resident stuck event has no preceding matched departure: person="
                        + event.getPersonId() + ", time=" + event.getTime());
        records.add(new StuckRecord(event.getPersonId(), active.mainTripIndex(),
                normalize(active.routingMode(), event.getLegMode()),
                normalize(event.getLegMode(), active.legMode()), event.getTime()));
    }

    synchronized Snapshot snapshot() {
        Set<Id<Person>> persons = new HashSet<>();
        Set<MainTripKey> trips = new HashSet<>();
        TreeMap<String, MutableMode> modes = new TreeMap<>();
        for (StuckRecord record : records) {
            persons.add(record.personId());
            MainTripKey key = record.mainTripKey();
            trips.add(key);
            modes.computeIfAbsent(record.routingMode(), ignored -> new MutableMode())
                    .add(record.personId(), key);
        }
        List<ModeResult> modeResults = modes.entrySet().stream()
                .map(entry -> new ModeResult(entry.getKey(), entry.getValue().events,
                        entry.getValue().persons.size(), entry.getValue().trips.size()))
                .toList();
        return new Snapshot(records.size(), persons.size(), Set.copyOf(trips),
                List.copyOf(modeResults), List.copyOf(records));
    }

    private Person resident(Id<Person> id) {
        Person person = scenario.getPopulation().getPersons().get(id);
        return person != null && ResidentCalibrationSubpopulations.MUNICH_RESIDENT.equals(
                PopulationUtils.getSubpopulation(person)) ? person : null;
    }

    private static List<LegContext> indexedLegs(Plan plan) {
        require(plan != null, "Resident has no selected plan");
        IdentityHashMap<Leg, Integer> tripIndexByLeg = new IdentityHashMap<>();
        List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(
                plan, StageActivityTypeIdentifier::isStageActivity);
        for (int tripIndex = 0; tripIndex < trips.size(); tripIndex++) {
            for (Leg leg : trips.get(tripIndex).getLegsOnly()) {
                require(tripIndexByLeg.put(leg, tripIndex) == null,
                        "Selected-plan leg belongs to more than one main trip");
            }
        }
        List<LegContext> ordered = new ArrayList<>();
        for (PlanElement element : plan.getPlanElements()) {
            if (!(element instanceof Leg leg)) continue;
            Integer tripIndex = tripIndexByLeg.get(leg);
            require(tripIndex != null,
                    "Selected-plan leg is not enclosed by a deterministic main trip");
            ordered.add(new LegContext(tripIndex, normalize(leg.getMode()),
                    normalize(leg.getRoutingMode(), leg.getMode())));
        }
        return List.copyOf(ordered);
    }

    private static String normalize(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.toLowerCase(Locale.ROOT);
            }
        }
        return "unknown";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    record MainTripKey(Id<Person> personId, int tripIndex)
            implements Comparable<MainTripKey> {
        @Override
        public int compareTo(MainTripKey other) {
            int person = personId.compareTo(other.personId);
            return person != 0 ? person : Integer.compare(tripIndex, other.tripIndex);
        }
    }

    record StuckRecord(Id<Person> personId, int mainTripIndex, String routingMode,
                       String eventLegMode, double time) {
        MainTripKey mainTripKey() {
            return new MainTripKey(personId, mainTripIndex);
        }
    }

    record ModeResult(String routingMode, long events, long uniquePersons,
                      long affectedMainTrips) { }

    record Snapshot(long events, long uniquePersons, Set<MainTripKey> affectedMainTrips,
                    List<ModeResult> modes, List<StuckRecord> records) {
        long affectedMainTripCount() {
            return affectedMainTrips.size();
        }
    }

    private record LegContext(int mainTripIndex, String legMode, String routingMode) { }

    private static final class MutableMode {
        long events;
        final Set<Id<Person>> persons = new HashSet<>();
        final Set<MainTripKey> trips = new HashSet<>();

        void add(Id<Person> person, MainTripKey trip) {
            events++;
            persons.add(person);
            trips.add(trip);
        }
    }
}
