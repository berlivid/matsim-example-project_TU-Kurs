# Literature-based scoring diagnostic for the synthetic 2019 Munich scenario

## Purpose and status

This configuration is a short technical and behavioural diagnostic, not a
calibrated model. It provides a transparent alternative starting point to the
earlier automatic constant-adjustment experiments. It runs the unchanged
regional five-percent population and validated synthetic 2019 public-transport
supply for iterations 0--10. It contains no empirical modal-share targets and
does not modify an alternative-specific constant automatically.

The diagnostic asks whether a deliberately simple, literature-informed scoring
vector produces technically coherent behaviour before any scenario-specific
constant calibration. Its output cannot by itself demonstrate convergence or
fitness for the thesis scenarios.

## Why the former Munich values are not a validated mode-choice vector

The public Munich scenario repository states that its plans are output from
the MITO travel-demand model. Moeckel et al. describe the associated
application as one in which MATSim performed dynamic traffic assignment:
behavioural adjustments other than route choice were disabled and the demand
was supplied by MITO. The old Munich `configBase.xml` therefore documents a
technically usable assignment configuration, but its common
`marginalUtilityOfTraveling=-6` values and zero monetary distance rates are not
evidence that those values were estimated or validated as a Munich mode-choice
model. They are not adopted as such here.

Sources: the [original Munich scenario](https://github.com/matsim-scenarios/matsim-munich),
its [base configuration](https://github.com/matsim-scenarios/matsim-munich/blob/master/scenarios/tumTbBase/configBase.xml),
and [Moeckel et al. (2020)](https://doi.org/10.1155/2020/1902162).

## Scoring logic

The MATSim scoring function evaluates both activity performance and travel.
Setting the direct mode-specific
`marginalUtilityOfTraveling_util_hr` to zero therefore does not make travel
time free. Travel still displaces activity time and consequently carries the
opportunity cost associated with the positive utility of performing
activities. The [MATSim User Guide](https://www.matsim.org/files/book/partOne-latest.pdf)
explains this resource-value mechanism. The official configuration source also
describes mode-specific travel utility as additional to the opportunity cost
of time and records zero as the VSP reference value.

PT waiting receives an additional `-6 utils/hour` because waiting for a
vehicle is a distinct burden on top of the time removed from activities. A
line switch receives `-1 util`. These values are transparent starting
assumptions, not estimates from Munich observations.

Walk is the permanent reference alternative and its ASC is exactly zero. The
[official MATSim consistency checker](https://matsim.org/doxygen/_vsp_config_consistency_checker_impl_8java_source.html)
warns that a non-zero walk constant is also applied to PT access and egress
walk legs. Fixing walk at zero avoids that internal inconsistency and provides
an identifiable reference for later calibration. The next calibration stage,
if authorised after reviewing this diagnostic, may adjust only car, PT and
bike ASCs.

All four initial ASCs are reset to zero so that this run diagnoses the stated
structural and transferred parameters without carrying forward constants from
the earlier calibration paths.

## Parameter vector and provenance

| Parameter | Value | Status and rationale |
|---|---:|---|
| Activity performing utility | `6.0 utils/hour` | MATSim structural starting value retained from the technical 2019 basis; it creates the opportunity cost of travel time. |
| Marginal utility of money | `1.0 utils/money unit` | Inherited technical normalization; one model money unit is interpreted as one euro for the transferred car-cost assumption. |
| Direct travel-time utility, all four choice modes | `0.0 utils/hour` | Transferred modelling assumption consistent with isolating the opportunity-cost mechanism; requires scenario-specific validation. |
| Direct distance utility, all four choice modes | `0.0 utils/metre` | Transferred simplifying assumption; monetary car cost remains separate. |
| PT waiting utility | `-6.0 utils/hour` | Transferred modelling assumption, additional to displaced activity time. |
| Line-switch utility | `-1.0 util` | Inherited technical setting, not empirically re-estimated here. |
| Car monetary distance rate | `-0.00020 money units/metre` | Transferred assumption equivalent to EUR 0.20/km. The value matches the versioned Bavaria-eqasim default car cost, but is not claimed as a Munich estimate. |
| PT, walk and bike monetary distance rates | `0.0` | Simplifying assumption for this diagnostic; fares and ownership costs are not modelled. |
| Car, PT, walk and bike ASCs | `0.0` | Neutral diagnostic start. Walk remains fixed; the other three still require Munich-specific calibration. |
| Walk routing speed | `1.333333333 m/s` (`4.8 km/h`) | Munich empirical observation transferred from Rayaprolu's reported weighted average. |
| Bike routing speed | `3.805555556 m/s` (`13.7 km/h`) | Munich empirical observation transferred from Rayaprolu's reported weighted average. |
| Beeline-distance factor | `1.3` | Inherited routing assumption, not an observed Munich coefficient. |

Rayaprolu reports weighted average observed speeds of 4.8 km/h for walk and
13.7 km/h for bicycle in the Munich mode-choice dataset; these are used only
as teleported routing speeds. See [Rayaprolu (2017)](https://www.mos.ed.tum.de/fileadmin/w00ccp/tb/theses/Rayaprolu_2017.pdf).

The car-cost value is transferred from the versioned
[Bavaria-eqasim cost parameters](https://github.com/TUM-VT/eqasim-java-bavaria/blob/da91c58746f15540572cd585f6cb98da662f4c03/bavaria/src/main/java/org/eqasim/bavaria/mode_choice/parameters/BavariaCostParameters.java).
It is a transparent plausibility assumption, not empirical evidence from the
synthetic population.

## Technical settings inherited without behavioural interpretation

The original population, network, transit schedule, transit vehicles,
`EPSG:31468`, five-percent flow and storage capacity factors, seed 4711,
activity types and typical durations, early-departure and late-arrival
settings, transit simulation and SwissRailRaptor routing remain unchanged. The
48-hour QSim horizon is retained from the later technical cutoff diagnosis so
that the known 43-hour end-of-simulation artefact is not reintroduced. It is a
technical boundary, not a calibrated behavioural parameter.

Replanning uses `ChangeExpBeta=0.8`, `ReRoute=0.1` and
`SubtourModeChoice=0.1`, with `BrainExpBeta=1.0`. The only choice alternatives
are car, PT, walk and bike; car and bike remain chain based.

`considerCarAvailability=false` is a modelling limitation. The source
population has no defensible licence, household vehicle-ownership or
person-specific availability attributes. Inventing them would create false
precision. The diagnostic consequently permits car as an alternative for all
agents and must be interpreted accordingly.

## Parameters deliberately not transferred

The versioned
[Bavaria-eqasim mode parameters](https://github.com/TUM-VT/eqasim-java-bavaria/blob/da91c58746f15540572cd585f6cb98da662f4c03/bavaria/src/main/java/org/eqasim/bavaria/mode_choice/parameters/BavariaModeParameters.java)
are retained only as comparison benchmarks. Their discrete-choice time and
alternative coefficients are not copied into classic MATSim scoring.
[Hörl (2021)](https://doi.org/10.1016/j.procs.2021.03.088) shows that directly
using discrete-choice parameters in MATSim is not technically sound without a
consistent integration of the choice-error structure.

The [Vienna MATSim study](https://www.mdpi.com/2071-1050/14/1/428) is a
methodological comparator showing that modal constants and other validation
indicators can be handled explicitly. Its numerical parameters are not
transferred to Munich.

## Interpretation and next decision

Physical modal shares, passenger-kilometres and mean trip distances are
outputs for validation. ASCs change relative plan utilities; they cannot
directly force route distances or absolute Pkm totals. Review of the short run
must therefore separate technical execution, modal response, distance
plausibility and convergence.

If the diagnostic is technically sound, only car, PT and bike constants may be
calibrated against the approved Munich-resident trip-share evidence, while the
walk constant remains zero. Any scoring specification ultimately accepted for
the thesis must then be frozen and used identically in BAU and Fast Track.
Otherwise scenario differences would combine infrastructure effects with a
change in behavioural assumptions.

## Reproduction

1. Run `05 Validate Literature-Based 2019 Scoring Diagnostic` and require the
   explicit validation PASS.
2. Confirm that
   `scenarios/munich_calibration_2019/output/literature-based-scoring-diagnostic`
   does not exist.
3. Run `06 Run Literature-Based 2019 Scoring Diagnostic` once on the university
   server.
4. After normal completion, run
   `07 Analyze Literature-Based 2019 Scoring Diagnostic` on the server. This
   read-only process validates the output config and protected inputs, streams
   the final standard trip and event files, and publishes only the small
   generated `analysis` folder.
5. Copy that `analysis` folder to the local project and review it before
   preparing any ASC-calibration round. The large plans and events remain
   ignored and server-local.

## Read-only result analysis

The primary final-result scope is the established territorial indicator
`BOTH_INSIDE`: both trip endpoints are inside or on the Munich municipal
boundary. The complete regional population remains simulated, while
`ORIGIN_ONLY`, `DESTINATION_ONLY`, `BOTH_OUTSIDE` and invalid-coordinate trips
are reported separately. This diagnostic deliberately predates the later
resident-cohort architecture and must not be described as a residence-based
calibration.

The analyzer prefers MATSim's standard `output_trips.csv.gz`, uses its analysis
main mode, travelled distance and travel time, and reads it as a stream. If
that standard file is unavailable, the documented fallback reads only selected
final plans and sums their stage-aware routed leg distances and times. It never
mixes these definitions silently: the report records the source used.

For each of car, PT, bike and walk, the analyzer reports final trip shares,
sample passenger-kilometres, Pkm shares, factor-20 daily sample expansion, mean
distance and mean travel time. No annualization is applied. Car
passenger-kilometres must not be interpreted as vehicle-kilometres; reliable
car Fkm require a separate event-based vehicle analysis. Standard MATSim
iteration mode shares, when available, cover the whole simulated population
and are labelled accordingly. They are not substituted for unavailable
iteration-specific Munich `BOTH_INSIDE` shares.

PersonStuckEvents are streamed and summarized by mode, hour and exact time,
including observations at the 48-hour boundary. The analyzer reports affected
persons with at least one `BOTH_INSIDE` trip but does not infer causes. All
validation and large-file reads must succeed before the five reports are
published atomically. Existing analysis is never overwritten.

Local preparation compiles and validates the configuration only. It does not
start Controller or QSim.
