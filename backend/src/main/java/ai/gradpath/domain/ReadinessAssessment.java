package ai.gradpath.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Database composition: a student's saved readiness results are deleted with the student via the foreign-key cascade.
 *
 * <p>Stored in the existing placement_predictions table: the "probability" column holds the 0-100 readiness score
 * (it is NOT a probability). Rows created by the earlier ML/demo scorer are flagged {@code is_legacy} and are never
 * presented as rule-engine results.
 */
@Entity
@Table(name = "placement_predictions")
public class ReadinessAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(name = "student_id", nullable = false)
    private Integer studentId;
    @Column(name = "probability", nullable = false)
    private double score;
    @Column(length = 40, nullable = false)
    private String category;
    @Column(name = "model_name", length = 80, nullable = false)
    private String engineName;
    @Column(name = "rule_engine_version", length = 40, nullable = false)
    private String ruleEngineVersion;
    @Column(nullable = false)
    private boolean preliminary;
    @Column(name = "is_legacy", nullable = false)
    private boolean legacy;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = Csv.utcNow();

    public ReadinessAssessment() { }

    public ReadinessAssessment(Integer studentId, double score, String category, String engineName,
                               String ruleEngineVersion, boolean preliminary) {
        this.studentId = studentId;
        this.score = score;
        this.category = category;
        this.engineName = engineName;
        this.ruleEngineVersion = ruleEngineVersion;
        this.preliminary = preliminary;
    }

    public Integer getId() { return id; }
    public Integer getStudentId() { return studentId; }
    public double getScore() { return score; }
    public String getCategory() { return category; }
    public String getEngineName() { return engineName; }
    public String getRuleEngineVersion() { return ruleEngineVersion; }
    public boolean isPreliminary() { return preliminary; }
    public boolean isLegacy() { return legacy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
