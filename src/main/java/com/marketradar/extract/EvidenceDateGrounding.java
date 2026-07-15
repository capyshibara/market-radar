package com.marketradar.extract;

import java.time.LocalDate;
import java.time.Month;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts only dates explicitly present in the cited evidence span. */
public final class EvidenceDateGrounding {

    public enum Status { ABSENT, GROUNDED, INVALID_FORMAT, UNGROUNDED }
    public record Result(Status status, LocalDate date) {}

    private static final Pattern ISO = Pattern.compile("(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern DMY = Pattern.compile("(?<!\\d)(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})(?!\\d)");
    private static final Pattern VI = Pattern.compile(
            "(?iu)(?:ngày\\s+)?(\\d{1,2})\\s+tháng\\s+(\\d{1,2})\\s+năm\\s+(\\d{4})");
    private static final Pattern EN_DMY = Pattern.compile(
            "(?iu)(\\d{1,2})(?:st|nd|rd|th)?\\s+([a-z]+)\\s*,?\\s*(\\d{4})");
    private static final Pattern EN_MDY = Pattern.compile(
            "(?iu)([a-z]+)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\s*,\\s*(\\d{4})");
    private static final Map<String, Month> MONTHS = Map.ofEntries(
            Map.entry("january", Month.JANUARY), Map.entry("jan", Month.JANUARY),
            Map.entry("february", Month.FEBRUARY), Map.entry("feb", Month.FEBRUARY),
            Map.entry("march", Month.MARCH), Map.entry("mar", Month.MARCH),
            Map.entry("april", Month.APRIL), Map.entry("apr", Month.APRIL),
            Map.entry("may", Month.MAY), Map.entry("june", Month.JUNE), Map.entry("jun", Month.JUNE),
            Map.entry("july", Month.JULY), Map.entry("jul", Month.JULY),
            Map.entry("august", Month.AUGUST), Map.entry("aug", Month.AUGUST),
            Map.entry("september", Month.SEPTEMBER), Map.entry("sep", Month.SEPTEMBER),
            Map.entry("sept", Month.SEPTEMBER), Map.entry("october", Month.OCTOBER),
            Map.entry("oct", Month.OCTOBER), Map.entry("november", Month.NOVEMBER),
            Map.entry("nov", Month.NOVEMBER), Map.entry("december", Month.DECEMBER),
            Map.entry("dec", Month.DECEMBER));

    private EvidenceDateGrounding() {}

    public static Result parseAndGround(String modelValue, String evidenceSpan) {
        if (modelValue == null || modelValue.isBlank()) return new Result(Status.ABSENT, null);
        LocalDate date;
        try { date = LocalDate.parse(modelValue); }
        catch (Exception ignored) { return new Result(Status.INVALID_FORMAT, null); }
        return datesIn(evidenceSpan).contains(date)
                ? new Result(Status.GROUNDED, date)
                : new Result(Status.UNGROUNDED, null);
    }

    /** Effective/expiry fields influence lifecycle actions and must fail the fact closed. */
    public static boolean criticalFieldAcceptable(Result result) {
        return result != null && (result.status() == Status.ABSENT || result.status() == Status.GROUNDED);
    }

    public static Set<LocalDate> datesIn(String text) {
        Set<LocalDate> result = new HashSet<>();
        if (text == null || text.isBlank()) return result;
        addNumeric(result, ISO.matcher(text), true);
        addNumeric(result, DMY.matcher(text), false);
        addNumeric(result, VI.matcher(text), false);
        addEnglish(result, EN_DMY.matcher(text), false);
        addEnglish(result, EN_MDY.matcher(text), true);
        return Set.copyOf(result);
    }

    private static void addNumeric(Set<LocalDate> out, Matcher matcher, boolean yearFirst) {
        while (matcher.find()) {
            try {
                int year = Integer.parseInt(matcher.group(yearFirst ? 1 : 3));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(yearFirst ? 3 : 1));
                out.add(LocalDate.of(year, month, day));
            } catch (RuntimeException ignored) { /* invalid source date is not evidence */ }
        }
    }

    private static void addEnglish(Set<LocalDate> out, Matcher matcher, boolean monthFirst) {
        while (matcher.find()) {
            try {
                String monthText = matcher.group(monthFirst ? 1 : 2).toLowerCase(Locale.ROOT);
                Month month = MONTHS.get(monthText);
                if (month == null) continue;
                int day = Integer.parseInt(matcher.group(monthFirst ? 2 : 1));
                int year = Integer.parseInt(matcher.group(3));
                out.add(LocalDate.of(year, month, day));
            } catch (RuntimeException ignored) { /* invalid source date is not evidence */ }
        }
    }
}
