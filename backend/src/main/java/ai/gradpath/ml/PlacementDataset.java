package ai.gradpath.ml;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Labelled training data: one feature row and a placed (1) / not placed (0) label per student.
 *
 * <p>{@link #load(Path)} reads a placement CSV (for example a Kaggle "college placement" dataset) and accepts the
 * common column-name aliases. Only the five model features and the label are read - any other column, including
 * sensitive ones such as IQ, is ignored. {@link #synthetic(int, long)} creates a clearly labelled practice dataset.
 */
public record PlacementDataset(List<double[]> rows, int[] labels, String source, boolean synthetic) {

    /** Accepted CSV header names for each logical column (compared lower-case with spaces as underscores). */
    static final Map<String, List<String>> ALIASES = Map.of(
            "cgpa", List.of("cgpa", "gpa"),
            "internship", List.of("internship_experience", "internships", "internship"),
            "projects", List.of("projects_completed", "projects", "project_count"),
            "communication", List.of("communication_skills", "communication"),
            "extracurricular", List.of("extra_curricular_score", "extracurricular_score", "extra_curricular", "extracurricular"),
            "target", List.of("placement", "placed", "status", "placementstatus", "placement_status"));

    /** Columns are looked up in this fixed order, so a missing-column error is always reported the same way. */
    static final List<String> COLUMN_ORDER = List.of("cgpa", "projects", "internship", "communication", "extracurricular", "target");

    public PlacementDataset {
        if (rows.size() != labels.length) {
            throw new IllegalArgumentException("Rows and labels differ in length");
        }
        rows = List.copyOf(rows);
    }

    public int size() {
        return labels.length;
    }

    public double placementRate() {
        int placed = 0;
        for (int y : labels) {
            placed += y;
        }
        return size() == 0 ? 0 : (double) placed / size();
    }

    /** Load a placement CSV. Rows with a missing or unreadable value are skipped. */
    public static PlacementDataset load(Path csv) throws IOException {
        return load(csv, null);
    }

    /**
     * Load a placement CSV using an explicitly supplied grading scale, avoiding inference from held-out feature rows.
     */
    public static PlacementDataset load(Path csv, double cgpaScale) throws IOException {
        if (cgpaScale != 4.0 && cgpaScale != 5.0 && cgpaScale != 10.0) {
            throw new IOException("CGPA scale must be 4, 5 or 10");
        }
        return load(csv, Double.valueOf(cgpaScale));
    }

    private static PlacementDataset load(Path csv, Double explicitCgpaScale) throws IOException {
        try (BufferedReader in = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = in.readLine();
            if (headerLine == null) {
                throw new IOException("The CSV file is empty");
            }
            List<String> header = splitCsv(stripBom(headerLine));
            Map<String, Integer> col = new HashMap<>();
            for (String key : COLUMN_ORDER) {
                col.put(key, find(header, key));
            }
            List<double[]> raw = new ArrayList<>();
            List<Integer> ys = new ArrayList<>();
            double maxCgpa = 0;
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> cells = splitCsv(line);
                try {
                    double cgpa = number(cells, col.get("cgpa"));
                    double projects = number(cells, col.get("projects"));
                    Integer internship = yesNo(cell(cells, col.get("internship")));
                    double communication = number(cells, col.get("communication"));
                    double extra = number(cells, col.get("extracurricular"));
                    Integer target = yesNo(cell(cells, col.get("target")));
                    if (internship == null || target == null || Double.isNaN(cgpa) || Double.isNaN(projects)
                            || Double.isNaN(communication) || Double.isNaN(extra)) {
                        continue;
                    }
                    raw.add(new double[] {cgpa, projects, internship, communication, extra});
                    ys.add(target);
                    maxCgpa = Math.max(maxCgpa, cgpa);
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    // skip malformed row
                }
            }
            if (raw.isEmpty()) {
                throw new IOException("No usable rows were found in " + csv.getFileName());
            }
            double scale = explicitCgpaScale != null ? explicitCgpaScale
                    : maxCgpa > 5.0 ? 10.0 : (maxCgpa > 4.0 ? 5.0 : 4.0);
            for (double[] r : raw) {
                r[0] = Math.max(0, Math.min(1, r[0] / scale));
            }
            int[] labels = ys.stream().mapToInt(Integer::intValue).toArray();
            return new PlacementDataset(raw, labels, "CSV file " + csv.getFileName() + " (CGPA scale " + (int) scale + ")", false);
        }
    }

    /**
     * Practice data with a known, plausible relationship between the features and placement (higher CGPA, more
     * projects, an internship and better communication raise the chance). It is NOT evidence about real students.
     */
    public static PlacementDataset synthetic(int n, long seed) {
        Random rng = new Random(seed);
        List<double[]> rows = new ArrayList<>(n);
        int[] labels = new int[n];
        for (int i = 0; i < n; i++) {
            double cgpaRatio = 0.5 + 0.5 * rng.nextDouble();
            double projects = Math.min(8, poisson(rng, 2.2));
            double internship = rng.nextDouble() < 0.45 ? 1 : 0;
            double communication = 3 + rng.nextInt(8);       // 3..10
            double extra = rng.nextInt(11);                  // 0..10
            double z = 7 * (cgpaRatio - 0.75) + 0.35 * projects + 1.0 * internship + 0.25 * (communication - 6)
                    + 0.08 * (extra - 5) + rng.nextGaussian() * 0.9 - 0.8;
            labels[i] = rng.nextDouble() < 1 / (1 + Math.exp(-z)) ? 1 : 0;
            rows.add(new double[] {cgpaRatio, projects, internship, communication, extra});
        }
        return new PlacementDataset(rows, labels, "Synthetic practice data (" + n + " rows, seed " + seed + ")", true);
    }

    private static int poisson(Random rng, double lambda) {
        double limit = Math.exp(-lambda);
        double p = 1;
        int k = 0;
        do {
            k++;
            p *= rng.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    private static int find(List<String> header, String key) throws IOException {
        for (String alias : ALIASES.get(key)) {
            for (int i = 0; i < header.size(); i++) {
                if (header.get(i).trim().toLowerCase(Locale.ROOT).replace(' ', '_').equals(alias)) {
                    return i;
                }
            }
        }
        throw new IOException("Could not find a column for '" + key + "'. Accepted names: " + ALIASES.get(key)
                + ". Columns in the file: " + header);
    }

    private static String cell(List<String> cells, int i) {
        return cells.get(i).trim();
    }

    private static double number(List<String> cells, int i) {
        String v = cell(cells, i);
        return v.isEmpty() ? Double.NaN : Double.parseDouble(v);
    }

    /** Placement and internship labels: yes/no, placed/not placed, true/false, 1/0. Counts above 0 mean yes. */
    static Integer yesNo(String v) {
        String s = v.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "yes", "y", "placed", "true", "1" -> { return 1; }
            case "no", "n", "not placed", "notplaced", "false", "0" -> { return 0; }
            default -> {
                try {
                    return Double.parseDouble(s) > 0 ? 1 : 0;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
    }

    /** Minimal RFC 4180 splitter (quoted fields, doubled quotes). */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
