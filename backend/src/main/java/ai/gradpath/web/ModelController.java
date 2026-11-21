package ai.gradpath.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import ai.gradpath.ml.ModelTrainer;
import ai.gradpath.ml.PlacementFeatures;
import ai.gradpath.ml.PlacementModel;
import ai.gradpath.service.PlacementModelService;

/** "About the Model": how the placement model was trained, how well it performs and what its limits are. */
@Controller
public class ModelController {

    private final PlacementModelService models;

    public ModelController(PlacementModelService models) {
        this.models = models;
    }

    /** One row of the logistic-regression coefficient table. */
    public record FeatureRow(String label, String key, double mean, double scale, double weight, double oddsRatio) { }

    /** One row of the decision-tree feature-importance table. */
    public record ImportanceRow(String label, String key, double importance) { }

    @GetMapping("/model")
    public String page(Model model) {
        model.addAttribute("available", models.isAvailable());
        model.addAttribute("modelDisclaimer", PlacementModelService.DISCLAIMER);
        models.model().ifPresent(m -> {
            List<FeatureRow> rows = new ArrayList<>();
            List<ImportanceRow> importanceRows = new ArrayList<>();
            boolean logistic = PlacementModel.LOGISTIC_REGRESSION.equals(m.algorithm());
            for (int j = 0; j < PlacementFeatures.COUNT; j++) {
                if (logistic) {
                    rows.add(new FeatureRow(PlacementFeatures.LABELS.get(j), m.features().get(j), m.mean()[j],
                            m.scale()[j], m.weights()[j], Math.round(m.oddsRatio(j) * 1000) / 1000.0));
                } else {
                    importanceRows.add(new ImportanceRow(PlacementFeatures.LABELS.get(j), m.features().get(j),
                            Math.round(m.featureImportance()[j] * 1000) / 1000.0));
                }
            }
            model.addAttribute("m", m);
            model.addAttribute("modelName", m.algorithmName());
            model.addAttribute("logisticModel", logistic);
            model.addAttribute("featureRows", rows);
            model.addAttribute("importanceRows", importanceRows);
            model.addAttribute("lambdas", ModelTrainer.LAMBDAS);
            model.addAttribute("rocChart", ChartFactory.rocCurve(m));
            if (logistic) {
                model.addAttribute("coefChart", ChartFactory.coefficients(m));
            } else {
                model.addAttribute("importanceChart", ChartFactory.featureImportance(m));
            }
            model.addAttribute("calibrationChart", ChartFactory.calibration(m));
        });
        return "model";
    }
}
