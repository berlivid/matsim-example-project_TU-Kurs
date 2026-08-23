# Munich synthetic-2019 calibration input

## Analytical role and current status

This isolated scenario supplies the public-transport input for a later common
mode-choice calibration. It combines the original 5-% population and public
road network with a **synthetic 2019 reference supply extracted from the
combined forecast dataset**. It is not a historical MVV GTFS snapshot. The
service date 13 February 2026 is only a technical activation date and must not
be interpreted as the historical reference year.

The GTFS subset, MATSim transit inputs, reference checks and representative
SwissRailRaptor connections are structurally validated. Full end-to-end
approval remains conditional on a normal iteration-zero shutdown on the Uni
server. No mode-choice strategy is active in the validation configuration.

## Required server input

The unchanged source archive must be copied manually to:

`original-input-data/mvv_gtfs_2019/gtfs_2019.zip`

Its expected SHA-256 is
`92844C3EF84167548C4E373A1B14445EA5AC211D918BDB77422EC7B2E11693C4`.
The source ZIP is intentionally ignored by Git and is not transferred through
the repository. The derived GTFS ZIP and all three MATSim transit files are
also ignored; they must be rebuilt on the server from the versioned Java code
and specification.

## Point-and-click sequence on the Uni server

Open the project as a Maven project in IntelliJ and run these shared
configurations in order from the project root:

1. **01 Build Synthetic GTFS 2019** runs
   `org.matsim.project.prepare.BuildSyntheticGtfs2019Reference` with its
   implemented `build` argument. It validates the raw archive, applies the
   approved `Analyse_2019=1` and model-space rules, preserves complete selected
   trips, corrects route types and writes
   `original-input-data/mvv_gtfs_2019/synthetic_2019_reference.zip`.
2. **02 Create GTFS 2019 Calibration Transit** runs
   `org.matsim.project.prepare.CreateGtfs2019CalibrationTransit` with an 8 GB
   maximum heap. It creates and rereads `network-with-pt.xml.gz`,
   `transitSchedule.xml.gz` and `transitVehicles.xml.gz` under
   `scenarios/munich_calibration_2019/input_transit/`.
3. **03 Validate GTFS 2019 Calibration Input** runs
   `org.matsim.project.prepare.ValidateGtfs2019CalibrationInput` with a 12 GB
   maximum heap. It first repeats structural and representative PT-routing
   checks, then loads the original 324,043-person 5-% population and executes
   only iteration 0 from `config_input_validation.xml`.

The final validation requires at least 8 GB Java heap; 12 GB is recommended
and is configured for step 3. Its output directory must not already exist
because the configuration deliberately uses `failIfDirectoryExists`.

## Local validation evidence and limitation

The local build, referential checks and representative bus, tram, subway and
rail routes passed. The local full-population attempt loaded and prepared all
324,043 persons but stopped during QSim cleanup because the Maven JVM was
limited to 3,936 MB heap. The preceding logs contained no broken GTFS
reference, missing vehicle, invalid stop-link reference or representative
PT-routing error. Nevertheless, MATSim correctly marked that run as invalid;
its output must not be used for analysis.

Consequently, the input is structurally ready for transfer but is not fully
end-to-end approved until step 3 finishes normally on the Uni server. Only
after that result should the common spatial analysis filter and mode-choice
calibration be started. BAU 2040, Fast Track 2040 and GTFS 2037 are independent
of this workflow and must not be rebuilt by these configurations.

For provenance, selection counts, conversion assumptions and methodological
limitations, see
[`docs/methodology/gtfs_2019_calibration_input.md`](../../docs/methodology/gtfs_2019_calibration_input.md).
