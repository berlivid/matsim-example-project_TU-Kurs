# Resident iteration-0 stuck-resolution audit

This fail-closed audit reuses the generated root-cause rows, reads the versioned road source and synthetic 2019 calibration inputs, and streams only the 48-hour events once. It does not start Controller or QSim and does not change any input or preserved output.

## Car-link audit

| Link | Entries | Exits | Remaining vehicles | All persistent persons | Car-root-cause persons | Source and neighborhood preserved | Dead end | Classification |
|---|---:|---:|---:|---:|---:|---|---|---|
| `16208` | 1575 | 1257 | 318 | 318 | 317 | true | false | `PLAUSIBLE_BUT_SEVERE_CONGESTION` |
| `419626` | 1673 | 1266 | 407 | 407 | 403 | true | false | `PLAUSIBLE_BUT_SEVERE_CONGESTION` |
| `453133` | 1395 | 1248 | 147 | 147 | 147 | true | false | `PLAUSIBLE_BUT_SEVERE_CONGESTION` |

The synthetic PT-network build preserves each target road link and every audited adjacent car link semantically relative to `studyNetworkDense.xml`: endpoints, length, free speed, capacity, lanes, modes and source attributes are unchanged. Every target has an outgoing car continuation and none has a confirmed dead end or source-to-calibration capacity discontinuity. Congestion alone is not treated as a data error.

## PT service audit

| Service evidence class | All PT persons | Munich residents | Share of 68,770 residents |
|---|---:|---:|---:|
| `NO_LATER_SERVICE` | 250 | 72 | 0.1047% |
| `NO_COMPATIBLE_CONNECTION` | 180 | 108 | 0.1570% |
| `COMPATIBLE_SERVICE_NOT_BOARDED` | 83 | 38 | 0.0553% |
| `TRANSFER_MISSED_AFTER_DELAY` | 265 | 48 | 0.0698% |
| `INSUFFICIENT_EVIDENCE` | 18 | 0 | 0.0000% |

Planned departure and final waiting-time distributions (seconds):

| Measure | Minimum | Median | P95 | Maximum |
|---|---:|---:|---:|---:|
| Planned departure | 517.000 | 39897.000 | 74693.000 | 86820.000 |
| Final waiting start | 18361.000 | 70026.000 | 92558.000 | 157333.000 |
| Waiting to 48h | 15467.000 | 102765.000 | 124635.000 | 154439.000 |

Actual-compatible-pass counts use `VehicleDepartsAtFacility` events, matched to the schedule by transit line and route. A scheduled departure is not reported as an actually passing vehicle unless that event exists. Missing stop evidence remains `INSUFFICIENT_EVIDENCE`. No additional departure is inferred or invented.

## Mode reconciliation

All 1,701 persons reconcile exactly. The set comprises 905 car-routing cases and 796 PT-routing cases. Of the PT requests, 18 have physical `walk` PersonStuck leg modes but retain `pt` as their computational routing/choice mode; 0 of these are Munich residents. They are PT requests whose realized physical representation is walk, not endogenous choice changes and not an additional person category.

The Munich-resident subset is exactly 818 persons: 552 car-routing and 266 PT-routing cases (1.1895% of 68,770 residents). Car cases affect 0.8027% and PT cases affect 0.3868% of the resident cohort. The full set also contains 687 regional-background and 196 unresolved-background persons.

## Fail-closed decision

No objective source-to-calibration road-network error or confirmed synthetic-schedule pipeline error was demonstrated. Therefore this task implements no model correction. Globally raising capacity, extending the horizon or inventing services would conceal rather than resolve the observed states. The scientifically defensible options are to (1) accept and sensitivity-test the documented resident execution loss of 1.1895% (0.8027% car and 0.3868% PT), with explicit exclusion/reporting rules, or (2) approve a separately justified modeling assumption for late-day demand/service or road congestion and test it in isolation. The 83 compatible-pass/no-boarding cases, including 38 residents (0.0553%), require a capacity/boarding interpretation before they can support any correction. Run 12 remains blocked pending that methodological decision; another iteration-zero run is required only after an approved input or modeling correction, not with the unchanged setup.
