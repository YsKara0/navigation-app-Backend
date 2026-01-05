package com.navigation.backend.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.navigation.backend.model.Point;
import com.navigation.backend.service.ProximityService.ProximityResult;
import com.navigation.backend.service.TrilaterationService.TrilaterationResult;

/**
 * Ana Konum Belirleme Servisi
 * 
 * Proximity ve Trilateration servislerini yönetir.
 * Duruma göre en uygun yöntemi seçer veya hibrit yaklaşım kullanır.
 */
@Service
public class PositioningService {

    private final ProximityService proximityService;
    private final TrilaterationService trilaterationService;

    // Konum belirleme modu
    public enum PositioningMode {
        PROXIMITY,      // Sadece en yakın beacon
        TRILATERATION,  // Üçgenleme (3+ beacon gerekli)
        HYBRID,         // Otomatik seçim
        WEIGHTED        // Ağırlıklı proximity
    }

    // Varsayılan mod
    private PositioningMode defaultMode = PositioningMode.HYBRID;

    public PositioningService(ProximityService proximityService, TrilaterationService trilaterationService) {
        this.proximityService = proximityService;
        this.trilaterationService = trilaterationService;
    }

    /**
     * Varsayılan modla konum hesaplar
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings) {
        return calculateLocation(beaconReadings, defaultMode);
    }

    /**
     * Belirtilen modla konum hesaplar
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings, PositioningMode mode) {
        if (beaconReadings == null || beaconReadings.isEmpty()) {
            return PositioningResult.empty("Beacon verisi yok");
        }

        switch (mode) {
            case PROXIMITY:
                return calculateProximity(beaconReadings);
            
            case WEIGHTED:
                return calculateWeightedProximity(beaconReadings);
            
            case TRILATERATION:
                return calculateTrilateration(beaconReadings);
            
            case HYBRID:
            default:
                return calculateHybrid(beaconReadings);
        }
    }

    /**
     * Proximity tabanlı konum
     */
    private PositioningResult calculateProximity(List<Map<String, Object>> beaconReadings) {
        ProximityResult result = proximityService.calculateProximityLocation(beaconReadings);
        
        if (!result.isValid()) {
            return PositioningResult.empty("Proximity hesaplanamadı");
        }

        return new PositioningResult(
            result.getLocation(),
            PositioningMode.PROXIMITY,
            0.7, // Proximity güvenilirliği orta
            result.getNearestBeaconId(),
            result.getNearestRoom(),
            result.getEstimatedDistance()
        );
    }

    /**
     * Ağırlıklı Proximity
     */
    private PositioningResult calculateWeightedProximity(List<Map<String, Object>> beaconReadings) {
        ProximityResult result = proximityService.calculateWeightedProximity(beaconReadings);
        
        if (!result.isValid()) {
            return PositioningResult.empty("Weighted Proximity hesaplanamadı");
        }

        return new PositioningResult(
            result.getLocation(),
            PositioningMode.WEIGHTED,
            0.75,
            result.getNearestBeaconId(),
            result.getNearestRoom(),
            result.getEstimatedDistance()
        );
    }

    /**
     * Trilateration tabanlı konum
     */
    private PositioningResult calculateTrilateration(List<Map<String, Object>> beaconReadings) {
        TrilaterationResult result = trilaterationService.calculateLocation(beaconReadings);
        
        if (!result.isValid()) {
            // Fallback to proximity
            return calculateWeightedProximity(beaconReadings);
        }

        // En yakın beacon'ı bul (bilgi amaçlı)
        ProximityResult proximityInfo = proximityService.calculateProximityLocation(beaconReadings);

        return new PositioningResult(
            result.getLocation(),
            PositioningMode.TRILATERATION,
            result.getConfidence(),
            proximityInfo.getNearestBeaconId(),
            proximityInfo.getNearestRoom(),
            proximityInfo.getEstimatedDistance()
        );
    }

    /**
     * Hibrit yaklaşım - duruma göre en uygun yöntemi seçer
     */
    private PositioningResult calculateHybrid(List<Map<String, Object>> beaconReadings) {
        int beaconCount = beaconReadings.size();

        // 1 beacon: Sadece proximity
        if (beaconCount == 1) {
            return calculateProximity(beaconReadings);
        }

        // 2 beacon: Weighted proximity
        if (beaconCount == 2) {
            return calculateWeightedProximity(beaconReadings);
        }

        // 3+ beacon: Trilateration dene, başarısız olursa weighted proximity
        TrilaterationResult triResult = trilaterationService.calculateLocation(beaconReadings);
        
        if (triResult.isValid() && triResult.getConfidence() > 0.5) {
            ProximityResult proximityInfo = proximityService.calculateProximityLocation(beaconReadings);
            return new PositioningResult(
                triResult.getLocation(),
                PositioningMode.TRILATERATION,
                triResult.getConfidence(),
                proximityInfo.getNearestBeaconId(),
                proximityInfo.getNearestRoom(),
                proximityInfo.getEstimatedDistance()
            );
        }

        // Trilateration güvenilir değil, weighted proximity kullan
        return calculateWeightedProximity(beaconReadings);
    }

    /**
     * Varsayılan modu değiştir
     */
    public void setDefaultMode(PositioningMode mode) {
        this.defaultMode = mode;
    }

    public PositioningMode getDefaultMode() {
        return defaultMode;
    }

    /**
     * Konum belirleme sonuç sınıfı
     */
    public static class PositioningResult {
        private final Point location;
        private final PositioningMode mode;
        private final double confidence;
        private final String nearestBeaconId;
        private final String nearestRoom;
        private final double estimatedDistance;
        private final String errorMessage;

        public PositioningResult(Point location, PositioningMode mode, double confidence,
                                 String nearestBeaconId, String nearestRoom, double estimatedDistance) {
            this.location = location;
            this.mode = mode;
            this.confidence = confidence;
            this.nearestBeaconId = nearestBeaconId;
            this.nearestRoom = nearestRoom;
            this.estimatedDistance = estimatedDistance;
            this.errorMessage = null;
        }

        private PositioningResult(String errorMessage) {
            this.location = new Point(0, 0);
            this.mode = null;
            this.confidence = 0;
            this.nearestBeaconId = null;
            this.nearestRoom = null;
            this.estimatedDistance = 0;
            this.errorMessage = errorMessage;
        }

        public static PositioningResult empty(String errorMessage) {
            return new PositioningResult(errorMessage);
        }

        // Getters
        public Point getLocation() { return location; }
        public double getX() { return location != null ? location.getX() : 0; }
        public double getY() { return location != null ? location.getY() : 0; }
        public PositioningMode getMode() { return mode; }
        public double getConfidence() { return confidence; }
        public String getNearestBeaconId() { return nearestBeaconId; }
        public String getNearestRoom() { return nearestRoom; }
        public double getEstimatedDistance() { return estimatedDistance; }
        public String getErrorMessage() { return errorMessage; }
        public boolean isValid() { return location != null && confidence > 0; }
    }
}

