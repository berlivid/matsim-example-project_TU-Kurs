# Iteration-0 output-config diagnostic

## Inputs and result

This read-only comparison used:

- productive config: `scenarios/munich_calibration_2019/config_resident_mode_choice_calibration.xml`;
- expected in-memory config: the productive config after `RunMatsim2019ResidentModeChoiceIteration0Validation.applyApprovedOverrides`;
- copied server output config: `generated/iteration0_output_config_diagnostic/munich-calibration-2019-resident-iteration-0-validation.output_config.xml`.

The copied file was not modified. The positional snapshot failure was not a QSim failure: the output validator had already confirmed normal controller shutdown. No unexpected semantic difference was found.

## Complete difference list

| Module | Parameter or parameter-set identity | Productive value | Expected iteration-0 value | Actual server-output value | Classification |
|---|---|---|---|---|---|
| `controller` | parameter `runId` | `munich-calibration-2019-resident-initial` | `munich-calibration-2019-resident-iteration-0-validation` | `munich-calibration-2019-resident-iteration-0-validation` | `APPROVED_OVERRIDE` |
| `controller` | parameter `outputDirectory` | `scenarios/munich_calibration_2019/output/resident-mode-choice-initial` | `scenarios/munich_calibration_2019/output/resident-mode-choice-iteration-0-validation` | `scenarios\munich_calibration_2019\output\resident-mode-choice-iteration-0-validation` | `APPROVED_OVERRIDE` |
| `controller` | parameter `lastIteration` | `20` | `0` | `0` | `APPROVED_OVERRIDE` |
| `swissRailRaptor` | parameter `intermodalAccessEgressModeSelection` in runtime-added default module | absent before controller module installation | absent before controller module installation | `CalcLeastCostModePerStop` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `intermodalLegOnlyHandling` in runtime-added default module | absent before controller module installation | absent before controller module installation | `forbid` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `scoringParameters` in runtime-added default module | absent before controller module installation | absent before controller module installation | `Default` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `transferCalculation` in runtime-added default module | absent before controller module installation | absent before controller module installation | `Initial` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `transferPenaltyBaseCost` in runtime-added default module | absent before controller module installation | absent before controller module installation | `0.0` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `transferPenaltyCostPerTravelTimeHour` in runtime-added default module | absent before controller module installation | absent before controller module installation | `0.0` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `transferPenaltyMaxCost` in runtime-added default module | absent before controller module installation | absent before controller module installation | `Infinity` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `transferPenaltyMinCost` in runtime-added default module | absent before controller module installation | absent before controller module installation | `-Infinity` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `transferWalkMargin` in runtime-added default module | absent before controller module installation | absent before controller module installation | `5.0` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `useCapacityConstraints` in runtime-added default module | absent before controller module installation | absent before controller module installation | `false` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `useIntermodalAccessEgress` in runtime-added default module | absent before controller module installation | absent before controller module installation | `false` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `useModeMappingForPassengers` in runtime-added default module | absent before controller module installation | absent before controller module installation | `false` | `EXPECTED_RUNTIME_NORMALIZATION` |
| `swissRailRaptor` | parameter `useRangeQuery` in runtime-added default module | absent before controller module installation | absent before controller module installation | `false` | `EXPECTED_RUNTIME_NORMALIZATION` |

No `MATSIM_SERIALIZATION_OR_ORDERING` difference remained after loading both XML files through MATSim. The backslash representation of the approved output path is a platform serialization detail of the same path and is normalized only for semantic path comparison.

## Unchanged protected semantics

All other loaded config fields were equal. In particular, the comparison found no difference in population, network, transit schedule, transit vehicles, coordinate system, random seed, global or QSim thread counts, capacity factors, 43-hour QSim horizon, scoring parameters, mode constants, routing parameters, transit settings, strategy names/weights/subpopulation scopes, or `SubtourModeChoice` modes, chain-based modes and behavior.

The 13 additional fields are exactly the default parameters of MATSim 2025.0's `SwissRailRaptorConfigGroup`, which is installed by the already approved `SwissRailRaptorModule` before MATSim writes the output config. They are harmless but must be matched strictly against those dependency defaults; arbitrary extra modules or parameter sets remain invalid.
