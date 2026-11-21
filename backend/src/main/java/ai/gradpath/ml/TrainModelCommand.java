package ai.gradpath.ml;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Command-line trainer (no Spring, no database). Run through the application entry point:
 *
 * <pre>
 *   java -jar target/gradpath-ai.jar train-model data\kaggle\placement.csv --cgpa-scale 10
 *   java -jar target/gradpath-ai.jar train-model --synthetic
 *   ./mvnw spring-boot:run -Dspring-boot.run.arguments="train-model data/kaggle/placement.csv"
 * </pre>
 *
 * Trains logistic regression, a decision tree and a random forest, selecting by cross-validation ROC-AUC.
 * Options: {@code --out <file>} (default {@code backend/src/main/resources/ml/placement-model.json}) and {@code --seed <n>}.
 */
public final class TrainModelCommand {

    public static final String COMMAND = "train-model";
    static final Path DEFAULT_OUT = Path.of("backend", "src", "main", "resources", "ml", "placement-model.json");

    private TrainModelCommand() { }

    public static void main(String[] args) throws Exception {
        String csv = null;
        boolean synthetic = false;
        Double cgpaScale = null;
        Path out = DEFAULT_OUT;
        long seed = 42;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case COMMAND -> { }
                case "--synthetic" -> synthetic = true;
                case "--out" -> out = Path.of(args[++i]);
                case "--seed" -> seed = Long.parseLong(args[++i]);
                case "--cgpa-scale" -> cgpaScale = Double.parseDouble(args[++i]);
                default -> csv = a;
            }
        }
        if ((csv == null && !synthetic) || (csv != null && cgpaScale == null)) {
            System.err.println("Usage: train-model <placement.csv> --cgpa-scale <4|5|10> | --synthetic  [--out file] [--seed n]");
            System.exit(2);
            return;
        }
        PlacementDataset data = synthetic ? PlacementDataset.synthetic(10_000, seed)
                : PlacementDataset.load(Path.of(csv), cgpaScale);
        System.out.printf(Locale.ROOT, "Loaded %d rows from %s. Placement rate %.1f%%.%n",
                data.size(), data.source(), 100 * data.placementRate());
        PlacementModel m = ModelTrainer.trainBest(data, seed);
        System.out.println("Candidate comparison (model selection uses CV ROC-AUC):");
        for (ModelCandidate candidate : m.comparisons()) {
            System.out.printf(Locale.ROOT, "  %-36s train %.4f  CV %.4f +/- %.4f  test %.4f  F1 %.4f%s%n",
                    candidate.name(), candidate.trainAuc(), candidate.cvAucMean(), candidate.cvAucStd(),
                    candidate.test().rocAuc(), candidate.test().f1(), candidate.selected() ? "  [selected]" : "");
        }
        if (PlacementModel.LOGISTIC_REGRESSION.equals(m.algorithm())) {
            for (double[] s : m.lambdaSearch()) {
                System.out.printf(Locale.ROOT, "  logistic lambda %-7s CV ROC-AUC %.4f%n", s[0], s[1]);
            }
        }
        ModelMetrics t = m.test();
        System.out.printf(Locale.ROOT, "Selected %s, threshold %.2f, CV ROC-AUC %.4f +/- %.4f%n",
                m.algorithmName(), m.threshold(), m.cvAucMean(), m.cvAucStd());
        System.out.printf(Locale.ROOT, "Test (%d rows): ROC-AUC %.4f  accuracy %.4f  precision %.4f  recall %.4f  F1 %.4f  Brier %.4f%n",
                t.rows(), t.rocAuc(), t.accuracy(), t.precision(), t.recall(), t.f1(), t.brier());
        if (PlacementModel.LOGISTIC_REGRESSION.equals(m.algorithm())) {
            for (int j = 0; j < PlacementFeatures.COUNT; j++) {
                System.out.printf(Locale.ROOT, "  %-16s weight %+.4f  odds ratio per SD %.3f%n",
                        PlacementFeatures.LABELS.get(j), m.weights()[j], m.oddsRatio(j));
            }
        } else {
            for (int j = 0; j < PlacementFeatures.COUNT; j++) {
                System.out.printf(Locale.ROOT, "  %-16s feature importance %.3f%n",
                        PlacementFeatures.LABELS.get(j), m.featureImportance()[j]);
            }
        }
        ModelStore.write(m, out);
        System.out.println("Saved model to " + out.toAbsolutePath());
    }
}
