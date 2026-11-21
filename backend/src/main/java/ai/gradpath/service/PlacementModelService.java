package ai.gradpath.service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import ai.gradpath.domain.Student;
import ai.gradpath.ml.ModelStore;
import ai.gradpath.ml.PlacementFeatures;
import ai.gradpath.ml.PlacementModel;
import ai.gradpath.readiness.ProfileSnapshot;

/**
 * FACADE over the trained placement model: loads it once at start-up (SINGLETON bean) and turns a profile into a
 * {@link PlacementEstimate}. The estimate is secondary information - the 60-rule readiness score remains the
 * official score and alone drives categories and job fit. Without a model file the feature is simply hidden.
 */
@Service
public class PlacementModelService {

    private static final Logger log = LoggerFactory.getLogger(PlacementModelService.class);

    public static final String DISCLAIMER = "This estimate comes from a statistical model trained on past placement "
            + "data. It reflects patterns in that data, not your individual future, and it never replaces the "
            + "rule-based readiness score or a career officer's judgement.";

    private final PlacementModel model;

    @org.springframework.beans.factory.annotation.Autowired
    public PlacementModelService(@Value("${gradpath.ml.model-path:}") String modelPath) {
        this.model = load(modelPath);
    }

    /** For tests: use a given model (or none). */
    public PlacementModelService(PlacementModel model) {
        this.model = model;
    }

    private static PlacementModel load(String modelPath) {
        try {
            if (modelPath != null && !modelPath.isBlank()) {
                Path p = Path.of(modelPath);
                if (!Files.isRegularFile(p)) {
                    log.warn("Placement model file not found at the configured path; the estimate is disabled");
                    return null;
                }
                PlacementModel m = ModelStore.read(p);
                log.info("Loaded placement model {} from configured path", m.version());
                return m;
            }
            ClassPathResource res = new ClassPathResource(ModelStore.CLASSPATH_LOCATION);
            if (!res.exists()) {
                log.info("No bundled placement model; the estimate is disabled");
                return null;
            }
            try (InputStream in = res.getInputStream()) {
                PlacementModel m = ModelStore.read(in);
                log.info("Loaded bundled placement model {} ({}, test ROC-AUC {})", m.version(),
                        m.synthetic() ? "synthetic practice data" : "real data", m.test().rocAuc());
                return m;
            }
        } catch (Exception e) {
            log.warn("Could not load the placement model ({}); the estimate is disabled", e.getClass().getSimpleName());
            return null;
        }
    }

    public boolean isAvailable() {
        return model != null;
    }

    public Optional<PlacementModel> model() {
        return Optional.ofNullable(model);
    }

    public Optional<PlacementEstimate> estimate(Student s) {
        return estimate(ProfileSnapshot.of(s));
    }

    public Optional<PlacementEstimate> estimate(ProfileSnapshot snapshot) {
        if (model == null) {
            return Optional.empty();
        }
        PlacementFeatures f = PlacementFeatures.of(snapshot);
        double p = model.probability(f);
        return Optional.of(new PlacementEstimate(p, model.threshold(), model.placementRate(),
                model.contributions(f), model.outsideTrainingRange(f), model.synthetic(), model.algorithmName(),
                model.version()));
    }

    /**
     * One student's model output.
     *
     * @param probability     estimated probability of placement (0..1)
     * @param threshold       model decision threshold
     * @param baseRate        placement rate in the training data
     * @param contributions   per-feature effects, largest first
     * @param extrapolated    true when an input was outside the training range (clamped to its edge)
     * @param synthetic       true when the model was trained on practice data
     * @param algorithmName   selected classifier
     * @param modelVersion    model version
     */
    public record PlacementEstimate(double probability, double threshold, double baseRate,
                                    List<PlacementModel.Contribution> contributions, boolean extrapolated,
                                    boolean synthetic, String algorithmName, String modelVersion) {

        public int percent() {
            return (int) Math.round(probability * 100);
        }

        public String percentLabel() {
            return percent() + "%";
        }

        public String baseRateLabel() {
            return Math.round(baseRate * 100) + "%";
        }

        /** Low (< 1/3), Moderate, High (>= 2/3). */
        public String band() {
            return probability >= 2.0 / 3 ? "High" : probability >= 1.0 / 3 ? "Moderate" : "Low";
        }

        public String badge() {
            return switch (band()) {
                case "High" -> "success";
                case "Moderate" -> "info";
                default -> "warn";
            };
        }

        /** Plain-language description of one contribution. */
        public String describe(PlacementModel.Contribution c) {
            double v = c.logOdds();
            String size = Math.abs(v) < 0.15 ? "barely changes" : (Math.abs(v) < 0.6 ? (v > 0 ? "raises" : "lowers")
                    : (v > 0 ? "strongly raises" : "strongly lowers"));
            String comparison = " compared with an average student in the training data";
            if ("Decision tree".equals(algorithmName) || "Random forest".equals(algorithmName)) {
                comparison = " in a one-feature comparison with a typical training profile";
            }
            return String.format(Locale.ROOT, "%s %s the estimate%s", c.label(), size, comparison);
        }
    }
}
