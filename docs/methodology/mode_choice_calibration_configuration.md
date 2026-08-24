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
repository and is not set by this configuration. Once selected, it must remain
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
starts the controller. It neither deletes output nor changes parameters.

The versioned Munich municipal-boundary filter is not part of simulation
demand preparation. It will later select analysis trips whose origin and
destination main activities are both inside or on the administrative boundary.
The regional population remains simulated in full.

## Interpretation and next calibration stage

The run must not be interpreted as a final modal split, passenger-kilometre or
vehicle-kilometre result. Empirical targets with the same year, main-trip
definition and two-endpoints-inside-Munich geography are still required. The
observed 2019 car occupancy factor and its provenance are also outstanding.

After empirical calibration and validation, exactly the same constants,
scoring parameters, choice-set definition and availability assumption must be
transferred to BAU and Fast Track. The two future scenarios must not be
separately recalibrated to different modal splits. Only differences produced
under one frozen behavioural model can be interpreted as scenario effects.
