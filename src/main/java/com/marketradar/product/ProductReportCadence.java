package com.marketradar.product;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Canonical inclusive rolling windows used by every Product report surface. */
public enum ProductReportCadence {
    WEEKLY(7, "BẢN TIN 7 NGÀY", "7-DAY BRIEF"),
    MONTHLY(30, "BẢN TIN 30 NGÀY", "30-DAY BRIEF"),
    QUARTERLY(90, "BẢN TIN 90 NGÀY", "90-DAY BRIEF");

    private final int days;
    private final String labelVi;
    private final String labelEn;

    ProductReportCadence(int days, String labelVi, String labelEn) {
        this.days = days;
        this.labelVi = labelVi;
        this.labelEn = labelEn;
    }

    public int days() { return days; }
    public LocalDate start(LocalDate end) { return end.minusDays(days - 1L); }
    public String label(boolean vi) { return vi ? labelVi : labelEn; }

    /** Exact dates avoid presenting a rolling window as a calendar month/quarter. */
    public String periodLabel(LocalDate end, boolean vi) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern(
                vi ? "dd/MM/yyyy" : "MMM d, yyyy", vi ? Locale.forLanguageTag("vi") : Locale.ENGLISH);
        return start(end).format(f) + " – " + end.format(f);
    }

    public boolean matches(LocalDate windowStart, LocalDate windowEnd, LocalDate asOf) {
        return asOf != null && start(asOf).equals(windowStart) && asOf.equals(windowEnd);
    }

    public static ProductReportCadence parse(String value, ProductReportCadence fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
