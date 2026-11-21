package ai.gradpath.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Small CART-style binary classifier used by the placement-model comparison pipeline. */
final class DecisionTreeClassifier {

    private DecisionTreeClassifier() { }

    static Fitted train(double[][] x, int[] y, int maxDepth, int minLeaf, int maxFeatures,
                        boolean bootstrap, long seed) {
        Random random = new Random(seed);
        int[] sample = new int[x.length];
        for (int i = 0; i < sample.length; i++) {
            sample[i] = bootstrap ? random.nextInt(x.length) : i;
        }
        Builder builder = new Builder(x, y, maxDepth, minLeaf, maxFeatures, random);
        int root = builder.build(sample, 0);
        if (root != 0) {
            throw new IllegalStateException("Decision-tree root was not created first");
        }
        return new Fitted(new DecisionTreeModel(builder.nodes), builder.importance());
    }

    record Fitted(DecisionTreeModel model, double[] importance) { }

    private static final class Builder {

        private final double[][] x;
        private final int[] y;
        private final int maxDepth;
        private final int minLeaf;
        private final int maxFeatures;
        private final Random random;
        private final List<DecisionTreeModel.Node> nodes = new ArrayList<>();
        private final double[] importance;

        Builder(double[][] x, int[] y, int maxDepth, int minLeaf, int maxFeatures, Random random) {
            this.x = x;
            this.y = y;
            this.maxDepth = maxDepth;
            this.minLeaf = minLeaf;
            this.maxFeatures = Math.min(maxFeatures, x[0].length);
            this.random = random;
            this.importance = new double[x[0].length];
        }

        int build(int[] rows, int depth) {
            int positives = 0;
            for (int row : rows) {
                positives += y[row];
            }
            double probability = (positives + 1.0) / (rows.length + 2.0);
            int nodeIndex = nodes.size();
            nodes.add(new DecisionTreeModel.Node(-1, 0, probability, -1, -1));
            if (depth >= maxDepth || rows.length < minLeaf * 2 || positives == 0 || positives == rows.length) {
                return nodeIndex;
            }

            Split split = bestSplit(rows, positives);
            if (split == null || split.gain() <= 1e-12) {
                return nodeIndex;
            }
            int[] left = new int[split.leftCount()];
            int[] right = new int[rows.length - left.length];
            int l = 0;
            int r = 0;
            for (int row : rows) {
                if (x[row][split.feature()] <= split.threshold()) {
                    left[l++] = row;
                } else {
                    right[r++] = row;
                }
            }
            if (l != left.length || r != right.length) {
                throw new IllegalStateException("Decision-tree split produced an unexpected row count");
            }
            importance[split.feature()] += split.gain() * rows.length;
            int leftIndex = build(left, depth + 1);
            int rightIndex = build(right, depth + 1);
            nodes.set(nodeIndex, new DecisionTreeModel.Node(split.feature(), split.threshold(), probability,
                    leftIndex, rightIndex));
            return nodeIndex;
        }

        private Split bestSplit(int[] rows, int positives) {
            int featureCount = x[0].length;
            int[] features = new int[featureCount];
            for (int i = 0; i < featureCount; i++) {
                features[i] = i;
            }
            if (maxFeatures < featureCount) {
                for (int i = featureCount - 1; i > 0; i--) {
                    int j = random.nextInt(i + 1);
                    int tmp = features[i];
                    features[i] = features[j];
                    features[j] = tmp;
                }
            }

            double parentImpurity = gini(rows.length, positives);
            Split best = null;
            for (int candidate = 0; candidate < maxFeatures; candidate++) {
                int feature = features[candidate];
                Integer[] ordered = Arrays.stream(rows).boxed().toArray(Integer[]::new);
                Arrays.sort(ordered, (a, b) -> Double.compare(x[a][feature], x[b][feature]));
                int leftPositive = 0;
                for (int i = 0; i < ordered.length - 1; i++) {
                    leftPositive += y[ordered[i]];
                    int leftCount = i + 1;
                    int rightCount = ordered.length - leftCount;
                    double low = x[ordered[i]][feature];
                    double high = x[ordered[i + 1]][feature];
                    if (low == high || leftCount < minLeaf || rightCount < minLeaf) {
                        continue;
                    }
                    double gain = parentImpurity
                            - (leftCount * gini(leftCount, leftPositive)
                            + rightCount * gini(rightCount, positives - leftPositive)) / rows.length;
                    if (best == null || gain > best.gain() + 1e-12) {
                        best = new Split(feature, low + (high - low) / 2.0, leftCount, gain);
                    }
                }
            }
            return best;
        }

        private double[] importance() {
            double sum = Arrays.stream(importance).sum();
            if (sum > 0) {
                for (int i = 0; i < importance.length; i++) {
                    importance[i] /= sum;
                }
            }
            return importance;
        }
    }

    private static double gini(int count, int positives) {
        double p = (double) positives / count;
        return 2 * p * (1 - p);
    }

    private record Split(int feature, double threshold, int leftCount, double gain) { }
}
