package com.navigation.backend.model;

import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "location_logs")
public class LocationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sessionId; // Kullanicinin oturum ID'si (Unique)

    private double x;
    private double y;

    private String zoneName; // "Sinif 101 Onu", "Koridor Girisi" vb.
    
    private String targetDestination; // Kullanicinin gitmek istedigi hedef (Opsiyonel)

    private LocalDateTime timestamp;

    public LocationLog() {
    }

    public LocationLog(String sessionId, double x, double y, String zoneName, String targetDestination) {
        this.sessionId = sessionId;
        this.x = x;
        this.y = y;
        this.zoneName = zoneName;
        this.targetDestination = targetDestination;
        this.timestamp = LocalDateTime.now();
    }

    // Getter ve Setterlar
    public Long getId() { return id; }
    public String getSessionId() { return sessionId; }
    public double getX() { return x; }
    public double getY() { return y; }
    public String getZoneName() { return zoneName; }
    public String getTargetDestination() { return targetDestination; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
