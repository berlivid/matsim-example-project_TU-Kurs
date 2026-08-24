# Synthetic-2019 mode-choice calibration configuration

## Purpose and status

`scenarios/munich_calibration_2019/config_mode_choice_calibration.xml` is a
separate, reproducible configuration for the first technical mode-choice
diagnostic. It uses the technically validated synthetic 2019 reference supply
extracted from the combined forecast dataset. It is not a historical GTFS
snapshot and it is not yet an empirically calibrated behavioural model.

The configuration is isolated from the iteration-zero input validation and
from both 2040 production scenarios. It retains the original public
five-percent population, the synthetic-2019 network with public transport,
schedule and transit vehicles, `EPSG:31468`, SwissRailRaptor, and QSim flow and
storage capacity factors of `0.05`. No population, network or public-transport
input is spatially cut or rewritten.

## Choice set and replanning

The endogenous choice set is exactly `car`, `pt`, `walk` and `bike`.
`SubtourModeChoice` uses
`fromSpecifiedModesToSpecifiedModes`, with `car` and `bike` as chain-based
modes, zero probability of a random single-trip change, and zero coordinate
distance. The single canonical replanning module contains:

| Strategy | Weight |
|---|---:|
| `ChangeExpBeta` | 0.8 |
| `ReRoute` | 0.1 |
| `SubtourModeChoice` | 0.1 |

The weights sum to one. Plan memory is four, the worst plan is removed when
necessary, and innovation is disabled after 80 percent of the configured
iterations.

`ride` remains outside endogenous mode choice. Standard MATSim `ride` would
represent an unlinked passenger trip without a matched driver or vehicle. The
project has neither a joint-trip or ride-matching model nor a compatible
trip-based passenger target. The `car` alternative therefore approximates
aggregate motorised individual passenger travel for calibration. Technical
`ride` scoring and teleportation parameters remain in the config for
compatibility, but `ride` and `other` are not offered by `SubtourModeChoice`.

For the later external-cost calculation, car vehicle-kilometres are to be
derived from simulated car passenger-kilometres with an observed 2019
occupancy factor (`Pkm/Fkm`). That empirical factor is not contained in the
original model inputs. The user-supplied annual references imply exactly 1.5
persons per vehicle; their detailed citation and spatial scope remain pending.
The factor is not set by this configuration. Once approved, it must remain
identical in BAU and Fast Track; an alternative 2040 occupancy may only be
reported as a sensitivity.

## Uncalibrated scoring vector

`car` is the reference alternative. The initial constants are deliberately:

| Mode | Constant |
|---|---:|
| `car` | 0.0 |
| `pt` | 0.0 |
| `walk` | 0.0 |
| `bike` | 0.0 |

This is an uncalibrated starting vector, not evidence that the modes have equal
attractiveness. Existing common scoring values remain unchanged: performing
utility is 6 utils/hour, mode travel-time utility is -6 utils/hour, PT waiting
utility is -6 utils/hour, line-switch utility is -1, and monetary and
distance-dependent mode values remain zero. Walk and bike retain the existing
teleported speeds and beeline factors.

No `carAvail`, driving-licence, car-ownership or vehicle-availability
attributes are present in the population. The approved provisional setting is
therefore `considerCarAvailability=false`. It avoids inventing person-level
attributes, but offers car without an individual availability constraint and
is a substantive limitation. It should later be tested against empirical
ownership and licence evidence rather than interpreted as observed behaviour.

## Population readiness

A streaming, read-only audit of the unchanged public population finds 324,043
persons, 324,043 selected plans and 540,468 main trips. Input modes are
244,934 car, 58,524 PT, 160,997 walk and 76,013 bike trips; no unknown mode is
present. There are 216,425 closed subtours in 216,425 plans. These 216,425
persons are technically eligible for a subtour mode change. The remaining
107,618 plans contain no closed subtour and therefore cannot be changed by the
configured subtour-only strategy.

The open-tour diagnostic assigns 75,149 of these plans to the same first/last
activity type at a different location and 32,469 to different first/last
activity types. Their 37,417 `BOTH_INSIDE` trips represent 23.297821% of the
primary sample. No missing or problematic endpoint location was found.

The raw population stores activity coordinates but no link IDs. For this
read-only audit, exact repeated locations are detected with a numerical
tolerance of `0.000001 m`. The runtime setting remains `coordDistance=0.0`:
the MATSim controller assigns activity link references during scenario
preparation before replanning. The distinction is technical and does not move
an activity or change the population.

## Diagnostic run and safeguards

The first diagnostic is configured for iterations 0 through 20, random seed
4711, four global threads and two QSim threads. Its protected output directory
is `scenarios/munich_calibration_2019/output/mode-choice-initial`, with
`failIfDirectoryExists`. Twenty iterations test technical operation and early
replanning behaviour; they are not a convergence claim or a final calibration.

`ValidateModeChoiceCalibrationConfig` checks the complete configuration and
input references without starting QSim. `RunMatsim2019ModeChoiceCalibration`
accepts no arguments, repeats that validation, refuses an existing output
directory, loads only the fixed 2019 config, installs SwissRailRaptor and then
starts the controller. It neither deletes output nor changes parameters. The
config also enables normal final plan output for reproducible postprocessing;
this changes output recording, not behavioural parameters.

The runner installs `ModeChoiceCalibrationIterationListener`. At the
`AfterMobsim` event it reads the complete selected-plan snapshot after the
current mobsim and before the next iteration's replanning. Rows therefore
describe selected and routed plans; stuck-event records separately identify
plans that may not have been fully executed.
It writes small analysis files below the protected run output. The independent
`AnalyzeModeChoiceCalibrationOutput` postprocessor can recreate the final
summary from the complete final `output_plans` file without starting QSim. The
complete metric, distance and target rules are documented in
`mode_choice_output_analysis.md`.

MATSim 2025.0 also provides `betweenAllAndFewerConstraints`. It adds an
unclosed root subtour and relaxes mass conservation for that root. Car and bike
remain chain-based and begin at the first activity, but the complete daily plan
need not return them there. All 107,618 currently open plans are structurally
eligible in the initial canonical, monomodal population. The setting was
explored but is not approved: an open end location creates an inter-day
vehicle-position limitation for chain-based car and bike. The productive
configuration retains `fromSpecifiedModesToSpecifiedModes`; the fixed
23.297821% of primary trips is reported as a limitation.

The exploratory implementation remains as a fully separate five-iteration test
configuration, not as a change to the production calibration. The test differs
only in run ID, protected output directory, last iteration and behavior. Its
aggregate listener applies MATSim's facility/link chain-resource sequence to
the originally open cohort and distinguishes an invalid resource jump from the
deliberately relaxed end-of-day location. The prepared test method and
decision are documented in `mode_choice_open_tour_test.md`. Its cohort
diagnostic was invalid because it used incomplete experienced-plan
reconstructions, so it is retained only as experimental provenance and not as
evidence for a production change.

The versioned Munich municipal-boundary filter is not part of simulation
demand preparation. It will later select analysis trips whose origin and
destination main activities are both inside or on the administrative boundary.
The regional population remains simulated in full.

## Interpretation and next calibration stage

The first run must not be interpreted as calibrated. Its copied local analysis
contains only iteration 20 because the former standalone postprocessor
overwrote the history; convergence cannot be assessed. Primary four-mode trip
targets are now versioned, while mean-distance targets and detailed source
provenance remain outstanding. Annual Pkm and Fkm are reference quantities,
not direct service-day calibration targets.

After empirical calibration and validation, exactly the same constants,
scoring parameters, choice-set definition and availability assumption must be
transferred to BAU and Fast Track. The two future scenarios must not be
separately recalibrated to different modal splits. Only differences produced
under one frozen behavioural model can be interpreted as scenario effects.

## Calibration round 1

`config_mode_choice_calibration_round_1.xml` is a mechanically controlled copy
of the unchanged productive calibration config. The only content differences
are run ID, protected output directory and the constants PT 0.89, walk 0.78
and bike -0.21. Car remains the zero reference. A textual reverse-diff
validator proves that behavior, modes, chain constraints, seed, iterations,
strategy weights, inputs, capacities, threads, routing, scoring parameters and
the 43-hour QSim horizon remain identical. In particular,
`fromSpecifiedModesToSpecifiedModes` remains active.

The 34% car, 24% PT, 18% bike and 24% walk targets apply to `BOTH_INSIDE` main
trips and `ALL_PLANS`. The constants are a first ratio-guided adjustment from
the uncalibrated final state; they are neither final estimates nor a new set of
observations. Approximately 23.3% of primary trips remain in plans without a
closed subtour and cannot be changed by this strategy.

The server output validator requires a complete selected-plan history for
iterations 0--20, the fixed structural counts, zero unknown modes and zero
invalid distances. It summarizes every iteration and the mean/minimum/maximum
over iterations 16--20, reports target gaps in percentage points, and uses
final Pkm shares only as secondary validation. It never annualizes the
simulated day. Positive stuck events are reported for the last five iterations
and near the 43-hour boundary but do not alone invalidate the calibration.
Round 2 should be decided from late-iteration target gaps, trend stability,
secondary plausibility metrics and the descriptive stuck-event pattern.
