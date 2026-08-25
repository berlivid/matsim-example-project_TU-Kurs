# MATSim Run Log

## 2026-08-19: initial common BAU/Fast Track rail-measure build (superseded)

- Read rows 13, 18 and 19 of the unchanged infrastructure workbook and transcribed the executable rules into `common_service_specification.csv`.
- The fail-closed analyzer reported zero blockers. It selected 234 Poccistraße regional trips (117 per direction) and 203 regular S2 trips for Berduxstraße (94 direction 0; 109 direction 1).
- Existing parent centroids `106206` and `162054` are used only as explicit spatial scenario proxies. Four new directional rail-platform IDs prevent bus or underground platforms from being reclassified.
- The forecast feed encoded zero median dwell. This provisional choice was superseded on 20 August after stronger project-internal current-GTFS evidence supported 60 seconds.
- The Sendlinger Spange creates no GTFS row because the workbook specifies no published regular-weekday timetable change. Normal-day MATSim does not represent disruption resilience or diversion operations.
- Built provisional zero-dwell artifacts. Their obsolete hashes are intentionally omitted; the current artifacts and hashes are recorded in the 20 August entry below.
- MATSim transit inputs were not regenerated in this initial step. This status was superseded by the 20 August regeneration and validation; no simulation was run.

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
- Raw file: `original-input-data/mvv_gtfs_2026/gesamt_gtfs.zip`
- Portal release label: `06/2026`
- Internal `feed_info.txt` version: `20260705`
- Feed validity: 2026-06-15 through 2026-09-30
- Accessed: 2026-07-31
- License: CC BY 4.0
- Representative service day: Wednesday, 2026-09-16
- Source details and checksum:
  `original-input-data/mvv_gtfs_2026/README.md`

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

## 2040 GTFS and MATSim transit preparation — 19 August 2026

- The cleaned BAU GTFS remained unchanged: SHA-256 `91518C445DC1699396A7D377C18075DB78D164BC9813B2929B6F7242B8070B0A`.
- Fast Track GTFS was rebuilt with 520 U9 trips, 160 Nordring trips and 398 extended U4 trips: SHA-256 `6F89D39827EE3279162C2E1648C563ED93B59F14D13722F682CD20F41B466579`.
- U9 exact direction/time duplicates were removed; positive sub-two-minute intervals were retained; five intermediate stops use 20-second dwell.
- Fast Track MATSim inputs contain 71,300 departures and vehicles. Both published input sets passed read-only MATSim reference, road-network, S8, U4, transfer and PT-only-link validation.
- BAU and Fast Track configs remain inactive. No full 2040 simulation was run.
## 19 August 2026 — 2040 public-transport activation and smoke tests

- Activated the scenario-local combined network, transit schedule and transit vehicles in both production configs; set `useTransit=true` and `EPSG:31468` without changing population, scoring, capacity, strategy or mode-choice settings.
- Added reproducible full-load/reference validation and focused SwissRailRaptor queries. All required Fast Track services and platform interchanges were routable; BAU excludes U9 and both Nordring lines.
- Confirmed the Impler-/Poccistraße route uses the dedicated U9 platform and existing U3 platform with the documented minimum-transfer assumption. No transfer-data change was required.
- Ran separate iteration-zero, 09:30–11:00 controller tests with one synthetic PT agent and one synthetic car agent per scenario. Both agents completed in both scenarios. No production population was loaded, and no full simulation was run.

## 20 August 2026 — common-stop dwell correction and transit regeneration

- Audited positive intermediate passenger-stop dwell observations in the project-internal current MVV GTFS. Regular S2 provided 9,079 observations (median 60 seconds; 10th–90th percentile 60–120 seconds). RE5, RB40 and RB54 provided 2,934 regional observations (median 60 seconds; 10th–90th percentile 60–240 seconds).
- Applied 60 seconds dwell at Poccistraße and Berduxstraße. Arrival/departure records do not identify braking and acceleration separately, so no additional penalty was added. All later trip times shift by 60 seconds; trip counts, departure patterns and express exclusions remain unchanged.
- Added eight directed 180-second Poccistraße regional–U3/U6 transfers, derived from the existing station transfer matrix. Fast Track additionally contains 28 directed 300-second U9 relations to U3/U6 and regional platforms.
- Rebuilt both deterministic GTFS feeds: BAU `41D04B06D4134F71EF21468D5109264E9B61702B135E601B5875E5D6C490FF54`; Fast Track `3F82E66EB7B0999D210B639BC85571CC59D06E9969FA53146FA5CA43D9578A0F`. Repeat builds produced identical hashes.
- Regenerated and reloaded both MATSim input sets. BAU has 249,194 nodes, 561,990 links, 80,764 stop facilities and 70,620 departures; Fast Track has 249,216 nodes, 562,029 links, 80,805 stop facilities and 71,300 departures.
- Focused SwissRailRaptor tests reached both new stops and completed the Poccistraße regional-to-U3/U6 transfer with 192 seconds of transfer/access time. All retained Fast Track service tests passed. No full simulation was run.

## 23 August 2026 — GTFS-2019 validation time-horizon correction

- A Uni-server validation run continued beyond 33,000 simulated hours because
  `qsim.endTime` was undefined. The `30:00:00` value in the Hermes module did
  not constrain QSim.
- A QSim-free audit found a latest departure of `29:40:00`, a maximum route
  offset of `32:35:00`, and a latest vehicle arrival of `42:30:00`.
- The longest values were traced unchanged to internally monotonic overnight
  and multi-day coach stop times in both the synthetic subset and source ZIP;
  no route was excluded and no stop time was rewritten.
- The 2019 generator now derives `qsim.endTime=43:00:00`, the first complete
  hour after the latest accepted vehicle arrival. A 48-hour fail-closed bound
  prevents unreviewed multi-day services from creating an excessive horizon.
- Compilation, six focused tests, full structural reference validation and
  representative bus/tram/subway/rail routing passed. No full local QSim was
  started. The incomplete server output remains invalid until step 03 is rerun.

## 24 August 2026 — synthetic-2019 server validation completed

- The corrected synthetic 2019 reference input was validated on the Uni server
  with sufficient Java heap and the complete 324,043-person population.
- MATSim completed iteration 0 and terminated normally with process exit code
  0. The validator reported `GTFS 2019 END-TO-END VALIDATION PASS`.
- This establishes technical end-to-end readiness of the synthetic 2019 transit
  input. It does not independently establish historical feed provenance.
- No mode choice was active and no behavioural calibration was performed.

## 24 August 2026 — Munich trip-boundary preflight

- Added a read-only origin-and-destination analysis filter based on the
  versioned City of Munich administrative boundary in EPSG:31468. Points on the
  boundary are included through JTS `covers`.
- Streamed all 324,043 selected plans once and identified 540,468 main trips
  with MATSim stage-activity handling. The primary `BOTH_INSIDE` sample contains
  160,603 trips (29.715543%). No invalid-coordinate trip was found.
- The complete regional population and every scenario input remain unchanged.
  No modal split, passenger-kilometre or vehicle-kilometre result was produced,
  and no mode choice or simulation was started.

## 24 August 2026 — synthetic-2019 mode-choice configuration prepared

- Added a separate protected diagnostic configuration for the validated
  synthetic 2019 reference. It offers `car`, `pt`, `walk` and `bike` through
  `SubtourModeChoice`; `car` and `bike` are chain based. `ride` and `other` are
  not choice alternatives.
- Replanning weights are `ChangeExpBeta=0.8`, `ReRoute=0.1` and
  `SubtourModeChoice=0.1`. The car, PT, walk and bike constants all remain zero
  as an explicitly uncalibrated starting vector.
- The protected diagnostic is configured for iterations 0–20, seed 4711,
  four global threads, two QSim threads and 5-% flow/storage factors. No local
  QSim or 20-iteration run was started.
- A streaming audit confirmed 324,043 persons, 540,468 main trips, 216,425
  plans with a closed subtour and 107,618 without one. No unknown input mode or
  car-availability, licence or vehicle attribute was found.
- The complete regional population remains unchanged. The municipal boundary
  is an analysis-only filter. Empirical target shares and the observed 2019 car
  occupancy factor remain external inputs for later calibration and cost
  analysis.

## 24 August 2026 — mode-choice calibration analyzer prepared

- Added a read-only analyzer for each completed mobsim iteration and for an
  existing final output. It uses experienced plans at `AfterMobsim`, before
  replanning, and reuses the unchanged Munich origin-and-destination filter.
- Main trips count once in modal split and main-mode passenger-kilometres.
  Physical stages are reported separately, including PT submodes from the
  actually used transit route and explicit distance-source diagnostics.
- Results distinguish the primary `BOTH_INSIDE` sample, all regional trips,
  boundary-crossing trips, external controls and mode-choice-capable versus
  non-capable plans. Pkm sums are shown for the 5-% sample and with factor 20;
  shares and mean lengths are not scaled and no annualisation is applied.
- Added a blank, validated 2019 target schema. No target value, mode constant
  or occupancy factor was invented. Raw MATSim car route kilometres remain
  separate from later occupancy-adjusted external-cost vehicle-kilometres.
- Focused synthetic tests passed without QSim or a full-population analysis.
  No BAU, Fast Track, population, boundary or transit input was changed.

## 24 August 2026 — first-run calibration preflight

- The copied first-run analysis contains only iteration 20. The iteration and
  final-summary CSVs are byte-identical because the former standalone
  postprocessor replaced the history with a one-result list. No accompanying
  standard file can reconstruct iterations 0-19; convergence is unassessable.
- Corrected the writer so listener histories remain sorted and unique and the
  standalone postprocessor never replaces or invents a history.
- Versioned the user-supplied four-mode trip targets: car 34%, PT 24%, bike
  18% and walk 24%. Annual Pkm shares are secondary references; annual Pkm/Fkm
  remain incomparable with one service day without annualisation.
- A read-only population stream confirmed 216,425 closed-subtour and 107,618
  open plans. Open plans contain 37,417 `BOTH_INSIDE` trips (23.297821% of the
  primary sample): 75,149 plans have the same endpoint activity type at a
  different location and 32,469 have different endpoint activity types.
- MATSim 2025.0 would expose all open plans through
  `betweenAllAndFewerConstraints`, but the chain-mode end-location risk remains.
  The productive config was not changed; a short protected test is recommended.

## 24 August 2026 — isolated open-tour test prepared

- Added a separate five-iteration config for
  `betweenAllAndFewerConstraints`. An exact textual comparison permits only the
  run ID, output directory, last iteration and SubtourModeChoice behavior to
  differ from the unchanged production calibration config.
- Added server steps 08–10 for read-only config validation, the protected test
  run and read-only output validation. Only step 09 can start QSim; no local
  QSim was started while preparing the test.
- The test listener will follow the 107,618-person originally open cohort and
  its 37,417 primary `BOTH_INSIDE` trips without writing person identifiers.
  It will distinguish car/bike resource jumps from a resource ending at the
  final location of an open day.
- Output acceptance requires complete histories 0–5, final summary 5, regular
  termination, actual mode-signature changes and no unknown modes, invalid
  distances, stuck events or unverifiable/jumping chain resources.
- This entry records test preparation only. No server result, empirical
  calibration, convergence statement or production behavior change is claimed.

## 24 August 2026 — open-tour decision and complete-plan analysis correction

- The exploratory run completed iterations 0--5 and shut down regularly. It
  recorded 2,417 stuck events in iteration 0, 833 new events in iteration 5 and
  8,465 cumulatively.
- Its open-cohort diagnostic found all 107,618 baseline person IDs but zero
  current trips. MATSim 2025.0 `ExperiencedPlansService` reconstructs elements
  from experienced events and is not a complete selected-plan snapshot; the
  general history consequently represented about 216,000 rather than 324,043
  persons. Cohort mode and chain-location results from this run are invalid.
- Future `AfterMobsim` analysis uses every scenario person's selected plan,
  before the next iteration's replanning. It treats metrics as selected/routed,
  not necessarily fully executed, and fails closed against 324,043 persons,
  540,468 main trips and 160,603 `BOTH_INSIDE` trips.
- The production behavior remains `fromSpecifiedModesToSpecifiedModes`.
  `betweenAllAndFewerConstraints` is not adopted because it relaxes day-end
  consistency for chain-based car and bike. The 37,417 fixed primary trips
  (23.297821%) remain a reported limitation.
- Added separate future-run stuck metrics and a read-only existing-event
  auditor. Event mode, time and link are descriptive; no cause is inferred.

## 24 August 2026 — mode-choice calibration round 1 prepared

- Added a separate round-1 config with car fixed at 0.00 and PT 0.89, walk
  0.78 and bike -0.21. A reverse textual comparison permits only the run ID,
  output directory and these three non-reference constants to differ from the
  unchanged productive calibration config.
- The approved primary targets remain car 34%, PT 24%, bike 18% and walk 24%
  for `BOTH_INSIDE` and `ALL_PLANS`. The constants are a first ratio-guided
  step from the uncalibrated final state, not final calibrated parameters.
- Added server steps 12--14 for read-only config validation, the protected
  iterations 0--20 run, and read-only structural validation plus summary.
  Only step 13 starts MATSim; no local QSim was run.
- The prepared summary reports all iteration shares, mean/minimum/maximum for
  iterations 16--20, target gaps, secondary final Pkm shares and stuck events
  in the last five iterations. It applies no annualisation and does not reject
  a run solely for a small positive stuck count.
- The productive and open-tour configs, all scenario inputs, BAU and Fast
  Track remained unchanged during preparation.

## 25 August 2026 — round 1 validated and round 2 prepared

- Recomputed the Round-1 evidence from the copied analysis CSVs. All iterations
  0--20 are present; iteration 20 is the sole final summary. Counts are 324,043
  persons, 540,468 main trips and 160,603 `BOTH_INSIDE` trips, with zero
  unknown modes and zero invalid distances.
- Round 1 is structurally valid and directionally successful, but not
  converged. Car rose from 35.506809% in iteration 16 to 39.413336% in
  iteration 20, while walk fell from 22.779774% to 18.408747%.
- Added a versioned small calibration register; no large output was copied.
- Prepared Round 2 with car 0.00, PT 1.27, walk 1.27 and bike -0.34,
  iterations 0--40 and innovation disabled after 60%. It reloads the original
  input population and retains the production behavior and every scenario
  input.
- Added server steps 15--17. Only step 16 starts MATSim. Step 17 evaluates
  iterations 31--40 using mean, minimum, maximum, range and linear trend, plus
  secondary Pkm/distance and descriptive stuck-event diagnostics.
- No local QSim was started and no existing output was changed.
