package ai.gradpath.ml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A trained placement classifier and its evaluation results. The JSON file contains model parameters and metrics,
 * never training records.
 *
 * @param trainAuc ROC-AUC when the selected classifier is evaluated on its training rows
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlacementModel(String format, String version, String trainedAt, String dataSource, boolean synthetic,
                             int rows, int trainRows, int testRows, double trainAuc, double placementRate,
                             List<String> features,
                             double[] mean, double[] scale, double[] min, double[] max, double[] weights, double bias,
                             double lambda, double threshold, int cvFolds, double cvAucMean, double cvAucStd,
                             List<double[]> lambdaSearch, ModelMetrics test, String algorithm,
                             List<DecisionTreeModel> trees, List<ModelCandidate> comparisons,
                             double[] featureImportance) {

    public static final String FORMAT = "gradpath-placement-model-v2";
    private static final String LEGACY_FORMAT = "gradpath-logreg-v1";
    public static final String LOGISTIC_REGRESSION = "logistic-regression";
    public static final String DECISION_TREE = "decision-tree";
    public static final String RANDOM_FOREST = "random-forest";
    public static final String NAME = "Logistic regression (L2, Newton/IRLS)";

    /** One feature's effect on an individual estimate, in log-odds relative to an average training profile. */
    public record Contribution(String feature, String label, double value, double logOdds) { }

    public PlacementModel {
        if (!FORMAT.equals(format) && !LEGACY_FORMAT.equals(format)) {
            throw new IllegalArgumentException("Unsupported model format: " + format);
        }
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = LOGISTIC_REGRESSION;
        }
        trees = trees == null ? List.of() : List.copyOf(trees);
        comparisons = comparisons == null ? List.of() : List.copyOf(comparisons);
        int d = PlacementFeatures.COUNT;
        if (features == null || !features.equals(PlacementFeatures.NAMES) || mean == null || mean.length != d
                || scale == null || scale.length != d || min == null || min.length != d || max == null || max.length != d
                || weights == null || (LOGISTIC_REGRESSION.equals(algorithm) && weights.length != d)) {
            throw new IllegalArgumentException("The model file does not match the five expected features");
        }
        if ((DECISION_TREE.equals(algorithm) || RANDOM_FOREST.equals(algorithm)) && trees.isEmpty()) {
            throw new IllegalArgumentException("The selected tree model has no trees");
        }
        if (!LOGISTIC_REGRESSION.equals(algorithm) && !DECISION_TREE.equals(algorithm)
                && !RANDOM_FOREST.equals(algorithm)) {
            throw new IllegalArgumentException("Unsupported placement model algorithm: " + algorithm);
        }
        if (featureImportance == null || featureImportance.length != d) {
            featureImportance = new double[d];
            if (LOGISTIC_REGRESSION.equals(algorithm)) {
                for (int i = 0; i < d; i++) {
                    featureImportance[i] = Math.abs(weights[i]);
                }
            }
        }
    }

    /** Retained for code constructing the original logistic-regression model shape. */
    public PlacementModel(String format, String version, String trainedAt, String dataSource, boolean synthetic,
                          int rows, int trainRows, int testRows, double placementRate, List<String> features,
                          double[] mean, double[] scale, double[] min, double[] max, double[] weights, double bias,
                          double lambda, double threshold, int cvFolds, double cvAucMean, double cvAucStd,
                          List<double[]> lambdaSearch, ModelMetrics test) {
        this(format, version, trainedAt, dataSource, synthetic, rows, trainRows, testRows, 0, placementRate, features,
                mean, scale, min, max, weights, bias, lambda, threshold, cvFolds, cvAucMean, cvAucStd, lambdaSearch,
                test, LOGISTIC_REGRESSION, List.of(), List.of(), null);
    }

    /** Clamp to the training range, then standardise. */
    public double[] standardise(PlacementFeatures f) {
        double[] x = f.toArray();
        double[] z = new double[x.length];
        for (int j = 0; j < x.length; j++) {
            double v = Math.max(min[j], Math.min(max[j], x[j]));
            z[j] = (v - mean[j]) / scale[j];
        }
        return z;
    }

    /** True when any input lies outside the range seen in training (the estimate then uses the nearest edge). */
    public boolean outsideTrainingRange(PlacementFeatures f) {
        double[] x = f.toArray();
        for (int j = 0; j < x.length; j++) {
            if (x[j] < min[j] - 1e-9 || x[j] > max[j] + 1e-9) {
                return true;
            }
        }
        return false;
    }

    public double logit(PlacementFeatures f) {
        if (!LOGISTIC_REGRESSION.equals(algorithm)) {
            double p = Math.max(1e-15, Math.min(1 - 1e-15, probability(f)));
            return Math.log(p / (1 - p));
        }
        double[] z = standardise(f);
        double s = bias;
        for (int j = 0; j < z.length; j++) {
            s += weights[j] * z[j];
        }
        return s;
    }

    /** Estimated probability of placement, 0..1. */
    public double probability(PlacementFeatures f) {
        if (LOGISTIC_REGRESSION.equals(algorithm)) {
            return LogisticRegression.sigmoid(logit(f));
        }
        double total = 0;
        double[] input = f.toArray();
        for (int i = 0; i < input.length; i++) {
            input[i] = Math.max(min[i], Math.min(max[i], input[i]));
        }
        for (DecisionTreeModel tree : trees) {
            total += tree.probability(input);
        }
        return total / trees.size();
    }

    /**
     * Per-feature effects, largest first. Tree-model values are local one-feature perturbations and need not add
     * exactly to the prediction because trees can model interactions.
     */
    public List<Contribution> contributions(PlacementFeatures f) {
        double[] x = f.toArray();
        List<Contribution> out = new ArrayList<>();
        if (LOGISTIC_REGRESSION.equals(algorithm)) {
            double[] z = standardise(f);
            for (int j = 0; j < z.length; j++) {
                out.add(new Contribution(features.get(j), PlacementFeatures.LABELS.get(j), x[j], weights[j] * z[j]));
            }
        } else {
            double predictionLogOdds = logit(f);
            for (int j = 0; j < x.length; j++) {
                double[] baseline = x.clone();
                baseline[j] = mean[j];
                out.add(new Contribution(features.get(j), PlacementFeatures.LABELS.get(j), x[j],
                        predictionLogOdds - logit(PlacementFeatures.of(baseline))));
            }
        }
        out.sort(Comparator.comparingDouble((Contribution c) -> Math.abs(c.logOdds())).reversed());
        return out;
    }

    /** Coefficients converted to odds ratios per one standard deviation, for logistic regression. */
    public double oddsRatio(int feature) {
        if (!LOGISTIC_REGRESSION.equals(algorithm)) {
            throw new IllegalStateException("Odds ratios are available only for logistic regression");
        }
        return Math.exp(weights[feature]);
    }

    public String algorithmName() {
        return switch (algorithm) {
            case DECISION_TREE -> "Decision tree";
            case RANDOM_FOREST -> "Random forest";
            default -> NAME;
        };
    }
}
