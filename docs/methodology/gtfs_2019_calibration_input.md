# Synthetic 2019 public-transport reference supply

## Purpose and status

This input is a **synthetic 2019 reference supply extracted from the combined
forecast dataset**. It is not a historical MVV GTFS snapshot. The isolated
Analyze/Build/Validate pipeline is implemented, the derived GTFS subset is
referentially closed, and the MATSim network, schedule and vehicles pass
structural and SwissRailRaptor tests. The full 324,043-person iteration-zero
run is not yet accepted: two attempts loaded and prepared all inputs but ended
during QSim cleanup, first through the one-thread QNet cleanup path and then
through a 3.936 GB Java heap limit. A final run in a separate JVM with at least
8 GB heap remains required before mode-choice calibration starts.

No mode choice is enabled. BAU 2040, Fast Track 2040, GTFS 2037 and the current
GTFS converter are independent and unchanged.

## Source, provenance and approved interpretation

The unchanged source is
`original-input-data/mvv_gtfs_2019/gtfs_2019.zip` (61,666,332 bytes; SHA-256
`92844C3EF84167548C4E373A1B14445EA5AC211D918BDB77422EC7B2E11693C4`).
It belongs to the same combined delivery as the 2037 forecast data. The
project-approved interpretation is:

- `Analyse_2019=1` defines the synthetic reference subset;
- `Prognosenetz_2037=1` is not an exclusion criterion because unchanged routes
  may occur in both scenarios;
- routes whose `Analyse_2019` value differs from `1` are excluded;
- 13 February 2026 is only the technical GTFS activation date. It is not a
  historical reference date.

The archive has no `feed_info.txt`. No independently verifiable accompanying
documentation was available for the custom scenario flags. Their meaning is
therefore a project decision rather than externally confirmed source metadata.

## Read-only source audit

| GTFS object | Raw rows |
|---|---:|
| Agencies | 42 |
| Stops | 183,258 |
| Routes | 6,175 |
| Trips | 202,643 |
| Stop times | 3,569,531 |
| Shape points | 3,460,257 |
| Transfers | 299,378 |
| Calendar rows | 1 |
| Calendar-date exceptions | 0 |

The source contains all required GTFS tables and no broken route, trip,
service, shape, stop, transfer or parent-station references. Stop sequences
and times are consistent, and all stops have numeric WGS84 coordinates. The
feed is regional rather than Munich-only (latitude 39.084388--56.650333;
longitude 2.359120--24.744194). This broad extent is not interpreted as an
error and is not clipped at the administrative city boundary.

All raw routes incorrectly have `route_type=0`. The builder applies exactly
the custom-metadata and route-ID classification sequence already validated for
the cleaned GTFS-2037 dataset. The dominant source agency uses `unknown` as
timezone; as in the 2037 cleaning step, retained blank/unknown timezones are
normalized to `Europe/Berlin`, and invalid retained agency URLs receive an
explicit `example.invalid` placeholder. These are technical GTFS-validity
corrections, not historical evidence.

## Selection and model-space reduction

The year filter retains 5,948 routes and 185,663 trips before spatial
reduction. The same rule as the cleaned 2037 feed is then applied: a trip is
selected if it calls at two or more distinct stops inside the rectangular
extent of `scenarios/munich_base_2023/studyNetworkDense.xml`. Every stop time
of a selected trip is retained, including regional sections outside the
extent. Thus, selected services are never cut at an artificial boundary.

The referentially closed subset contains:

| Object | Rows |
|---|---:|
| Routes | 1,610 |
| Trips | 59,103 |
| Stops including required parents | 54,299 |
| Stop times | 1,099,312 |
| Shape points | 1,070,463 |
| Internal transfers | 95,387 |

| Corrected mode | Routes | Trips |
|---|---:|---:|
| Bus | 1,486 | 48,101 |
| Tram | 24 | 4,778 |
| Subway | 7 | 2,081 |
| Rail | 88 | 4,077 |
| Ferry | 5 | 66 |

The reproducible subset SHA-256 is
`3C2DCAC534A5C582CC949B56820FB7B6985CEC7BD843FBFB349DD4A00EB2F52E`;
an immediate rebuild produced the identical checksum.

## MATSim conversion

`CreateGtfs2019CalibrationTransit` is separate from
`CreateCurrentMvvTransit` and every 2037 builder. It loads the original public
Munich road network, converts WGS84 stops to EPSG:31468, uses `doNotMerge`,
extended route types and existing transfer data, creates a PT pseudonetwork,
and creates MATSim transit vehicles. Candidate files are written in a
temporary directory, reread and validated before publication to
`scenarios/munich_calibration_2019/input_transit/`.

| MATSim object | Count |
|---|---:|
| Network nodes | 249,003 |
| Network links | 560,926 |
| Stop facilities | 79,559 |
| Transit lines | 1,610 |
| Transit routes | 13,880 |
| Departures | 59,103 |
| Transit vehicles | 59,103 |
| Explicit transfer relations | 95,387 |

The number of MATSim stop facilities exceeds the GTFS stop count because
`doNotMerge` deliberately preserves route/direction-specific facilities.
Every served facility has a valid network-link reference, every departure has
a vehicle, all original road nodes and links remain present, and `car` remains
allowed on every original car link.

## Validation evidence and remaining blocker

The focused JUnit tests pass. The produced inputs reload successfully and
SwissRailRaptor returns representative bus, tram, subway and rail connections.
The validation config uses the unchanged 5-% base population, `useTransit=true`,
iteration 0 only, random seed 4711 and no mode-choice strategy.

Both full-population attempts loaded all four inputs and prepared all 324,043
persons. The first inherited one QSim thread and hit an internal QNet cleanup
null reference. The corrected config uses two QSim threads. Its run then
reached QSim but exhausted the actual 3,936 MB Maven JVM heap during cleanup.
No preceding missing-reference, unroutable-PT, invalid-schedule or invalid-
vehicle exception was logged. Nevertheless, MATSim marked the run output as
invalid, so it must not be used analytically.

The smallest remaining step is to run the shared IntelliJ configuration
**03 Validate GTFS 2019 Calibration Input** on the Uni server. Its validator
repeats the structural and representative SwissRailRaptor checks and then
executes `scenarios/munich_calibration_2019/config_input_validation.xml` in the
same Java process. The run configuration provides 12 GB heap (8 GB is the
minimum), retains iteration 0 and requires a regular controller shutdown. Only
after that pass should the common spatial analysis filter and mode-choice
calibration be started.

## Reproducibility and version control

The approved assumptions are recorded in
`original-input-data/mvv_gtfs_2019/synthetic_2019_reference_spec.csv`. Source
and derived ZIP files, the three MATSim transit inputs, and all validation
outputs are intentionally ignored because they are large or generated. Java
builders, focused tests, the validation config, this method note and the
scenario README are suitable for version control. No commit was created.
