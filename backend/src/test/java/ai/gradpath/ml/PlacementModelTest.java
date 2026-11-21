package ai.gradpath.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.gradpath.readiness.ProfileSnapshot;
import ai.gradpath.service.PlacementModelService;

class PlacementModelTest {

    private static PlacementModel model;

    @BeforeAll
    static void train() {
        model = ModelTrainer.train(PlacementDataset.synthetic(4000, 7), 7);
    }

    private static ProfileSnapshot profile(double cgpa, int projects, int internships, int comm, int extra) {
        return new ProfileSnapshot(cgpa, 4.0, projects, internships, 0, 3, 0, 0, comm, extra, 100, List.of());
    }

    // ---- logistic regression ----

    @Test
    void sigmoidIsStableAtTheExtremes() {
        assertThat(LogisticRegression.sigmoid(0)).isEqualTo(0.5);
        assertThat(LogisticRegression.sigmoid(800)).isEqualTo(1.0);
        assertThat(LogisticRegression.sigmoid(-800)).isEqualTo(0.0);
        assertThat(LogisticRegression.sigmoid(2) + LogisticRegression.sigmoid(-2)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void solverSolvesASmallLinearSystem() {
        double[] x = LogisticRegression.solve(new double[][] {{4, 1}, {1, 3}}, new double[] {1, 2});
        assertThat(x[0]).isCloseTo(1.0 / 11, within(1e-12));
        assertThat(x[1]).isCloseTo(7.0 / 11, within(1e-12));
    }

    @Test
    void newtonRecoversAKnownRelationship() {
        // y depends on x through p = sigmoid(-0.5 + 2x); a large sample must recover roughly those coefficients
        java.util.Random rng = new java.util.Random(1);
        int n = 20000;
        double[][] x = new double[n][1];
        int[] y = new int[n];
        for (int i = 0; i < n; i++) {
            x[i][0] = rng.nextGaussian();
            y[i] = rng.nextDouble() < LogisticRegression.sigmoid(-0.5 + 2 * x[i][0]) ? 1 : 0;
        }
        LogisticRegression.Fit fit = LogisticRegression.train(x, y, 0);
        assertThat(fit.weights()[0]).isCloseTo(2.0, within(0.1));
        assertThat(fit.bias()).isCloseTo(-0.5, within(0.1));
        assertThat(fit.iterations()).isLessThan(20);
    }

    @Test
    void penaltyShrinksTheWeights() {
        double[][] x = {{-2}, {-1}, {-0.5}, {0.5}, {1}, {2}, {-1.5}, {1.5}};
        int[] y = {0, 0, 1, 0, 1, 1, 0, 1};
        double free = Math.abs(LogisticRegression.train(x, y, 0).weights()[0]);
        double shrunk = Math.abs(LogisticRegression.train(x, y, 50).weights()[0]);
        assertThat(shrunk).isLessThan(free);
    }

    // ---- metrics ----

    @Test
    void rocAucMatchesHandCalculation() {
        assertThat(ModelMetrics.rocAuc(new double[] {0.1, 0.4, 0.35, 0.8}, new int[] {0, 0, 1, 1})).isEqualTo(0.75);
        assertThat(ModelMetrics.rocAuc(new double[] {0.9, 0.8, 0.2, 0.1}, new int[] {1, 1, 0, 0})).isEqualTo(1.0);
        assertThat(ModelMetrics.rocAuc(new double[] {0.5, 0.5, 0.5, 0.5}, new int[] {1, 0, 1, 0})).isEqualTo(0.5);
    }

    @Test
    void confusionMatrixAndScores() {
        ModelMetrics m = ModelMetrics.evaluate(new double[] {0.9, 0.7, 0.6, 0.2, 0.1}, new int[] {1, 0, 1, 1, 0}, 0.5);
        assertThat(new int[] {m.tp(), m.fp(), m.tn(), m.fn()}).containsExactly(2, 1, 1, 1);
        assertThat(m.accuracy()).isEqualTo(0.6);
        assertThat(m.precision()).isEqualTo(0.6667);
        assertThat(m.recall()).isEqualTo(0.6667);
        assertThat(m.roc().get(0)).containsExactly(0, 0);
        assertThat(m.roc().get(m.roc().size() - 1)).containsExactly(1, 1);
    }

    @Test
    void bestThresholdMaximisesF1() {
        double t = ModelTrainer.bestF1Threshold(new double[] {0.9, 0.8, 0.3, 0.2}, new int[] {1, 1, 0, 0});
        assertThat(t).isGreaterThan(0.3).isLessThanOrEqualTo(0.8);
    }

    // ---- training pipeline ----

    @Test
    void trainingIsDeterministicAndBeatsChance() {
        PlacementModel again = ModelTrainer.train(PlacementDataset.synthetic(4000, 7), 7);
        assertThat(again.weights()).containsExactly(model.weights());
        assertThat(again.threshold()).isEqualTo(model.threshold());
        assertThat(model.test().rocAuc()).isGreaterThan(0.72);
        assertThat(model.cvAucMean()).isGreaterThan(0.72);
        assertThat(model.trainRows() + model.testRows()).isEqualTo(4000);
        assertThat(model.lambdaSearch()).hasSize(ModelTrainer.LAMBDAS.length);
        assertThat(model.synthetic()).isTrue();
    }

    @Test
    void comparesThreeClassifiersAndPersistsTheCrossValidationWinner(@TempDir Path dir) throws Exception {
        PlacementModel selected = ModelTrainer.trainBest(PlacementDataset.synthetic(600, 13), 13);
        assertThat(selected.comparisons()).hasSize(3);
        assertThat(selected.comparisons().stream().map(ModelCandidate::algorithm).toList())
                .containsExactly(PlacementModel.LOGISTIC_REGRESSION, PlacementModel.DECISION_TREE,
                        PlacementModel.RANDOM_FOREST);
        assertThat(selected.comparisons().stream().filter(ModelCandidate::selected).toList()).hasSize(1);
        assertThat(selected.comparisons().stream().filter(ModelCandidate::selected).findFirst().orElseThrow().algorithm())
                .isEqualTo(selected.algorithm());
        assertThat(selected.probability(PlacementFeatures.of(profile(3.2, 2, 1, 7, 5)))).isBetween(0.0, 1.0);

        Path file = dir.resolve("selected-model.json");
        ModelStore.write(selected, file);
        PlacementModel restored = ModelStore.read(file);
        assertThat(restored.algorithm()).isEqualTo(selected.algorithm());
        assertThat(restored.comparisons()).hasSize(3);
        assertThat(restored.probability(PlacementFeatures.of(profile(3.2, 2, 1, 7, 5))))
                .isEqualTo(selected.probability(PlacementFeatures.of(profile(3.2, 2, 1, 7, 5))));
    }

    @Test
    void decisionTreeModelSurvivesJsonRoundTrip(@TempDir Path dir) throws Exception {
        PlacementDataset data = PlacementDataset.synthetic(400, 29);
        double[][] rows = data.rows().toArray(double[][]::new);
        DecisionTreeModel tree = DecisionTreeClassifier.train(rows, data.labels(), 3, 10,
                PlacementFeatures.COUNT, false, 29).model();
        PlacementModel treeModel = new PlacementModel(PlacementModel.FORMAT, "decision-tree-test", "2026-01-01T00:00:00Z",
                "test dataset", false, data.size(), 320, 80, 0.8, data.placementRate(), PlacementFeatures.NAMES,
                new double[] {0.75, 2, 0.4, 6, 5}, new double[] {0.15, 1.5, 0.5, 2, 3},
                new double[] {0.5, 0, 0, 3, 0}, new double[] {1, 8, 1, 10, 10}, new double[0], 0,
                0, 0.5, 5, 0.8, 0.01, List.of(), model.test(), PlacementModel.DECISION_TREE,
                List.of(tree), List.of(), new double[PlacementFeatures.COUNT]);
        Path file = dir.resolve("tree-model.json");
        ModelStore.write(treeModel, file);
        PlacementModel restored = ModelStore.read(file);
        PlacementFeatures input = PlacementFeatures.of(profile(3.2, 2, 1, 7, 5));
        assertThat(restored.algorithm()).isEqualTo(PlacementModel.DECISION_TREE);
        assertThat(restored.probability(input)).isEqualTo(treeModel.probability(input));
    }

    @Test
    void learnedEffectsPointTheExpectedWay() {
        for (double w : model.weights()) {
            assertThat(w).isPositive();          // the synthetic data rewards every feature
        }
        double weak = model.probability(PlacementFeatures.of(profile(2.4, 0, 0, 4, 2)));
        double strong = model.probability(PlacementFeatures.of(profile(3.9, 5, 1, 9, 8)));
        assertThat(strong).isGreaterThan(weak + 0.4);
    }

    @Test
    void stratifiedSplitKeepsTheClassBalance() {
        int[] y = new int[1000];
        for (int i = 0; i < 200; i++) {
            y[i] = 1;
        }
        int[] parts = ModelTrainer.stratifiedAssignment(y, 5, new java.util.Random(3));
        for (int k = 0; k < 5; k++) {
            int pos = 0;
            int all = 0;
            for (int i = 0; i < y.length; i++) {
                if (parts[i] == k) {
                    all++;
                    pos += y[i];
                }
            }
            assertThat(all).isEqualTo(200);
            assertThat(pos).isEqualTo(40);
        }
    }

    @Test
    void refusesUnusableData() {
        assertThatThrownBy(() -> ModelTrainer.train(PlacementDataset.synthetic(20, 1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        PlacementDataset allPlaced = new PlacementDataset(java.util.Collections.nCopies(60, new double[] {0.8, 1, 1, 5, 5}),
                new int[60], "x", true);
        assertThatThrownBy(() -> ModelTrainer.train(allPlaced, 1)).hasMessageContaining("both");
    }

    // ---- features, contributions and clamping ----

    @Test
    void featuresUseTheCgpaShareOfItsScale() {
        PlacementFeatures f = PlacementFeatures.of(new ProfileSnapshot(8.0, 10.0, 2, 3, 0, 0, 0, 0, 7, 4, 100, List.of()));
        assertThat(f.toArray()).containsExactly(0.8, 2, 1, 7, 4);
    }

    @Test
    void outOfRangeInputsAreClampedAndFlagged() {
        PlacementFeatures extreme = PlacementFeatures.of(profile(4.0, 40, 1, 10, 10));
        PlacementFeatures edge = new PlacementFeatures(1.0, model.max()[1], 1, 10, 10);
        assertThat(model.outsideTrainingRange(extreme)).isTrue();
        assertThat(model.probability(extreme)).isEqualTo(model.probability(edge));
        assertThat(model.outsideTrainingRange(PlacementFeatures.of(profile(3.0, 2, 1, 6, 5)))).isFalse();
    }

    @Test
    void contributionsAddUpToTheLogit() {
        PlacementFeatures f = PlacementFeatures.of(profile(3.1, 2, 0, 7, 3));
        double sum = model.bias() + model.contributions(f).stream().mapToDouble(PlacementModel.Contribution::logOdds).sum();
        assertThat(sum).isCloseTo(model.logit(f), within(1e-9));
        List<PlacementModel.Contribution> cs = model.contributions(f);
        for (int i = 1; i < cs.size(); i++) {
            assertThat(Math.abs(cs.get(i - 1).logOdds())).isGreaterThanOrEqualTo(Math.abs(cs.get(i).logOdds()));
        }
    }

    // ---- persistence and loading ----

    @Test
    void modelSurvivesAJsonRoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("m.json");
        ModelStore.write(model, file);
        PlacementModel back = ModelStore.read(file);
        PlacementFeatures f = PlacementFeatures.of(profile(3.3, 3, 1, 7, 6));
        assertThat(back.probability(f)).isEqualTo(model.probability(f));
        assertThat(back.test().rocAuc()).isEqualTo(model.test().rocAuc());
        assertThat(Files.readString(file)).doesNotContain("@").contains(PlacementModel.FORMAT);
    }

    @Test
    void wrongFormatOrFeaturesAreRejected(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.json");
        ModelStore.write(model, file);
        Files.writeString(file, Files.readString(file).replace(PlacementModel.FORMAT, "other-format"));
        assertThatThrownBy(() -> ModelStore.read(file)).hasStackTraceContaining("Unsupported model format");
    }

    @Test
    void bundledModelLoadsAndIsLabelled() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(ModelStore.CLASSPATH_LOCATION)) {
            assertThat(in).as("bundled model").isNotNull();
            PlacementModel bundled = ModelStore.read(in);
            assertThat(bundled.features()).isEqualTo(PlacementFeatures.NAMES);
            assertThat(bundled.dataSource()).isNotBlank();
        }
        assertThat(new PlacementModelService("").isAvailable()).isTrue();
    }

    @Test
    void serviceWithoutAModelHidesTheEstimate() {
        PlacementModelService none = new PlacementModelService("does/not/exist.json");
        assertThat(none.isAvailable()).isFalse();
        assertThat(none.estimate(profile(3.0, 2, 1, 6, 5))).isEmpty();
    }

    @Test
    void estimateBandsAndDescriptions() {
        PlacementModelService svc = new PlacementModelService(model);
        PlacementModelService.PlacementEstimate e = svc.estimate(profile(3.9, 5, 1, 9, 8)).orElseThrow();
        assertThat(e.percent()).isBetween(0, 100);
        assertThat(e.band()).isIn("Low", "Moderate", "High");
        assertThat(e.contributions()).hasSize(PlacementFeatures.COUNT);
        assertThat(e.describe(e.contributions().get(0))).contains("the estimate");
    }

    // ---- CSV loading ----

    @Test
    void loadsAKaggleStyleCsvWithAliasesAndIgnoresOtherColumns(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("placement.csv");
        Files.writeString(csv, "﻿College_ID,IQ,CGPA,Internship_Experience,Extra_Curricular_Score,Communication_Skills,Projects_Completed,Placement\n"
                + "C1,110,8.5,Yes,7,8,3,Yes\n"
                + "C2,95,6.0,No,2,4,1,No\n"
                + "C3,100,,No,2,4,1,No\n"                // missing CGPA: skipped
                + "C4,101,\"7,2\",No,2,4,1,No\n"         // unreadable number: skipped
                + "C5,120,9.6,Yes,9,9,5,Placed\n");
        PlacementDataset d = PlacementDataset.load(csv);
        assertThat(d.size()).isEqualTo(3);
        assertThat(d.rows().get(0)).containsExactly(0.85, 3, 1, 8, 7);
        assertThat(d.labels()).containsExactly(1, 0, 1);
        assertThat(d.synthetic()).isFalse();
        assertThat(d.source()).contains("CGPA scale 10");
    }

    @Test
    void explicitCgpaScaleIsUsedForLeakFreeTrainingPreprocessing(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("placement.csv");
        Files.writeString(csv, "CGPA,Projects,Internships,Communication,Extra_Curricular_Score,Placed\n"
                + "3.9,1,No,5,5,Yes\n");

        PlacementDataset data = PlacementDataset.load(csv, 10.0);

        assertThat(data.rows().get(0)[0]).isCloseTo(0.39, within(1e-12));
        assertThat(data.source()).contains("CGPA scale 10");
    }

    @Test
    void missingColumnIsReportedClearly(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("x.csv");
        Files.writeString(csv, "cgpa,projects,internship,communication,placed\n3,1,yes,5,yes\n");
        assertThatThrownBy(() -> PlacementDataset.load(csv)).hasMessageContaining("extracurricular");
    }

    @Test
    void csvSplitterHandlesQuotes() {
        assertThat(PlacementDataset.splitCsv("a,\"b,c\",\"d \"\"e\"\"\",")).containsExactly("a", "b,c", "d \"e\"", "");
        assertThat(PlacementDataset.yesNo("Not Placed")).isZero();
        assertThat(PlacementDataset.yesNo("2")).isEqualTo(1);
        assertThat(PlacementDataset.yesNo("maybe")).isNull();
    }
}
