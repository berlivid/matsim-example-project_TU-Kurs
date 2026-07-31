# MATSim Run Log

This file records technical runs and data-preparation steps in chronological
order. Methodological details are documented separately under `docs/methodology`.

## 2026-07-15 – Day 1: Project setup

### Repository and development environment

- Repository cloned locally.
- Project imported into IntelliJ as a Maven project.
- Java version: 21.
- MATSim version: 2025.0.

### Build

- Command: `mvnw.cmd clean package`
- Result: successful.

### Equil example

- Config: `scenarios/equil/config.xml`
- Purpose: verify that the project builds and that a basic MATSim scenario runs.

## 2026-07-17 – Day 2: Munich reference run

### Scenario

- Current config path: `scenarios/munich_base_2023/config_base.xml`
- Iterations: `lastIteration = 0`
- Population sample: 5%
- `flowCapacityFactor = 0.05`
- `storageCapacityFactor = 0.05`
- Java heap: approximately 4 GB
- Java version: 21

### Inputs

- Network: `studyNetworkDense.xml`
- Population: `munich-v1.0-5pct.plans.xml`
- The original Munich network and population were obtained from the public
  MATSim Munich scenario and are now stored locally.

### Result

- Network loaded successfully.
- 324,043 persons loaded successfully.
- Iteration 0 completed.
- Process finished with exit code 0.
- Output files were generated successfully.
- No fatal error or `OutOfMemoryError` occurred.
- The run was slow because the available Java heap was nearly fully used.

### Important limitations

- The model is currently a technical Munich reference scenario.
- It has not yet been validated as a calibrated 2023 baseline.
- Public transport is not simulated explicitly.
- No transit schedule or transit vehicles are included.
- The source year and calibration status of the public Munich population still
  need to be clarified.

## 2026-07-30 – Day 3: Preparation of the 2040 population

### Input preparation

- Population projection:
  `original-input-data/munich-demography/population_projection_2023_2040.csv`
- Munich municipal boundary:
  `original-input-data/munich-demography/munich_boundary.json`
- Boundary reprojected to DHDN / 3-degree Gauss-Krueger zone 4
  (`EPSG:31468`) to match the MATSim coordinates.
- Data sources and transformations:
  `original-input-data/munich-demography/README.md`

### Base-population analysis

Class:
`src/main/java/org/matsim/project/prepare/AnalyzeMunichPopulation.java`

- Total persons: 324,043
- Home inside Munich: 68,770
- Home outside Munich: 147,655
- Without home activity: 107,618
- Without home coordinate: 0
- Work inside Munich: 28,328
- Work outside Munich: 52,852
- Education inside Munich: 6,555
- Education outside Munich: 12,323
- Other inside Munich: 108,532
- Other outside Munich: 157,631

### Population scaling

Class:
`src/main/java/org/matsim/project/prepare/CreateMunichPopulation2040.java`

- Official population 2023: 1,488,719
- Projected population 2040: 1,752,066
- Growth factor: 1.176895
- Original MATSim residents inside Munich: 68,770
- Target MATSim residents inside Munich: 80,935
- Added cloned residents: 12,165
- Final MATSim population: 336,208
- Fixed random seed: 2040

### Generated files

- `scenarios/munich_bau_2040/population_2040.xml`
- `scenarios/munich_fast_track_2040/population_2040.xml`

The two files were generated from the same population and verified as
byte-identical. They are excluded from Git because of their size. The scenario
configs now reference these generated files.

### Validation of the 2040 population

- Total persons: 336,208
- Home inside Munich: 80,935
- Home outside Munich: 147,655
- Without home activity: 107,618
- Without home coordinate: 0
- Work inside Munich: 30,425
- Work outside Munich: 53,405
- Education inside Munich: 7,538
- Education outside Munich: 12,373
- Other inside Munich: 116,409
- Other outside Munich: 158,236

### Status

- Population generation compiled successfully.
- Both generated populations were read successfully by MATSim.
- No full BAU-2040 or Fast-Track-2040 simulation has been run yet.
- Detailed method: `docs/methodology/population_2040.md`

## 2026-07-31 – Day 5: Current MVV public-transport reference

### Raw GTFS input

- Dataset: `Soll-Fahrplandaten MVV-Gesamtnetz (GTFS)`
- Provider: Münchner Verkehrs- und Tarifverbund GmbH (MVV)
- Raw file: `original-input-data/mvv_gtfs/gesamt_gtfs.zip`
- Portal release label: `06/2026`
- Internal `feed_info.txt` version: `20260705`
- Feed validity: 2026-06-15 through 2026-09-30
- Accessed: 2026-07-31
- License: CC BY 4.0
- Representative service day: Wednesday, 2026-09-16
- Source details and checksum:
  `original-input-data/mvv_gtfs/README.md`

The selected date lies inside the feed validity period and no service explicitly
named `Special` is added for this date in `calendar_dates.txt`.

### GTFS conversion

Class:
`src/main/java/org/matsim/project/prepare/CreateCurrentMvvTransit.java`

Conversion settings:

- Base network: `scenarios/munich_base_2023/studyNetworkDense.xml`
- Coordinate transformation: WGS84 (`EPSG:4326`) to `EPSG:31468`
- Create PT pseudonetwork and transit vehicles: enabled
- Copy early and late departures: enabled
- Extended GTFS route types: enabled
- Stop merging: disabled (`doNotMerge`)

Generated files:

- `scenarios/munich_base_2023/input_transit/studyNetworkDense-with-pt.xml.gz`
- `scenarios/munich_base_2023/input_transit/transitSchedule-current.xml.gz`
- `scenarios/munich_base_2023/input_transit/transitVehicles-current.xml.gz`

Conversion result:

- Active services: 915
- Schedule-based departures before overnight copies: 44,536
- Frequency-based departures: 0
- Transit stop facilities reported after conversion: 39,799
- Transit lines: 857
- Transit vehicles/departures after overnight copies: 47,226

The conversion completed with exit code 0. The GTFS loader reported that
`shapes.txt` was present but empty. This is acceptable for the chosen
pseudonetwork approach.

### PT test configuration

Config:
`scenarios/munich_base_2023/config_pt_test.xml`

- Original `config_base.xml` retained unchanged.
- Network changed to the generated network with PT pseudolinks.
- Transit schedule and transit vehicles activated.
- `useTransit = true`
- Coordinate system: `EPSG:31468`
- `firstIteration = 0`
- `lastIteration = 0`
- `flowCapacityFactor = 0.05`
- `storageCapacityFactor = 0.05`
- Java heap: 8 GB

The first PT test had no explicit QSim end time. Transit vehicles remaining in
the simulation caused simulated time to continue far beyond the timetable; the
run was stopped manually after more than 1,900 simulated hours. The config was
then corrected by setting:

`qsim.endTime = 30:00:00`

### Successful Iteration-0 PT test

- MATSim version: 2025.0
- Java version: 21
- Population loaded: 324,043 persons
- SwissRailRaptor routes: 6,925
- SwissRailRaptor departures: 47,226
- SwissRailRaptor route stops: 130,758
- SwissRailRaptor stop facilities: 30,079
- SwissRailRaptor transfer connections: 1,729,986
- QSim completed through 30:00:00.
- Iteration 0 completed.
- Leg histogram PT segments: 179,650
- Process shut down normally.
- No fatal error or `OutOfMemoryError` occurred.
- Iteration runtime reported by `stopwatch.csv`: approximately 6 minutes and
  23 seconds, excluding initial scenario loading and routing preparation.

The output was written to:

`scenarios/munich_base_2023/output/pt_test`

Scenario outputs are excluded from Git.

### Warnings and limitations

- MATSim enlarged storage capacity on short PT pseudolinks. This was non-fatal
  but modifies PT pseudonetwork flow dynamics.
- Bus and tram routes are not mapped to the real road network.
- Transit vehicle types and capacities are generic converter defaults.
- No automatic car/PT mode-choice strategy is configured.
- PT crowding and capacities are not calibrated for the 5% population sample.
- The timetable represents 2026 and must not be labelled as a historical 2023
  timetable or as a 2040 timetable.

### Status

- The current MVV GTFS feed was converted successfully.
- MATSim schedule-based PT routing and explicit transit vehicle simulation were
  verified technically.
- The generated 2026 PT files are retained as a current reference and as a
  potential starting point for documented BAU-2040 and Fast-Track-2040 PT
  modifications.
