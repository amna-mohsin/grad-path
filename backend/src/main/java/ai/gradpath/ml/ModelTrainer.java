package ai.gradpath.ml;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Trains and evaluates logistic regression, a decision tree and a random forest in Java, deterministically for a seed.
 *
 * <ol>
 *   <li>Stratified split: 80% training, 20% held-out test (never used for any choice).</li>
 *   <li>Each classifier is compared with stratified 5-fold cross-validation on training rows.</li>
 *   <li>The candidate with the best mean ROC-AUC is selected; a simpler candidate wins near-ties.</li>
 *   <li>Each decision threshold maximises F1 on out-of-fold predictions.</li>
 *   <li>The final model is fitted on all training rows and evaluated once on the test rows.</li>
 * </ol>
 */
public final class ModelTrainer {

    public static final double[] LAMBDAS = {0.0, 0.1, 1.0, 10.0, 100.0, 1000.0};
    public static final int FOLDS = 5;
    public static final String VERSION = "logreg-1.0";
    /** Penalties whose CV ROC-AUC is within this of the best are treated as equally good. */
    static final double NEAR_TIE = 0.001;
    private static final int TREE_MAX_DEPTH = 5;
    private static final int TREE_MIN_LEAF = 20;
    private static final int FOREST_TREE_COUNT = 24;
    private static final int FOREST_MAX_DEPTH = 6;
    private static final int FOREST_MIN_LEAF = 15;

    private ModelTrainer() { }

    /** Train and compare three classifiers; selection uses CV ROC-AUC, never the held-out test results. */
    public static PlacementModel trainBest(PlacementDataset data, long seed) {
        PlacementModel logistic = train(data, seed);
        CandidateTraining tree = trainTreeCandidate(data, seed, false);
        CandidateTraining forest = trainTreeCandidate(data, seed, true);

        List<ModelCandidate> candidates = new ArrayList<>();
        candidates.add(new ModelCandidate(PlacementModel.LOGISTIC_REGRESSION, PlacementModel.NAME,
                logistic.trainAuc(), logistic.cvAucMean(), logistic.cvAucStd(), logistic.threshold(), false,
                logistic.test()));
        candidates.add(new ModelCandidate(PlacementModel.DECISION_TREE, "Decision tree",
                tree.trainAuc(), tree.cvAucMean(), tree.cvAucStd(), tree.threshold(), false, tree.test()));
        candidates.add(new ModelCandidate(PlacementModel.RANDOM_FOREST, "Random forest (" + FOREST_TREE_COUNT + " trees)",
                forest.trainAuc(), forest.cvAucMean(), forest.cvAucStd(), forest.threshold(), false, forest.test()));

        double bestAuc = candidates.stream().mapToDouble(ModelCandidate::cvAucMean).max().orElseThrow();
        int selected = 0;
        while (selected + 1 < candidates.size()
                && candidates.get(selected).cvAucMean() < bestAuc - NEAR_TIE) {
            selected++;
        }
        List<ModelCandidate> comparison = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ModelCandidate c = candidates.get(i);
            comparison.add(new ModelCandidate(c.algorithm(), c.name(), c.trainAuc(), c.cvAucMean(), c.cvAucStd(),
                    c.threshold(), i == selected, c.test()));
        }

        if (selected == 0) {
            return new PlacementModel(logistic.format(), logistic.version(), logistic.trainedAt(), logistic.dataSource(),
                    logistic.synthetic(), logistic.rows(), logistic.trainRows(), logistic.testRows(), logistic.trainAuc(),
                    logistic.placementRate(), logistic.features(), logistic.mean(), logistic.scale(), logistic.min(),
                    logistic.max(), logistic.weights(), logistic.bias(), logistic.lambda(), logistic.threshold(),
                    logistic.cvFolds(), logistic.cvAucMean(), logistic.cvAucStd(), logistic.lambdaSearch(),
                    logistic.test(), PlacementModel.LOGISTIC_REGRESSION, List.of(), comparison,
                    logistic.featureImportance());
        }

        CandidateTraining winner = selected == 1 ? tree : forest;
        double[][] trainingRows = rowsForTraining(data, seed);
        FeatureStats stats = featureStats(trainingRows);
        return new PlacementModel(PlacementModel.FORMAT,
                selected == 1 ? "decision-tree-1.0" : "random-forest-1.0",
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), data.source(), data.synthetic(),
                data.size(), winner.trainRows(), winner.test().rows(), winner.trainAuc(),
                winner.trainRate(),
                PlacementFeatures.NAMES, stats.mean(), stats.scale(), stats.min(), stats.max(), new double[0], 0,
                0, winner.threshold(), FOLDS, winner.cvAucMean(), winner.cvAucStd(), List.of(), winner.test(),
                winner.algorithm(), winner.trees(), comparison, winner.featureImportance());
    }

    public static PlacementModel train(PlacementDataset data, long seed) {
        if (data.size() < 50) {
            throw new IllegalArgumentException("At least 50 labelled rows are needed to train and test a model");
        }
        double overallRate = data.placementRate();
        if (overallRate == 0 || overallRate == 1) {
            throw new IllegalArgumentException("The dataset must contain both placed and not-placed students");
        }
        Random rng = new Random(seed);
        int[] fold = stratifiedAssignment(data.labels(), 5, rng);   // 5 parts: part 0 = test (20%)
        List<Integer> trainIdx = new ArrayList<>();
        List<Integer> testIdx = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            (fold[i] == 0 ? testIdx : trainIdx).add(i);
        }
        double[][] xTrain = rows(data, trainIdx);
        int[] yTrain = labels(data, trainIdx);
        double rate = placementRate(yTrain);
        double[][] xTest = rows(data, testIdx);
        int[] yTest = labels(data, testIdx);

        int[] cvFold = stratifiedAssignment(yTrain, FOLDS, rng);
        double bestAuc = -1;
        double bestLambda = LAMBDAS[0];
        double bestStd = 0;
        double[] bestOof = null;
        List<double[]> search = new ArrayList<>();
        List<double[]> oofs = new ArrayList<>();
        for (double lambda : LAMBDAS) {
            double[] oof = new double[yTrain.length];
            double[] aucs = new double[FOLDS];
            for (int k = 0; k < FOLDS; k++) {
                List<Integer> fit = new ArrayList<>();
                List<Integer> val = new ArrayList<>();
                for (int i = 0; i < yTrain.length; i++) {
                    (cvFold[i] == k ? val : fit).add(i);
                }
                double[][] xf = pick(xTrain, fit);
                int[] yf = pick(yTrain, fit);
                Scaler s = Scaler.fit(xf);
                LogisticRegression.Fit m = LogisticRegression.train(s.transform(xf), yf, lambda);
                double[] pv = new double[val.size()];
                int[] yv = new int[val.size()];
                for (int v = 0; v < val.size(); v++) {
                    int i = val.get(v);
                    pv[v] = m.probability(s.transform(xTrain[i]));
                    yv[v] = yTrain[i];
                    oof[i] = pv[v];
                }
                aucs[k] = ModelMetrics.rocAuc(pv, yv);
            }
            double mean = 0;
            for (double a : aucs) {
                mean += a / FOLDS;
            }
            double var = 0;
            for (double a : aucs) {
                var += (a - mean) * (a - mean) / FOLDS;
            }
            search.add(new double[] {lambda, ModelMetrics.r4(mean), ModelMetrics.r4(Math.sqrt(var))});
            oofs.add(oof);
        }
        double top = search.stream().mapToDouble(s -> s[1]).max().orElse(0);
        for (int c = 0; c < search.size(); c++) {          // lambdas ascend: the last near-best one is the simplest
            if (search.get(c)[1] >= top - NEAR_TIE) {
                bestAuc = search.get(c)[1];
                bestLambda = search.get(c)[0];
                bestStd = search.get(c)[2];
                bestOof = oofs.get(c);
            }
        }
        search.replaceAll(s -> new double[] {s[0], s[1]});
        double threshold = bestF1Threshold(bestOof, yTrain);

        Scaler scaler = Scaler.fit(xTrain);
        LogisticRegression.Fit fit = LogisticRegression.train(scaler.transform(xTrain), yTrain, bestLambda);
        double[] pTrain = new double[yTrain.length];
        for (int i = 0; i < yTrain.length; i++) {
            pTrain[i] = fit.probability(scaler.transform(xTrain[i]));
        }
        double trainAuc = ModelMetrics.r4(ModelMetrics.rocAuc(pTrain, yTrain));
        double[] pTest = new double[yTest.length];
        for (int i = 0; i < yTest.length; i++) {
            pTest[i] = fit.probability(scaler.transform(xTest[i]));
        }
        ModelMetrics test = ModelMetrics.evaluate(pTest, yTest, threshold);

        double[] min = new double[PlacementFeatures.COUNT];
        double[] max = new double[PlacementFeatures.COUNT];
        java.util.Arrays.fill(min, Double.MAX_VALUE);
        java.util.Arrays.fill(max, -Double.MAX_VALUE);
        for (double[] r : xTrain) {
            for (int j = 0; j < r.length; j++) {
                min[j] = Math.min(min[j], r[j]);
                max[j] = Math.max(max[j], r[j]);
            }
        }
        return new PlacementModel(PlacementModel.FORMAT, VERSION, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                data.source(), data.synthetic(), data.size(), yTrain.length, yTest.length, trainAuc,
                ModelMetrics.r4(rate),
                PlacementFeatures.NAMES, round(scaler.mean), round(scaler.scale), min, max, round(fit.weights()),
                ModelMetrics.r4(fit.bias()), bestLambda, threshold, FOLDS, ModelMetrics.r4(bestAuc),
                ModelMetrics.r4(bestStd), search, test, PlacementModel.LOGISTIC_REGRESSION, List.of(), List.of(), null);
    }

    private static CandidateTraining trainTreeCandidate(PlacementDataset data, long seed, boolean forest) {
        Random splitRandom = new Random(seed);
        int[] assignment = stratifiedAssignment(data.labels(), 5, splitRandom);
        List<Integer> trainIdx = new ArrayList<>();
        List<Integer> testIdx = new ArrayList<>();
        for (int i = 0; i < assignment.length; i++) {
            (assignment[i] == 0 ? testIdx : trainIdx).add(i);
        }
        double[][] xTrain = rows(data, trainIdx);
        int[] yTrain = labels(data, trainIdx);
        double[][] xTest = rows(data, testIdx);
        int[] yTest = labels(data, testIdx);
        int[] cvFold = stratifiedAssignment(yTrain, FOLDS, splitRandom);
        double[] oof = new double[yTrain.length];
        double[] aucs = new double[FOLDS];

        for (int k = 0; k < FOLDS; k++) {
            List<Integer> fitIdx = new ArrayList<>();
            List<Integer> validationIdx = new ArrayList<>();
            for (int i = 0; i < yTrain.length; i++) {
                (cvFold[i] == k ? validationIdx : fitIdx).add(i);
            }
            double[][] xFit = pick(xTrain, fitIdx);
            int[] yFit = pick(yTrain, fitIdx);
            TrainedTrees fitted = fitTrees(xFit, yFit, forest, seed + 1009L * (k + 1));
            double[] validationProbabilities = new double[validationIdx.size()];
            int[] validationLabels = new int[validationIdx.size()];
            for (int i = 0; i < validationIdx.size(); i++) {
                int row = validationIdx.get(i);
                validationProbabilities[i] = probability(fitted.trees(), xTrain[row]);
                validationLabels[i] = yTrain[row];
                oof[row] = validationProbabilities[i];
            }
            aucs[k] = ModelMetrics.rocAuc(validationProbabilities, validationLabels);
        }

        double mean = Arrays.stream(aucs).average().orElse(0);
        double variance = Arrays.stream(aucs).map(a -> (a - mean) * (a - mean)).average().orElse(0);
        double threshold = bestF1Threshold(oof, yTrain);
        TrainedTrees fitted = fitTrees(xTrain, yTrain, forest, seed + 9_999);
        double[] trainProbabilities = new double[xTrain.length];
        for (int i = 0; i < xTrain.length; i++) {
            trainProbabilities[i] = probability(fitted.trees(), xTrain[i]);
        }
        double trainAuc = ModelMetrics.r4(ModelMetrics.rocAuc(trainProbabilities, yTrain));
        double[] testProbabilities = new double[xTest.length];
        for (int i = 0; i < xTest.length; i++) {
            testProbabilities[i] = probability(fitted.trees(), xTest[i]);
        }
        ModelMetrics test = ModelMetrics.evaluate(testProbabilities, yTest, threshold);
        return new CandidateTraining(forest ? PlacementModel.RANDOM_FOREST : PlacementModel.DECISION_TREE,
                threshold, ModelMetrics.r4(mean), ModelMetrics.r4(Math.sqrt(variance)), test, yTrain.length, trainAuc,
                placementRate(yTrain), fitted.trees(), fitted.featureImportance());
    }

    private static TrainedTrees fitTrees(double[][] x, int[] y, boolean forest, long seed) {
        int count = forest ? FOREST_TREE_COUNT : 1;
        int maxDepth = forest ? FOREST_MAX_DEPTH : TREE_MAX_DEPTH;
        int minLeaf = forest ? FOREST_MIN_LEAF : TREE_MIN_LEAF;
        int maxFeatures = forest ? Math.max(1, (int) Math.sqrt(x[0].length)) : x[0].length;
        List<DecisionTreeModel> trees = new ArrayList<>(count);
        double[] importance = new double[x[0].length];
        for (int i = 0; i < count; i++) {
            DecisionTreeClassifier.Fitted fitted = DecisionTreeClassifier.train(x, y, maxDepth, minLeaf,
                    maxFeatures, forest, seed + i * 31L);
            trees.add(fitted.model());
            for (int j = 0; j < importance.length; j++) {
                importance[j] += fitted.importance()[j] / count;
            }
        }
        return new TrainedTrees(trees, importance);
    }

    private static double probability(List<DecisionTreeModel> trees, double[] row) {
        double total = 0;
        for (DecisionTreeModel tree : trees) {
            total += tree.probability(row);
        }
        return total / trees.size();
    }

    private static double[][] rowsForTraining(PlacementDataset data, long seed) {
        int[] assignment = stratifiedAssignment(data.labels(), 5, new Random(seed));
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < assignment.length; i++) {
            if (assignment[i] != 0) {
                indices.add(i);
            }
        }
        return rows(data, indices);
    }

    private static FeatureStats featureStats(double[][] rows) {
        int d = rows[0].length;
        double[] mean = new double[d];
        double[] scale = new double[d];
        double[] min = new double[d];
        double[] max = new double[d];
        Arrays.fill(min, Double.MAX_VALUE);
        Arrays.fill(max, -Double.MAX_VALUE);
        for (double[] row : rows) {
            for (int j = 0; j < d; j++) {
                mean[j] += row[j] / rows.length;
                min[j] = Math.min(min[j], row[j]);
                max[j] = Math.max(max[j], row[j]);
            }
        }
        for (double[] row : rows) {
            for (int j = 0; j < d; j++) {
                scale[j] += (row[j] - mean[j]) * (row[j] - mean[j]) / rows.length;
            }
        }
        for (int j = 0; j < d; j++) {
            scale[j] = Math.sqrt(scale[j]);
            if (scale[j] < 1e-12) {
                scale[j] = 1;
            }
        }
        return new FeatureStats(round(mean), round(scale), min, max);
    }

    /** Threshold (0.05..0.95 in steps of 0.01) with the highest F1 on the given predictions. */
    static double bestF1Threshold(double[] p, int[] y) {
        double best = 0.5;
        double bestF1 = -1;
        for (int t = 5; t <= 95; t++) {
            double th = t / 100.0;
            int tp = 0;
            int fp = 0;
            int fn = 0;
            for (int i = 0; i < p.length; i++) {
                boolean pred = p[i] >= th;
                if (pred && y[i] == 1) {
                    tp++;
                } else if (pred) {
                    fp++;
                } else if (y[i] == 1) {
                    fn++;
                }
            }
            double f1 = tp == 0 ? 0 : 2.0 * tp / (2.0 * tp + fp + fn);
            if (f1 > bestF1 + 1e-12) {
                bestF1 = f1;
                best = th;
            }
        }
        return best;
    }

    /** Assign each row to one of k parts so every part has about the same share of each class. */
    static int[] stratifiedAssignment(int[] y, int k, Random rng) {
        int[] out = new int[y.length];
        for (int cls = 0; cls <= 1; cls++) {
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < y.length; i++) {
                if (y[i] == cls) {
                    idx.add(i);
                }
            }
            java.util.Collections.shuffle(idx, rng);
            for (int j = 0; j < idx.size(); j++) {
                out[idx.get(j)] = j % k;
            }
        }
        return out;
    }

    private static double[][] rows(PlacementDataset d, List<Integer> idx) {
        return idx.stream().map(i -> d.rows().get(i)).toArray(double[][]::new);
    }

    private static int[] labels(PlacementDataset d, List<Integer> idx) {
        return idx.stream().mapToInt(i -> d.labels()[i]).toArray();
    }

    private static double placementRate(int[] labels) {
        return (double) Arrays.stream(labels).sum() / labels.length;
    }

    private static double[][] pick(double[][] x, List<Integer> idx) {
        return idx.stream().map(i -> x[i]).toArray(double[][]::new);
    }

    private static int[] pick(int[] y, List<Integer> idx) {
        return idx.stream().mapToInt(i -> y[i]).toArray();
    }

    private record TrainedTrees(List<DecisionTreeModel> trees, double[] featureImportance) { }

    private record CandidateTraining(String algorithm, double threshold, double cvAucMean, double cvAucStd,
                                     ModelMetrics test, int trainRows, double trainAuc, double trainRate,
                                     List<DecisionTreeModel> trees, double[] featureImportance) { }

    private record FeatureStats(double[] mean, double[] scale, double[] min, double[] max) { }

    private static double[] round(double[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = Math.round(v[i] * 1e8) / 1e8;
        }
        return out;
    }

    /** Standardisation (z-scores) fitted on training rows only. */
    record Scaler(double[] mean, double[] scale) {

        static Scaler fit(double[][] x) {
            int d = x[0].length;
            double[] mean = new double[d];
            double[] scale = new double[d];
            for (double[] r : x) {
                for (int j = 0; j < d; j++) {
                    mean[j] += r[j] / x.length;
                }
            }
            for (double[] r : x) {
                for (int j = 0; j < d; j++) {
                    scale[j] += (r[j] - mean[j]) * (r[j] - mean[j]) / x.length;
                }
            }
            for (int j = 0; j < d; j++) {
                scale[j] = Math.sqrt(scale[j]);
                if (scale[j] < 1e-12) {
                    scale[j] = 1;           // constant feature: leave it centred, avoid division by zero
                }
            }
            return new Scaler(mean, scale);
        }

        double[] transform(double[] r) {
            double[] z = new double[r.length];
            for (int j = 0; j < r.length; j++) {
                z[j] = (r[j] - mean[j]) / scale[j];
            }
            return z;
        }

        double[][] transform(double[][] x) {
            double[][] out = new double[x.length][];
            for (int i = 0; i < x.length; i++) {
                out[i] = transform(x[i]);
            }
            return out;
        }
    }
}
