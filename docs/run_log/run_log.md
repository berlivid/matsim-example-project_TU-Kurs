# MATSim Run Log

## 2026-07-15 – Day 1 Setup

### Repository
- Forked: yes
- Cloned locally: yes
- IntelliJ configured: yes

### Build
- Command: mvnw.cmd clean package
- Result:
- Java version:
- MATSim version from pom.xml:

### Example Run
- Config: scenarios/equil/config.xml
- Started via: MATSimGUI / RunMATSim
- Output folder:
- Warnings/errors:

## Day 2 – Munich reference run

Date:
17.07.2026

### Scenario:
scenarios/munich_base_2023/configBase.xml

### Input:
- munich-v1.0-network.xml.gz
- munich-v1.0-5pct.plans.xml.gz
- Inputs loaded from public MATSim Munich SVN repository

### Run settings:
- lastIteration = 0
- population sample = 5 %
- flowCapacityFactor = 0.05
- storageCapacityFactor = 0.05
- Java heap = approximately 4 GB
- Java version = 21

### Result:
- Network loaded successfully
- 324,043 persons loaded successfully
- Iteration 0 completed
- Process finished with exit code 0
- Output files successfully generated
- No fatal errors or OutOfMemoryError
- Run was slow because available heap was nearly fully used

### Important limitations:
- Current model is a technical Munich reference scenario
- It is not yet a validated 2023 baseline
- Public transport is not explicitly simulated
- No transit schedule or transit vehicles are included
- The data year and calibration status of the Munich reference model still need to be clarified