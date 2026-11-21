package ai.gradpath.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.gradpath.domain.ReadinessAssessment;
import ai.gradpath.readiness.FactorResult;
import ai.gradpath.readiness.ReadinessCategory;
import ai.gradpath.service.ReportService.DepartmentRow;
import ai.gradpath.service.ReportService.GapRow;
import ai.gradpath.service.ReportService.StudentRow;

/**
 * Builds Plotly.js chart specifications ({data, layout}) on the server. Templates embed them as escaped JSON data
 * attributes and frontend/static/js/charts.js renders them with the shared light theme.
 */
public final class ChartFactory {

    public static final String INDIGO = "#6366F1";
    public static final String VIOLET = "#8B5CF6";
    public static final String SKY = "#38BDF8";
    public static final String EMERALD = "#10B981";
    public static final String AMBER = "#F59E0B";
    public static final String RED = "#F87171";
    public static final String SLATE = "#64748B";

    private ChartFactory() { }

    public static String categoryColor(String label) {
        return switch (ReadinessCategory.fromLabel(label)) {
            case JOB_READY -> EMERALD;
            case DEVELOPING -> SKY;
            case NEEDS_IMPROVEMENT -> AMBER;
        };
    }

    private static Map<String, Object> spec(List<Map<String, Object>> data, Map<String, Object> layout) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", data);
        m.put("layout", layout);
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    /** Readiness category donut. */
    public static Map<String, Object> readinessDonut(Map<String, Long> counts, String title) {
        List<String> labels = new ArrayList<>(counts.keySet());
        return spec(List.of(map("type", "pie", "hole", 0.62, "labels", labels, "values", new ArrayList<>(counts.values()),
                        "marker", map("colors", labels.stream().map(ChartFactory::categoryColor).toList()),
                        "sort", false, "textinfo", "percent", "hovertemplate", "%{label}: %{value} student(s)<extra></extra>")),
                map("title", title, "height", 320, "showlegend", true));
    }

    /** Gauge for one readiness score. */
    public static Map<String, Object> gauge(double score, ReadinessCategory category) {
        return spec(List.of(map("type", "indicator", "mode", "gauge+number", "value", score,
                        "number", map("suffix", " / 100", "font", map("size", 34)),
                        "gauge", map("axis", map("range", List.of(0, 100), "tickvals", List.of(0, 40, 70, 100)),
                                "bar", map("color", categoryColor(category.label())),
                                "bgcolor", "#E5EBF8", "borderwidth", 0,
                                "steps", List.of(map("range", List.of(0, 40), "color", "rgba(245,158,11,.12)"),
                                        map("range", List.of(40, 70), "color", "rgba(56,189,248,.12)"),
                                        map("range", List.of(70, 100), "color", "rgba(16,185,129,.12)"))))),
                map("height", 260, "margin", map("l", 30, "r", 30, "t", 30, "b", 10)));
    }

    /** Points earned vs maximum for each factor. */
    public static Map<String, Object> factorBars(List<FactorResult> factors) {
        List<String> names = factors.stream().map(FactorResult::name).toList();
        return spec(List.of(
                map("type", "bar", "orientation", "h", "name", "Maximum", "y", names,
                        "x", factors.stream().map(FactorResult::maxPoints).toList(),
                        "marker", map("color", "#DCE3F3", "line", map("color", "#C9D3EA", "width", 1)),
                        "hovertemplate", "%{y}: max %{x} points<extra></extra>"),
                map("type", "bar", "orientation", "h", "name", "Earned", "y", names,
                        "x", factors.stream().map(FactorResult::points).toList(),
                        "text", factors.stream().map(f -> f.points() + " / " + f.maxPoints()).toList(),
                        "textposition", "outside", "cliponaxis", false,
                        "marker", map("color", INDIGO), "hovertemplate", "%{y}: %{x} points<extra></extra>")),
                map("title", "Points by factor", "barmode", "overlay", "height", 360,
                        "xaxis", map("title", "Points", "range", List.of(0, 34)),
                        "yaxis", map("autorange", "reversed"), "margin", map("l", 170, "r", 40, "t", 50, "b", 50)));
    }

    /** Saved readiness scores over time. */
    public static Map<String, Object> history(List<ReadinessAssessment> rows) {
        return spec(List.of(map("type", "scatter", "mode", "lines+markers",
                        "x", rows.stream().map(r -> r.getCreatedAt().toString()).toList(),
                        "y", rows.stream().map(ReadinessAssessment::getScore).toList(),
                        "line", map("color", SKY, "width", 3), "hovertemplate", "%{x|%d %b %Y}: %{y}<extra></extra>")),
                map("title", "Your readiness score over time", "height", 280,
                        "yaxis", map("range", List.of(0, 100), "title", "Score")));
    }

    public static Map<String, Object> readinessBySource(List<StudentRow> rows) {
        List<String> cats = ReadinessCategory.labels();
        List<Map<String, Object>> data = new ArrayList<>();
        for (String[] src : new String[][] {{"Registered", INDIGO}, {"Demo (fictional)", SKY}}) {
            data.add(map("type", "bar", "name", src[0], "x", cats,
                    "y", cats.stream().map(c -> rows.stream().filter(r -> r.source().equals(src[0]) && r.category().equals(c)).count()).toList(),
                    "marker", map("color", src[1])));
        }
        return spec(data, map("title", "Readiness by data source", "barmode", "stack", "height", 320,
                "yaxis", map("title", "Students")));
    }

    public static Map<String, Object> departmentBar(List<DepartmentRow> depts) {
        return spec(List.of(map("type", "bar", "x", depts.stream().map(DepartmentRow::department).toList(),
                        "y", depts.stream().map(DepartmentRow::students).toList(),
                        "marker", map("color", depts.stream().map(DepartmentRow::averageScore).toList(),
                                "colorscale", List.of(List.of(0, "#312E81"), List.of(0.5, INDIGO), List.of(1, SKY)),
                                "cmin", 0, "cmax", 100, "showscale", true,
                                "colorbar", map("title", map("text", "Avg score"))),
                        "hovertemplate", "%{x}: %{y} student(s)<extra></extra>")),
                map("title", "Students per department (colour = average readiness)", "height", 340,
                        "yaxis", map("title", "Students")));
    }

    public static Map<String, Object> cgpaHistogram(List<StudentRow> rows) {
        return spec(List.of(map("type", "histogram", "x", rows.stream().map(StudentRow::cgpa4).toList(),
                        "xbins", map("start", 0, "end", 4.0001, "size", 0.25), "marker", map("color", VIOLET))),
                map("title", "CGPA distribution (4.0-equivalent)", "height", 320, "bargap", 0.08,
                        "xaxis", map("title", "CGPA", "range", List.of(0, 4.05)), "yaxis", map("title", "Students")));
    }

    public static Map<String, Object> internshipPie(List<StudentRow> rows) {
        long with = rows.stream().filter(r -> r.internships() > 0).count();
        return spec(List.of(map("type", "pie", "hole", 0.55, "labels", List.of("Has internship", "No internship"),
                        "values", List.of(with, rows.size() - with), "sort", false,
                        "marker", map("colors", List.of(EMERALD, SLATE)))),
                map("title", "Internship participation", "height", 320));
    }

    public static Map<String, Object> skillGaps(List<GapRow> gaps) {
        List<GapRow> asc = new ArrayList<>(gaps);
        java.util.Collections.reverse(asc);
        return spec(List.of(map("type", "bar", "orientation", "h", "y", asc.stream().map(GapRow::skill).toList(),
                        "x", asc.stream().map(GapRow::students).toList(), "marker", map("color", AMBER))),
                map("title", "Most common skill gaps", "height", 360, "xaxis", map("title", "Students with gap"),
                        "margin", map("l", 140, "r", 20, "t", 50, "b", 50)));
    }

    public static Map<String, Object> horizontalCounts(Map<String, Long> counts, String title, String color, String xTitle) {
        List<String> keys = new ArrayList<>(counts.keySet());
        java.util.Collections.reverse(keys);
        return spec(List.of(map("type", "bar", "orientation", "h", "y", keys,
                        "x", keys.stream().map(counts::get).toList(), "marker", map("color", color))),
                map("title", title, "height", Math.max(300, 46 * keys.size() + 110), "xaxis", map("title", xTitle),
                        "margin", map("l", 200, "r", 20, "t", 50, "b", 50)));
    }

    public static Map<String, Object> statusBar(Map<String, Long> counts) {
        List<String> colors = List.of(SLATE, SKY, VIOLET, INDIGO, EMERALD, RED);
        return spec(List.of(map("type", "bar", "x", new ArrayList<>(counts.keySet()), "y", new ArrayList<>(counts.values()),
                        "marker", map("color", colors.subList(0, Math.min(colors.size(), counts.size()))))),
                map("title", "Applications by status", "height", 320, "yaxis", map("title", "Applications")));
    }

    public static Map<String, Object> jobActivity(Map<String, long[]> byCategory) {
        List<String> cats = new ArrayList<>(byCategory.keySet());
        return spec(List.of(
                map("type", "bar", "name", "Open", "x", cats, "y", cats.stream().map(c -> byCategory.get(c)[0]).toList(),
                        "marker", map("color", EMERALD)),
                map("type", "bar", "name", "Closed", "x", cats, "y", cats.stream().map(c -> byCategory.get(c)[1]).toList(),
                        "marker", map("color", SLATE))),
                map("title", "Listings by career category", "barmode", "stack", "height", 320, "yaxis", map("title", "Listings")));
    }

    /** Illustrative donut for the public landing page (sample numbers, clearly labelled). */
    public static Map<String, Object> landingSample() {
        Map<String, Long> sample = new LinkedHashMap<>();
        sample.put("Needs Improvement", 5L);
        sample.put("Developing", 11L);
        sample.put("Job Ready", 8L);
        return readinessDonut(sample, "Sample readiness mix (illustrative)");
    }

    /** ROC curve on the held-out test rows, with the chance diagonal. */
    public static Map<String, Object> rocCurve(ai.gradpath.ml.PlacementModel m) {
        List<double[]> pts = m.test().roc();
        return spec(List.of(
                        map("type", "scatter", "mode", "lines", "name", "Chance", "x", List.of(0, 1), "y", List.of(0, 1),
                                "line", map("color", SLATE, "dash", "dash"), "hoverinfo", "skip"),
                        map("type", "scatter", "mode", "lines", "name", "Model",
                                "x", pts.stream().map(p -> p[0]).toList(), "y", pts.stream().map(p -> p[1]).toList(),
                                "line", map("color", INDIGO, "width", 3), "fill", "tozeroy", "fillcolor", "rgba(99,102,241,.12)",
                                "hovertemplate", "False-positive rate %{x:.2f}<br>True-positive rate %{y:.2f}<extra></extra>")),
                map("title", "ROC curve (test data, AUC " + m.test().rocAuc() + ")", "height", 340, "showlegend", false,
                        "xaxis", map("title", "False-positive rate", "range", List.of(0, 1)),
                        "yaxis", map("title", "True-positive rate", "range", List.of(0, 1.02))));
    }

    /** Odds ratio per standard deviation for each feature. */
    public static Map<String, Object> coefficients(ai.gradpath.ml.PlacementModel m) {
        List<String> names = ai.gradpath.ml.PlacementFeatures.LABELS;
        List<Double> ors = new ArrayList<>();
        for (int j = 0; j < names.size(); j++) {
            ors.add(Math.round(m.oddsRatio(j) * 1000) / 1000.0);
        }
        return spec(List.of(map("type", "bar", "orientation", "h", "y", names, "x", ors,
                        "base", 0, "text", ors.stream().map(v -> "x" + v).toList(), "textposition", "outside", "cliponaxis", false,
                        "marker", map("color", ors.stream().map(v -> v >= 1 ? EMERALD : RED).toList()),
                        "hovertemplate", "%{y}: odds x%{x} per standard deviation<extra></extra>")),
                map("title", "Effect of +1 standard deviation on the odds of placement", "height", 320,
                        "xaxis", map("title", "Odds ratio (1 = no effect)"), "yaxis", map("autorange", "reversed"),
                        "shapes", List.of(map("type", "line", "x0", 1, "x1", 1, "y0", -0.5, "y1", names.size() - 0.5,
                                "line", map("color", SLATE, "dash", "dot"))),
                        "margin", map("l", 130, "r", 50, "t", 50, "b", 50)));
    }

    /** Normalized impurity-based feature importance for decision-tree models. */
    public static Map<String, Object> featureImportance(ai.gradpath.ml.PlacementModel m) {
        List<String> names = ai.gradpath.ml.PlacementFeatures.LABELS;
        List<Double> values = new ArrayList<>();
        for (double value : m.featureImportance()) {
            values.add(Math.round(value * 1000) / 1000.0);
        }
        return spec(List.of(map("type", "bar", "orientation", "h", "y", names, "x", values,
                        "base", 0, "text", values.stream().map(v -> String.format(java.util.Locale.ROOT, "%.3f", v)).toList(),
                        "textposition", "outside", "cliponaxis", false, "marker", map("color", INDIGO),
                        "hovertemplate", "%{y}: normalized importance %{x:.3f}<extra></extra>")),
                map("title", "Tree-based feature importance", "height", 320,
                        "xaxis", map("title", "Normalized impurity decrease"), "yaxis", map("autorange", "reversed"),
                        "margin", map("l", 130, "r", 50, "t", 50, "b", 50)));
    }

    /** Reliability diagram: predicted probability vs observed placement rate per bin. */
    public static Map<String, Object> calibration(ai.gradpath.ml.PlacementModel m) {
        List<double[]> bins = m.test().calibration();
        return spec(List.of(
                        map("type", "scatter", "mode", "lines", "name", "Perfect", "x", List.of(0, 1), "y", List.of(0, 1),
                                "line", map("color", SLATE, "dash", "dash"), "hoverinfo", "skip"),
                        map("type", "scatter", "mode", "lines+markers", "name", "Model",
                                "x", bins.stream().map(b -> b[0]).toList(), "y", bins.stream().map(b -> b[1]).toList(),
                                "text", bins.stream().map(b -> (int) b[2] + " students").toList(),
                                "marker", map("size", 9, "color", SKY), "line", map("color", SKY, "width", 2),
                                "hovertemplate", "Predicted %{x:.0%}<br>Observed %{y:.0%}<br>%{text}<extra></extra>")),
                map("title", "Calibration (test data)", "height", 340, "showlegend", false,
                        "xaxis", map("title", "Predicted probability", "range", List.of(0, 1), "tickformat", ".0%"),
                        "yaxis", map("title", "Observed placement rate", "range", List.of(0, 1.02), "tickformat", ".0%")));
    }

    /** One student's feature contributions (log-odds vs an average training profile). */
    public static Map<String, Object> contributions(List<ai.gradpath.ml.PlacementModel.Contribution> cs) {
        List<Double> v = cs.stream().map(c -> Math.round(c.logOdds() * 100) / 100.0).toList();
        return spec(List.of(map("type", "bar", "orientation", "h", "y", cs.stream().map(c -> c.label()).toList(), "x", v,
                        "marker", map("color", v.stream().map(x -> x >= 0 ? EMERALD : AMBER).toList()),
                        "hovertemplate", "%{y}: %{x:+.2f} log-odds<extra></extra>")),
                map("title", "What moves your estimate", "height", 280, "yaxis", map("autorange", "reversed"),
                        "xaxis", map("title", "Effect (log-odds; right = raises, left = lowers)", "zeroline", true),
                        "margin", map("l", 130, "r", 30, "t", 50, "b", 50)));
    }
}
