# Isolated open-tour mode-choice test

## Purpose and status

This test is a narrowly bounded technical and methodological experiment for the
synthetic 2019 reference scenario. It asks whether persons whose daily plan has
no closed subtour can participate in MATSim's endogenous mode choice without
creating inconsistent car or bicycle resource sequences. It does not calibrate
mode constants, estimate a policy effect or replace the production calibration
configuration. The test has been prepared and locally validated, but no result
is reported before the server run has completed and its output has passed the
read-only validator.

The unchanged population preflight identified 107,618 persons without a closed
subtour. Their plans contain 37,417 main trips with both main-activity endpoints
inside or on the Munich municipal boundary. These trips account for 23.297821%
of the primary analysis sample and cannot be changed under the current
`fromSpecifiedModesToSpecifiedModes` behavior.

## Separate configuration

`config_mode_choice_open_tour_test.xml` is an exact copy of
`config_mode_choice_calibration.xml` except for four approved values:

| Parameter | Production calibration | Open-tour test |
|---|---|---|
| `controller.runId` | `munich-calibration-2019-initial` | `munich-calibration-2019-open-tour-test` |
| `controller.outputDirectory` | `output/mode-choice-initial` | `output/mode-choice-open-tour-test` |
| `controller.lastIteration` | 20 | 5 |
| `subtourModeChoice.behavior` | `fromSpecifiedModesToSpecifiedModes` | `betweenAllAndFewerConstraints` |

The config validator reverses these four textual substitutions and requires the
result to be byte-identical to the production calibration config. This makes an
unintended fifth difference a blocking error. In particular, the offered modes,
chain-based modes, zero mode constants, random seed, capacity factors, input
files, scoring, routing, transit, QSim and thread settings remain identical.
The distinct output directory uses `failIfDirectoryExists`; neither validator
nor runner deletes or reuses an existing directory.

## MATSim behavior and model interpretation

In MATSim 2025.0, `fromSpecifiedModesToSpecifiedModes` selects only closed
subtours. `betweenAllAndFewerConstraints` additionally exposes the unclosed
root subtour that covers the complete plan. Car and bike remain chain-based:
MATSim checks whether their resource is available at the start of a candidate
subtour. For the unclosed root only, it does not require the resource to return
to the first activity at the end of the represented day.

The diagnostic therefore distinguishes two conditions. A car or bicycle being
used again from a location where the preceding use did not leave it is a
resource jump and blocks acceptance. Ending an open daily plan with the
resource at a different final location is not classified as a technical error,
because this is the condition deliberately relaxed by the tested MATSim
behavior. It is quantified separately as a methodological effect: the model
does not establish where the resource starts on the following day.

The location-sequence audit follows MATSim's facility/link hierarchy at the
configured `coordDistance=0.0`. Missing runtime facility/link information is
reported as unverifiable and causes the output validation to stop. No
coordinate tolerance or additional behavioral rule is introduced in the
post-run audit.

## Server procedure and recorded evidence

The versioned IntelliJ sequence is:

1. **08 Validate 2019 Open Tour Mode Choice Test** performs the exact config
   comparison and output-protection checks without QSim.
2. **09 Run 2019 Open Tour Mode Choice Test** is the only entry point that
   starts MATSim. It runs iterations 0 through 5 with SwissRailRaptor and the
   unchanged zero mode constants.
3. **10 Validate 2019 Open Tour Mode Choice Test Output** reads the completed
   output and does not start MATSim or rewrite analysis files.

During step 09, the standard calibration listener records the experienced plan
after each mobsim and before subsequent replanning. A second aggregate listener
tracks only the originally open cohort and writes no person identifiers. It
records baseline and current mode counts, choice-set availability, changed mode
signatures, chain-resource jumps, unverifiable locations, end locations and
stuck events. A shutdown record distinguishes regular from unexpected
termination.

## Decision criteria

The alternative can be considered for a later production decision only if all
of the following are observed:

- iterations 0, 1, 2, 3, 4 and 5 each have one complete metric set;
- the final summary contains iteration 5 only and does not replace history;
- all 107,618 originally open persons have a non-empty MATSim choice set;
- at least one originally open person has a changed main-mode signature;
- there are no unknown modes, invalid distances, stuck events, exceptions or
  unexpected shutdowns;
- no car or bicycle resource jump is found and every chain check is spatially
  verifiable;
- car and bicycle endings away from the initial location are reported and
  judged substantively acceptable rather than hidden.

Passing these checks would establish short-run technical suitability, not
empirical calibration, convergence or causal effects. The mode constants remain
0.0, and five iterations are insufficient for a calibrated scenario comparison.
The production config must remain unchanged until the reported mode changes and
open-chain endpoint counts have been reviewed.
