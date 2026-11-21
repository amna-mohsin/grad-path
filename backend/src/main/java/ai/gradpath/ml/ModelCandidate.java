package ai.gradpath.ml;

/** Training, cross-validation and held-out results for one candidate placement classifier. */
public record ModelCandidate(String algorithm, String name, double trainAuc, double cvAucMean, double cvAucStd,
                             double threshold, boolean selected, ModelMetrics test) { }
