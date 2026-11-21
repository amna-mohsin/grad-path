package ai.gradpath.ml;

import java.util.List;

/** Serializable binary decision tree whose leaves store Laplace-smoothed placement probabilities. */
public record DecisionTreeModel(List<Node> nodes) {

    public DecisionTreeModel {
        nodes = List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("A decision tree must contain a root node");
        }
    }

    public double probability(double[] features) {
        int index = 0;
        while (true) {
            Node node = nodes.get(index);
            if (node.feature() < 0) {
                return node.probability();
            }
            index = features[node.feature()] <= node.threshold() ? node.left() : node.right();
        }
    }

    public record Node(int feature, double threshold, double probability, int left, int right) { }
}
