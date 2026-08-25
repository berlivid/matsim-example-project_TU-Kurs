# Mode-choice calibration rounds 0 and 1

## Purpose and evidence status

This note preserves the calibration vectors, output locations and verified summary statistics without copying large MATSim outputs into Git. Trip shares refer to `BOTH_INSIDE` main trips in `ALL_PLANS`: both main-activity endpoints are inside or on the Munich municipal boundary, and each main trip is assigned once to `car`, `pt`, `bike` or `walk`. Trip shares are the primary calibration target. Passenger-kilometre shares are secondary validation indicators and are not annualised.

The source outputs remain ignored simulation artifacts. The numbers below were recomputed from their CSV files rather than transcribed without checking. The versioned machine-readable record is `mode_choice_calibration_history.csv`.

## Round 0: uncalibrated reference

Round 0 used constants `car = 0.00`, `pt = 0.00`, `walk = 0.00` and `bike = 0.00`. Only iteration 20 is retained in the copied analysis history, so no convergence window can be reconstructed and no convergence claim is permissible.

| Mode | Iteration-20 trip share | Target | Difference | Final Pkm share |
|---|---:|---:|---:|---:|
| car | 41.295763419% | 34% | +7.295763419 pp | 48.856014274% |
| pt | 13.843940357% | 24% | -10.156059643 pp | 15.732633872% |
| bike | 26.705033820% | 18% | +8.705033820 pp | 22.707237871% |
| walk | 18.155262404% | 24% | -5.844737596 pp | 12.704113984% |

These values were used only to determine the direction of the first constant adjustment. The Round-0 iteration history file and final summary are byte-identical because the former standalone postprocessor retained only the final iteration.

## Round 1: first adjusted vector

Round 1 used `car = 0.00`, `pt = 0.89`, `walk = 0.78` and `bike = -0.21`. The output passed structural checks for all iterations 0--20, 324,043 persons, 540,468 main trips, 160,603 `BOTH_INSIDE` trips, no unknown modes and no invalid distances.

| Mode | Mean 16--20 | Minimum | Maximum | Target | Mean minus target | Iteration-20 Pkm share |
|---|---:|---:|---:|---:|---:|---:|
| car | 37.936402184% | 35.506808715% | 39.413335990% | 34% | +3.936402184 pp | 46.670320498% |
| pt | 19.475850389% | 19.390671407% | 19.550070671% | 24% | -4.524149611 pp | 21.595230433% |
| bike | 22.713647939% | 22.322746150% | 22.844529679% | 18% | +4.713647939 pp | 19.128901410% |
| walk | 19.874099488% | 18.408747035% | 22.779773728% | 24% | -4.125900512 pp | 12.605547660% |

Stuck-event counts for iterations 16--20 were 796, 399, 300, 258 and 232. They are reported as technical diagnostics and are not converted into causal statements.

Round 1 is **structurally valid**, **directionally successful**, **not yet converged**, and **not the final calibration**. Car increased from 35.506808715% in iteration 16 to 39.413335990% in iteration 20, while walk fell from 22.779773728% to 18.408747035%. Therefore, the mean across iterations 16--20 is not a stable endpoint.

For auditability, the SHA-256 values of the two principal Round-1 evidence files are:

- `mode_choice_iteration_metrics.csv`: `70C5A7C90B9FA327F653AA88A4548CE94D89D9B8D2DE9AA0BB0BD1254E05D69B`
- `mode_choice_final_summary.csv`: `3BE613B1E38FB9F312AFEFED0CDF5B76697835CBE0BF1D85EC782A2ECFF4FE90`
- `stuck_events_iteration_metrics.csv`: `077C1CC1F403A0AEF04CD8FC427C414C8C56ED8BE3485771D5105FD306C8445A`

## Decision for round 2

Car remains the reference alternative. Round 2 starts from `car = 0.00`, `pt = 1.27`, `walk = 1.27` and `bike = -0.34`; Round 1 used 0.00, 0.89, 0.78 and -0.21 respectively. The second adjustment follows the remaining discrepancy in the mutable population and the ratio between target and simulated trip shares. Because Round 1 still exhibited late dynamics, these constants remain an iterative calibration step rather than final behavioural estimates. Pkm shares do not determine the adjustment.

Round 2 uses 40 iterations and disables innovation after 60% of the run. This leaves approximately 16 iterations for selection and stabilisation among existing plans. Constants address the location of the modal-share result; the longer post-innovation selection period addresses convergence. These are separate methodological functions. The same iteration logic is to be retained for subsequent calibration rounds and, once the common calibration is final, for comparable BAU and Fast Track runs.
