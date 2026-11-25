package com.greencloud.ui;

/**
 * Immutable record representing a summary metric row loaded from results/summary.csv.
 */
public record SummaryMetric(
        String metric,
        String value
) {}
