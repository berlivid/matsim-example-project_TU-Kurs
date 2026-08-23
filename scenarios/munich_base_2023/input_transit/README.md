# Current MVV transit inputs

This directory contains the MATSim public-transport inputs generated from the
current MVV complete-network GTFS feed for Wednesday, 2026-09-16.

## Files

- `studyNetworkDense-with-pt.xml.gz`: original Munich road network plus the
  generated PT pseudonetwork.
- `transitSchedule-current.xml.gz`: timetable, stops, routes, and departures.
- `transitVehicles-current.xml.gz`: standard transit vehicle types and
  vehicles assigned to the schedule departures.

The original `studyNetworkDense.xml` is not overwritten.

## Generation

Generator:

`src/main/java/org/matsim/project/prepare/CreateCurrentMvvTransit.java`

Raw input and source metadata:

`original-input-data/mvv_gtfs_2026/README.md`

Coordinate system: DHDN / 3-degree Gauss-Krueger zone 4 (`EPSG:31468`).

Conversion result:

- active GTFS services: 915;
- schedule-based departures before overnight copies: 44,536;
- frequency-based departures: 0;
- transit stop facilities reported after conversion: 39,799;
- transit lines: 857; and
- transit vehicles/departures after overnight copies: 47,226.

## Technical validation

Test config:

`scenarios/munich_base_2023/config_pt_test.xml`

The MATSim 2025.0 Iteration-0 test completed successfully with Java 21 and an
8 GB heap. The effective configuration used:

- `useTransit = true`;
- SwissRailRaptor for schedule-based PT routing;
- `qsim.endTime = 30:00:00`;
- `flowCapacityFactor = 0.05`; and
- `storageCapacityFactor = 0.05`.

The run loaded 324,043 persons, completed Iteration 0, wrote transit schedule
and vehicle outputs, and shut down normally without a fatal error or
`OutOfMemoryError`. The leg histogram reported 179,650 PT legs. This number
counts routed PT segments and can exceed the number of original main-mode PT
trips because transfers split a trip into multiple legs.

## Limitations

- This is a timetable-based PT pseudonetwork. Bus and tram services are not
  mapped to the actual road links.
- The empty GTFS `shapes.txt` is not used.
- MATSim enlarged storage capacity on short `pt_*` links during the technical
  test. This was non-fatal but changes pseudonetwork flow dynamics.
- Vehicle types and capacities are generic converter defaults.
- Automatic mode switching between car and PT is not configured.
- The 5% population sample and PT vehicle capacities have not been calibrated
  for crowding analysis.
- These files represent a 2026 service day, not a calibrated 2023 baseline and
  not a 2040 scenario.
