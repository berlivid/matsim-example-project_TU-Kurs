package org.matsim.project.prepare;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;

/**
 * Fail-closed semantic comparison of a prepared iteration-0 config and MATSim's
 * serialized output config.
 *
 * <p>MATSim may reorder parameter sets and the approved SwissRailRaptor module
 * installs its explicit defaults before writing the output config. Everything
 * else, including every value inside a semantically identified parameter set,
 * remains strict.</p>
 */
final class ResidentOutputConfigSemanticComparison {
    private static final String SWISS_RAIL_RAPTOR = "swissRailRaptor";
    private static final Map<String, List<String>> SET_IDENTITIES = Map.of(
            "strategysettings", List.of("subpopulation", "strategyName"),
            "modeParams", List.of("mode"),
            "activityParams", List.of("activityType"),
            "teleportedModeParameters", List.of("mode"),
            "scoringParameters", List.of("subpopulation"));

    private ResidentOutputConfigSemanticComparison() { }

    static Result compare(Config expectedBeforeController, Config actualOutput) {
        Snapshot expected = snapshot(expectedBeforeController, true);
        Snapshot actual = snapshot(actualOutput, false);
        List<Difference> differences = new ArrayList<>();

        expected.issues().forEach((key, value) -> differences.add(new Difference(
                "expected-config/" + key, "valid supported parameter set", value)));
        actual.issues().forEach((key, value) -> differences.add(new Difference(
                key, "one supported, uniquely identified parameter set", value)));

        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(expected.values().keySet());
        keys.addAll(actual.values().keySet());
        for (String key : keys) {
            String expectedValue = expected.values().get(key);
            String actualValue = actual.values().get(key);
            if (!equivalent(key, expectedValue, actualValue)) {
                differences.add(new Difference(key, printable(expectedValue),
                        printable(actualValue)));
            }
        }
        return new Result(List.copyOf(differences));
    }

    static void requireEquivalent(Config expectedBeforeController, Config actualOutput) {
        Result result = compare(expectedBeforeController, actualOutput);
        if (!result.differences().isEmpty()) {
            throw new IllegalStateException(result.failureMessage());
        }
    }

    private static Snapshot snapshot(Config config, boolean addRuntimeDefaults) {
        TreeMap<String, String> values = new TreeMap<>();
        TreeMap<String, String> issues = new TreeMap<>();
        config.getModules().forEach((name, group) -> flatten(
                "module[" + name + "]", group, values, issues));
        if (addRuntimeDefaults && !config.getModules().containsKey(SWISS_RAIL_RAPTOR)) {
            flatten("module[" + SWISS_RAIL_RAPTOR + "]",
                    new SwissRailRaptorConfigGroup(), values, issues);
        }
        return new Snapshot(Map.copyOf(values), Map.copyOf(issues));
    }

    private static void flatten(String prefix, ConfigGroup group,
                                Map<String, String> values,
                                Map<String, String> issues) {
        values.put(prefix + "/#present", "true");
        group.getParams().forEach((name, value) ->
                values.put(prefix + "/@" + name, value));

        TreeMap<String, Collection<? extends ConfigGroup>> sets =
                new TreeMap<>(group.getParameterSets());
        for (var entry : sets.entrySet()) {
            String type = entry.getKey();
            Set<String> seen = new TreeSet<>();
            for (ConfigGroup parameterSet : entry.getValue()) {
                String identity = identity(type, parameterSet);
                if (identity == null) {
                    String issueKey = prefix + "/set[" + type + "]{unsupported}"
                            + stableDescription(parameterSet);
                    issues.put(issueKey, "unsupported parameter-set type without a "
                            + "documented semantic identity: " + type);
                    continue;
                }
                String childPrefix = prefix + "/set[" + type + "]{" + identity + "}";
                if (!seen.add(identity)) {
                    issues.put(childPrefix + "/#duplicate",
                            "duplicated semantic identity " + identity);
                    continue;
                }
                flatten(childPrefix, parameterSet, values, issues);
            }
        }
    }

    private static String identity(String type, ConfigGroup parameterSet) {
        List<String> identityFields = SET_IDENTITIES.get(type);
        if (identityFields == null) return null;
        Map<String, String> params = parameterSet.getParams();
        if (!params.keySet().containsAll(identityFields)) return null;
        return identityFields.stream()
                .map(field -> field + "=" + printable(params.get(field)))
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    private static String stableDescription(ConfigGroup group) {
        return new TreeMap<>(group.getParams()).toString();
    }

    private static boolean equivalent(String key, String expected, String actual) {
        if (Objects.equals(expected, actual)) return true;
        if (expected == null || actual == null) return false;
        return isPathParameter(key)
                && normalizePath(expected).equals(normalizePath(actual));
    }

    private static boolean isPathParameter(String key) {
        int marker = key.lastIndexOf("/@");
        if (marker < 0) return false;
        String parameter = key.substring(marker + 2);
        return parameter.endsWith("File") || parameter.endsWith("Directory");
    }

    private static String normalizePath(String value) {
        return value.replace('\\', '/');
    }

    private static String printable(String value) {
        return value == null ? "<missing>" : value;
    }

    record Difference(String key, String expected, String actual) { }

    record Result(List<Difference> differences) {
        String failureMessage() {
            StringBuilder message = new StringBuilder(
                    "Output config has unexpected semantic differences:");
            for (Difference difference : differences) {
                message.append(System.lineSeparator()).append(" - ")
                        .append(difference.key()).append(": expected=")
                        .append(difference.expected()).append(", actual=")
                        .append(difference.actual());
            }
            return message.toString();
        }
    }

    private record Snapshot(Map<String, String> values,
                            Map<String, String> issues) { }
}
