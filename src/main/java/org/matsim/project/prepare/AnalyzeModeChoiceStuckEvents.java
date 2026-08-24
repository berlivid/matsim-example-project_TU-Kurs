package org.matsim.project.prepare;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

/** Read-only audit of existing calibration PersonStuckEvent files. */
public final class AnalyzeModeChoiceStuckEvents {
    static final Path TARGET = Path.of("generated/mode_choice_stuck_event_audit");
    static final List<OutputSpec> OUTPUTS = List.of(
            new OutputSpec("initial", Path.of("scenarios/munich_calibration_2019/output/mode-choice-initial"), 20),
            new OutputSpec("open_tour_test", Path.of("scenarios/munich_calibration_2019/output/mode-choice-open-tour-test"), 5));
    private static final Pattern ITERATION_DIRECTORY = Pattern.compile("it\\.(\\d+)");
    private static final Pattern NUMBERED_EVENTS = Pattern.compile(".*\\.(\\d+)\\.events\\.xml(?:\\.gz)?$");

    private AnalyzeModeChoiceStuckEvents() { }

    public static void main(String[] args) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(args.length == 0,
                "This read-only auditor accepts no arguments");
        audit(OUTPUTS, TARGET);
    }

    static void audit(List<OutputSpec> outputs, Path target) throws Exception {
        ValidateModeChoiceCalibrationConfig.require(!Files.exists(target),
                "Audit target already exists; nothing was overwritten: " + target);
        List<IterationAudit> audits = new ArrayList<>();
        for (OutputSpec output : outputs) {
            ValidateModeChoiceCalibrationConfig.require(Files.isDirectory(output.path()),
                    "Calibration output is missing: " + output.path());
            Map<Integer, Path> files = findEventFiles(output);
            for (int iteration = 0; iteration <= output.lastIteration(); iteration++) {
                Path file = files.get(iteration);
                audits.add(file == null ? IterationAudit.missing(output.name(), iteration)
                        : read(output.name(), iteration, file));
            }
        }
        Files.createDirectories(target);
        write(target.resolve("stuck_events_by_iteration.csv"), byIteration(audits));
        write(target.resolve("stuck_events_by_mode.csv"), byMode(audits));
        write(target.resolve("stuck_events_by_time.csv"), byTime(audits));
        write(target.resolve("stuck_events_by_cohort.csv"), byCohort(audits));
        write(target.resolve("stuck_event_audit_report.md"), report(audits));
    }

    static Map<Integer, Path> findEventFiles(OutputSpec output) throws IOException {
        Map<Integer, List<Candidate>> candidates = new HashMap<>();
        try (var paths = Files.walk(output.path())) {
            paths.filter(Files::isRegularFile).filter(AnalyzeModeChoiceStuckEvents::isEventsFile)
                    .forEach(path -> {
                        Candidate candidate = candidate(path, output);
                        if (candidate != null) candidates.computeIfAbsent(candidate.iteration,
                                ignored -> new ArrayList<>()).add(candidate);
                    });
        }
        TreeMap<Integer, Path> selected = new TreeMap<>();
        for (var entry : candidates.entrySet()) {
            List<Candidate> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(Candidate::priority)
                            .thenComparing(candidate -> candidate.path.toString())).toList();
            Candidate best = ordered.getFirst();
            long samePriority = ordered.stream().filter(value -> value.priority == best.priority).count();
            ValidateModeChoiceCalibrationConfig.require(samePriority == 1,
                    "Ambiguous event files for iteration " + entry.getKey() + ": " + ordered);
            selected.put(entry.getKey(), best.path);
        }
        return selected;
    }

    private static Candidate candidate(Path path, OutputSpec output) {
        for (Path part : path) {
            Matcher matcher = ITERATION_DIRECTORY.matcher(part.toString());
            if (matcher.matches()) return new Candidate(Integer.parseInt(matcher.group(1)), path, 0);
        }
        Matcher numbered = NUMBERED_EVENTS.matcher(path.getFileName().toString());
        if (numbered.matches()) return new Candidate(Integer.parseInt(numbered.group(1)), path, 1);
        if (path.getFileName().toString().contains("output_events")) {
            return new Candidate(output.lastIteration(), path, 2);
        }
        return null;
    }

    private static boolean isEventsFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".events.xml") || name.endsWith(".events.xml.gz")
                || name.endsWith(".output_events.xml") || name.endsWith(".output_events.xml.gz");
    }

    private static IterationAudit read(String output, int iteration, Path file) {
        Collector collector = new Collector();
        EventsManager manager = EventsUtils.createEventsManager();
        manager.addHandler(collector);
        new MatsimEventsReader(manager).readFile(file.toString());
        return collector.finish(output, iteration, file);
    }

    private static String byIteration(List<IterationAudit> audits) {
        StringBuilder csv = new StringBuilder("output,iteration,event_file_available,event_file,event_count,unique_persons,min_event_time_seconds,max_event_time_seconds\n");
        audits.forEach(audit -> csv.append(audit.output).append(',').append(audit.iteration)
                .append(',').append(audit.available).append(',').append(value(audit.file)).append(',')
                .append(audit.events.size()).append(',').append(unique(audit.events)).append(',')
                .append(number(minTime(audit.events))).append(',').append(number(maxTime(audit.events))).append('\n'));
        return csv.toString();
    }

    private static String byMode(List<IterationAudit> audits) {
        StringBuilder csv = new StringBuilder("output,iteration,leg_mode,event_count,unique_persons\n");
        for (IterationAudit audit : audits) group(audit.events, EventRecord::mode).forEach((mode, records) ->
                csv.append(audit.output).append(',').append(audit.iteration).append(',')
                        .append(value(mode)).append(',').append(records.size()).append(',')
                        .append(unique(records)).append('\n'));
        return csv.toString();
    }

    private static String byTime(List<IterationAudit> audits) {
        StringBuilder csv = new StringBuilder("output,iteration,time_window,event_count,unique_persons,min_event_time_seconds,max_event_time_seconds\n");
        for (IterationAudit audit : audits) group(audit.events,
                record -> hourWindow(record.time)).forEach((window, records) -> csv
                        .append(audit.output).append(',').append(audit.iteration).append(',')
                        .append(window).append(',').append(records.size()).append(',')
                        .append(unique(records)).append(',').append(number(minTime(records)))
                        .append(',').append(number(maxTime(records))).append('\n'));
        return csv.toString();
    }

    private static String byCohort(List<IterationAudit> audits) {
        StringBuilder csv = new StringBuilder("output,iteration,cohort,event_count,unique_persons,note\n");
        audits.forEach(audit -> csv.append(audit.output).append(',').append(audit.iteration)
                .append(",ALL_PERSONS,").append(audit.events.size()).append(',')
                .append(unique(audit.events)).append(',')
                .append("open-cohort membership not inferred from events alone\n"));
        return csv.toString();
    }

    private static String report(List<IterationAudit> audits) {
        long available = audits.stream().filter(audit -> audit.available).count();
        long events = audits.stream().mapToLong(audit -> audit.events.size()).sum();
        TreeMap<String, Long> links = new TreeMap<>();
        audits.forEach(audit -> audit.events.forEach(record -> links.merge(record.link, 1L, Long::sum)));
        StringBuilder report = new StringBuilder("# Stuck-event audit\n\n")
                .append("This read-only audit uses only available `PersonStuckEvent` records. It reports counts, persons, modes, event times and links descriptively; it does not infer causes. Root output-event files are used only when no iteration-specific file exists for the same iteration.\n\n")
                .append("Available iteration event files: ").append(available).append(" of ")
                .append(audits.size()).append("; events: ").append(events).append(".\n\n")
                .append("Open-cohort membership is not inferred from event data alone and is therefore marked unavailable rather than guessed.\n\n")
                .append("## Most frequent links\n\n| Link | Events |\n|---|---:|\n");
        links.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).limit(20)
                .forEach(entry -> report.append("| ").append(entry.getKey()).append(" | ")
                        .append(entry.getValue()).append(" |\n"));
        return report.toString();
    }

    private static <K> Map<K, List<EventRecord>> group(List<EventRecord> records,
            java.util.function.Function<EventRecord, K> classifier) {
        Map<K, List<EventRecord>> result = new TreeMap<>();
        records.forEach(record -> result.computeIfAbsent(classifier.apply(record),
                ignored -> new ArrayList<>()).add(record));
        return result;
    }

    private static long unique(List<EventRecord> records) {
        return records.stream().map(EventRecord::person).distinct().count();
    }
    private static double minTime(List<EventRecord> records) { return records.stream().mapToDouble(EventRecord::time).min().orElse(Double.NaN); }
    private static double maxTime(List<EventRecord> records) { return records.stream().mapToDouble(EventRecord::time).max().orElse(Double.NaN); }
    private static String hourWindow(double seconds) { return String.format(Locale.ROOT, "%02d:00-%02d:00", (int)(seconds / 3600), (int)(seconds / 3600) + 1); }
    private static String number(double value) { return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : ""; }
    private static String value(Object value) { String text = value == null ? "" : value.toString(); return text.contains(",") ? '"' + text.replace("\"", "\"\"") + '"' : text; }
    private static void write(Path target, String content) throws IOException { Files.writeString(target, content, StandardCharsets.UTF_8); }

    static record OutputSpec(String name, Path path, int lastIteration) { }
    private record Candidate(int iteration, Path path, int priority) { }
    private record EventRecord(String person, String mode, String link, double time) { }
    private record IterationAudit(String output, int iteration, boolean available, String file, List<EventRecord> events) {
        static IterationAudit missing(String output, int iteration) { return new IterationAudit(output, iteration, false, "", List.of()); }
    }
    private static final class Collector implements PersonStuckEventHandler {
        final List<EventRecord> records = new ArrayList<>();
        @Override public void handleEvent(PersonStuckEvent event) {
            records.add(new EventRecord(event.getPersonId().toString(), event.getLegMode() == null ? "unknown" : event.getLegMode(), event.getLinkId() == null ? "" : event.getLinkId().toString(), event.getTime()));
        }
        IterationAudit finish(String output, int iteration, Path file) {
            records.sort(Comparator.comparingDouble(EventRecord::time).thenComparing(EventRecord::person));
            return new IterationAudit(output, iteration, true, file.toString(), List.copyOf(records));
        }
    }
}
