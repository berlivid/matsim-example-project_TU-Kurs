# Legacy Open-Tour mode-choice experiment

## Status

This experiment was a protected technical diagnostic for the synthetic-2019
scenario. It is not part of the productive thesis method. Its Java entry
points, focused tests and IntelliJ run configurations have been removed from
the active project structure, while the configuration, detailed methodology
and ignored outputs remain available as provenance.

## Purpose and configuration

The test examined whether people whose selected daily plan contains no closed
subtour could participate in endogenous mode choice. It compared the retained
`fromSpecifiedModesToSpecifiedModes` behavior with
`betweenAllAndFewerConstraints`, which exposes the unclosed root tour.

`scenarios/munich_calibration_2019/config_mode_choice_open_tour_test.xml`
differed from the initial technical calibration config in exactly four values:

| Parameter | Initial diagnostic | Open-Tour experiment |
|---|---|---|
| run ID | `munich-calibration-2019-initial` | `munich-calibration-2019-open-tour-test` |
| output directory | `output/mode-choice-initial` | `output/mode-choice-open-tour-test` |
| last iteration | 20 | 5 |
| subtour behavior | `fromSpecifiedModesToSpecifiedModes` | `betweenAllAndFewerConstraints` |

All offered modes, zero starting constants, inputs, scoring, capacity factors,
routing and output protection otherwise remained unchanged.

## Recorded result and stuck-event problem

The controller completed iterations 0 through 5 and shut down regularly. It
recorded 2,417 stuck events in iteration 0, 833 new stuck events in iteration 5
and 8,465 cumulatively. These counts describe events; they do not establish a
single cause.

The cohort diagnostic found the expected 107,618 identifiers for people with
open plans but reconstructed zero current trips. `ExperiencedPlansService`
did not provide a complete selected-plan snapshot, and the accompanying
general analysis covered only about 216,000 of 324,043 people. The reported
cohort mode changes and chain-location results therefore cannot validate the
alternative behavior.

## Reason for rejection

The alternative was rejected independently of the incomplete cohort
diagnostic. For chain-based car and bike, it relaxes the requirement that the
resource returns to its initial daily location, leaving its next-day position
undefined. The aggregate mode-share targets can be pursued without accepting
that interpretation. The future calibration will instead retain the full
regional population and cover all trips made by Munich residents; residence
will later be identified from the home activity. That classification is not
implemented by this cleanup.

The ignored Open-Tour output and generated diagnostics are preserved. The
detailed historical account remains in
`docs/methodology/mode_choice_open_tour_test.md`, and the shared stuck-event
audit remains available because it also examines the initial calibration
output.
