package ai.gradpath.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A direct in-app message between a student and a career officer. */
@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    @Column(name = "sender_id", nullable = false)
    private Integer senderId;
    @Column(name = "recipient_id", nullable = false)
    private Integer recipientId;
    /** Optional contextual link; deleting the job clears this id but keeps the message. */
    @Column(name = "job_id")
    private Integer jobId;
    @Column(nullable = false, columnDefinition = "text")
    private String body;
    @Column(name = "is_read", nullable = false)
    private boolean read;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = Csv.utcNow();

    public Message() { }

    public Message(Integer senderId, Integer recipientId, String body, Integer jobId) {
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.body = body;
        this.jobId = jobId;
    }

    public Integer getId() { return id; }
    public Integer getSenderId() { return senderId; }
    public Integer getRecipientId() { return recipientId; }
    public Integer getJobId() { return jobId; }
    public String getBody() { return body; }
    public boolean isRead() { return read; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
