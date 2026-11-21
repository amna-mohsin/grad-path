package ai.gradpath.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An activity event shown on dashboards (profile saved, applied for a job, resume analysed ...). */
@Entity
@Table(name = "activity_log")
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    /** Optional account link; the log and actor-name snapshot survive account deletion. */
    @Column(name = "user_id")
    private Integer userId;
    @Column(name = "actor_name", length = 120, nullable = false)
    private String actorName = "";
    @Column(length = 80, nullable = false)
    private String action;
    @Column(length = 300, nullable = false)
    private String detail = "";
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = Csv.utcNow();

    public ActivityLog() { }

    public ActivityLog(Integer userId, String actorName, String action, String detail) {
        this.userId = userId;
        this.actorName = truncate(actorName, 120);
        this.action = truncate(action, 80);
        this.detail = truncate(detail, 300);
    }

    private static String truncate(String s, int max) {
        String v = s == null ? "" : s;
        return v.length() > max ? v.substring(0, max) : v;
    }

    public Integer getId() { return id; }
    public Integer getUserId() { return userId; }
    public String getActorName() { return actorName; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
