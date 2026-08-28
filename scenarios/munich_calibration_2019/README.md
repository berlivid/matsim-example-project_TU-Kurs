# Munich synthetic-2019 calibration input

## Analytical role and current status

This isolated scenario supplies the public-transport input for a later common
mode-choice calibration. It combines the original 5-% population and public
road network with a **synthetic 2019 reference supply extracted from the
combined forecast dataset**. It is not a historical MVV GTFS snapshot. The
service date 13 February 2026 is only a technical activation date and must not
be interpreted as the historical reference year.

The GTFS subset, MATSim transit inputs, reference checks, temporal checks and
representative SwissRailRaptor connections are structurally validated. Full
end-to-end approval remains conditional on a normal iteration-zero shutdown on
the Uni server. No mode-choice strategy is active in the validation
configuration.

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
   `scenarios/munich_calibration_2019/input_transit/`. It also audits all
   schedule times and writes the finite, schedule-derived QSim end time to the
   validation config.
3. **03 Validate GTFS 2019 Calibration Input** runs
   `org.matsim.project.prepare.ValidateGtfs2019CalibrationInput` with a 12 GB
   maximum heap. It first repeats structural and representative PT-routing
   checks, then loads the original 324,043-person 5-% population and executes
   only iteration 0 from `config_input_validation.xml`.

The final validation requires at least 8 GB Java heap; 12 GB is recommended
and is configured for step 3. Its output directory must not already exist
because the configuration deliberately uses `failIfDirectoryExists`.

## Recovery from the unbounded server run

The non-terminating server run used `qsim.endTime=undefined`. The similarly
named `hermes.endTime=30:00:00` did not control QSim. A read-only audit found
the last accepted vehicle arrival at `42:30:00`; the corrected generator sets
QSim end time to the first following complete hour, `43:00:00`. The validator
now fails before QSim if the end time is missing, non-finite, inconsistent with
the schedule, or if any accepted vehicle reaches it. Services reaching the
following service day are retained, subject to a fail-closed 48-hour maximum.

For the existing Uni-server checkout, use this exact recovery sequence:

1. pull the correction commit;
2. delete only
   `scenarios/munich_calibration_2019/output/input-validation-qsim2`, which is
   the incomplete output of the stopped run;
3. rerun **03 Validate GTFS 2019 Calibration Input**.

Do not rerun step 02 for this recovery: the GTFS subset and the three MATSim
transit inputs did not change. Step 02 will apply the same time policy during a
future clean transit rebuild. Step 01 is also unnecessary for this recovery.

## Local validation evidence and limitation

The local build, referential checks and representative bus, tram, subway and
rail routes passed. Focused tests also cover the explicit finite end time,
excessive-duration rejection and valid post-midnight service. No full local
QSim was started for this correction. The earlier local full-population attempt
was limited by a 3,936 MB Maven heap, while the later server attempt exposed
the independent undefined-end-time defect. Neither incomplete output may be
used for analysis.

Consequently, the input is structurally ready for transfer but is not fully
end-to-end approved until step 3 finishes normally on the Uni server. Only
after that result should the common spatial analysis filter and mode-choice
calibration be started. BAU 2040, Fast Track 2040 and GTFS 2037 are independent
of this workflow and must not be rebuilt by these configurations.

For provenance, selection counts, conversion assumptions and methodological
limitations, see
[`docs/methodology/gtfs_2019_calibration_input.md`](../../docs/methodology/gtfs_2019_calibration_input.md).

## Literature-based scoring diagnostic

`config_literature_based_scoring_diagnostic.xml` is a separate iterations-0--10
diagnostic. It starts from the unchanged validated 2019 population, network,
schedule and vehicles, retains SwissRailRaptor and the five-percent capacity
factors, and uses the technically established 48-hour horizon. It does not
contain modal-share targets or an automatic ASC update.

The choice set is exactly car, PT, walk and bike; car and bike remain chain
based. All four ASCs start at zero and walk is permanently fixed as the
reference. Direct mode-specific travel-time utilities are zero, while the
positive activity-performing utility preserves the opportunity cost of time.
Car has a transferred EUR 0.20/km operating-cost assumption. Walk and bike use
the Munich empirical speeds 4.8 and 13.7 km/h. Car availability is not checked
because the population contains no defensible licence, ownership or
availability attributes; this is a documented limitation.

Run `05 Validate Literature-Based 2019 Scoring Diagnostic` first. Only after
PASS, and only on the university server, run
`06 Run Literature-Based 2019 Scoring Diagnostic`. The protected output is
`output/literature-based-scoring-diagnostic` and must not already exist. See
[`literature_based_scoring_diagnostic.md`](../../docs/methodology/literature_based_scoring_diagnostic.md)
for parameter provenance and interpretation.
