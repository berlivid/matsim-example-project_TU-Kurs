# Legacy BOTH_INSIDE calibration preliminary rounds

## Status and scope

The initial calibration and rounds 1 and 2 are technical preliminary
experiments. Their primary metric was `BOTH_INSIDE`, `ALL_PLANS`: complete main
trips whose origin and destination main activities are inside or on the Munich
municipal boundary. This is an origin-and-destination territorial filter, not
a Munich-resident cohort.

The final thesis method will retain the full regional population and will use
all trips made by Munich residents for calibration and primary analysis.
Residence will later be determined from the home activity. No home-location
classification, population assignment or resident calibration config is
implemented by this structural cleanup. `BOTH_INSIDE` may remain a secondary
territorial indicator if it is labelled consistently.

## Authoritative targets

The Schröder targets remain authoritative. The trip shares are:

| Mode | Trip share |
|---|---:|
| car | 34% |
| pt | 24% |
| bike | 18% |
| walk | 24% |

The absolute annual passenger-kilometres and shares normalized from those
values are:

| Mode | Million pkm/year | Normalized pkm share |
|---|---:|---:|
| car | 10,637.49 | 62.9453% |
| pt | 4,510.08 | 26.6875% |
| bike | 1,131.50 | 6.6954% |
| walk | 620.50 | 3.6717% |

The rounded figures 63/27/7/4 sum to 101% and must not be used as exact
targets. The normalized values above derive from the four absolute annual pkm
values and sum to 100% subject to displayed rounding.

## Round 1

Round 1 used constants `car = 0.00`, `pt = 0.89`, `walk = 0.78` and
`bike = -0.21` for iterations 0 through 20. Its output passed the structural
checks for 324,043 people, 540,468 main trips and 160,603 `BOTH_INSIDE` trips,
with no unknown modes or invalid distances. The late-iteration results moved
in the intended general direction but were not stable: the round was
directionally useful, not converged and not a final calibration.

The existing ignored Round-1 output and the versioned calibration history are
preserved unchanged.

## Round 2

Round 2 was prepared with constants `car = 0.00`, `pt = 1.27`, `walk = 1.27`
and `bike = -0.34`, iterations 0 through 40, and innovation disabled after 60%
of the run. It retained the same `BOTH_INSIDE` target scope and was therefore
still a preliminary territorial calibration design. Its Java classes,
validators, tests and config remain in place until a resident-based pipeline
exists; only the obsolete point-and-click run configurations have been
removed.

## Preservation decision

No calibration output, config, analyzer, validator or round-specific Java
class was deleted. The active IntelliJ list no longer advertises the completed
or superseded experiments. Detailed Round-1 evidence remains in
`docs/calibration/mode_choice_calibration_round_1.md`, and the machine-readable
record remains in `docs/calibration/mode_choice_calibration_history.csv`.
