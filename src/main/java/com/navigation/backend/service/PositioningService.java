package com.navigation.backend.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.navigation.backend.model.Point;
import com.navigation.backend.service.ProximityService.ProximityResult;
import com.navigation.backend.service.TrilaterationService.TrilaterationResult;

/**
 * Ana Konum Belirleme Servisi
 * 
 * Proximity ve Trilateration servislerini yönetir.
 * Duruma göre en uygun yöntemi seçer veya hibrit yaklaşım kullanır.
 * Konum geçişlerini yumuşatma (smoothing) özelliği içerir.
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

    // ============== SMOOTHING PARAMETRELERİ ==============
    // Kullanıcı bazlı son konum bilgileri (userId -> LocationState)
    private final Map<String, LocationState> userLocationStates = new ConcurrentHashMap<>();
    
    // Maksimum hareket hızı (piksel/saniye) - İnsan yürüyüş hızı ~1.4 m/s = ~25 px/s (18 px/m)
    private static final double MAX_SPEED_PIXELS_PER_SEC = 40.0; // Biraz toleranslı
    
    // Smoothing faktörü (0-1 arası, 1'e yakın = daha az smoothing)
    private static final double SMOOTHING_FACTOR = 0.3; // Yeni konuma %30, eski konuma %70 ağırlık
    
    // Minimum hareket eşiği (bu kadar piksel değişmezse güncelleme yapma)
    private static final double MIN_MOVEMENT_THRESHOLD = 5.0;
    
    // Varsayılan kullanıcı ID'si (tek kullanıcı senaryosu için)
    private static final String DEFAULT_USER = "default";

    public PositioningService(ProximityService proximityService, TrilaterationService trilaterationService) {
        this.proximityService = proximityService;
        this.trilaterationService = trilaterationService;
    }

    /**
     * Varsayılan modla konum hesaplar
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings) {
        return calculateLocation(beaconReadings, defaultMode, DEFAULT_USER);
    }

    /**
     * Belirtilen modla konum hesaplar (varsayılan kullanıcı)
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings, PositioningMode mode) {
        return calculateLocation(beaconReadings, mode, DEFAULT_USER);
    }

    /**
     * Belirtilen mod ve kullanıcı için konum hesaplar
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings, PositioningMode mode, String userId) {
        if (beaconReadings == null || beaconReadings.isEmpty()) {
            return PositioningResult.empty("Beacon verisi yok");
        }

        PositioningResult rawResult;
        switch (mode) {
            case PROXIMITY:
                rawResult = calculateProximity(beaconReadings);
                break;
            
            case WEIGHTED:
                rawResult = calculateWeightedProximity(beaconReadings);
                break;
            
            case TRILATERATION:
                rawResult = calculateTrilateration(beaconReadings);
                break;
            
            case HYBRID:
            default:
                rawResult = calculateHybrid(beaconReadings);
                break;
        }

        // Smoothing uygula
        if (rawResult.isValid()) {
            return applySmoothingAndSpeedLimit(rawResult, userId);
        }
        
        return rawResult;
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

    // ============== SMOOTHING METODLARI ==============
    
    /**
     * Konum değişikliklerini yumuşatır ve maksimum hız sınırı uygular
     */
    private PositioningResult applySmoothingAndSpeedLimit(PositioningResult rawResult, String userId) {
        LocationState state = userLocationStates.computeIfAbsent(userId, k -> new LocationState());
        
        Point rawLocation = rawResult.getLocation();
        long currentTime = System.currentTimeMillis();
        
        // İlk konum ise direkt kaydet
        if (state.lastLocation == null) {
            state.lastLocation = rawLocation;
            state.lastUpdateTime = currentTime;
            return rawResult;
        }
        
        // Zaman farkı (saniye)
        double deltaTime = (currentTime - state.lastUpdateTime) / 1000.0;
        if (deltaTime < 0.1) deltaTime = 0.1; // Minimum 100ms
        
        double dx = rawLocation.getX() - state.lastLocation.getX();
        double dy = rawLocation.getY() - state.lastLocation.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        
        // Çok küçük hareket ise güncelleme yapma
        if (distance < MIN_MOVEMENT_THRESHOLD) {
            return new PositioningResult(
                state.lastLocation,
                rawResult.getMode(),
                rawResult.getConfidence(),
                rawResult.getNearestBeaconId(),
                rawResult.getNearestRoom(),
                rawResult.getEstimatedDistance()
            );
        }
        
        // Hız hesapla (piksel/saniye)
        double speed = distance / deltaTime;
        
        Point smoothedLocation;
        
        // Maksimum hız aşılıyorsa, hareketi sınırla
        if (speed > MAX_SPEED_PIXELS_PER_SEC) {
            // Maksimum mesafe = max hız * zaman
            double maxDistance = MAX_SPEED_PIXELS_PER_SEC * deltaTime;
            double ratio = maxDistance / distance;
            
            double newX = state.lastLocation.getX() + dx * ratio;
            double newY = state.lastLocation.getY() + dy * ratio;
            
            // Sonra smoothing uygula
            smoothedLocation = applyExponentialSmoothing(state.lastLocation, new Point(newX, newY));
            
            System.out.println("[PositioningService] Hız sınırı uygulandı: " + 
                String.format("%.1f px/s -> %.1f px/s", speed, MAX_SPEED_PIXELS_PER_SEC));
        } else {
            // Normal smoothing uygula
            smoothedLocation = applyExponentialSmoothing(state.lastLocation, rawLocation);
        }
        
        // State güncelle
        state.lastLocation = smoothedLocation;
        state.lastUpdateTime = currentTime;
        
        return new PositioningResult(
            smoothedLocation,
            rawResult.getMode(),
            rawResult.getConfidence(),
            rawResult.getNearestBeaconId(),
            rawResult.getNearestRoom(),
            rawResult.getEstimatedDistance()
        );
    }
    
    /**
     * Exponential Moving Average (EMA) ile smoothing
     * newLocation = alpha * rawLocation + (1 - alpha) * lastLocation
     */
    private Point applyExponentialSmoothing(Point lastLocation, Point rawLocation) {
        double smoothedX = SMOOTHING_FACTOR * rawLocation.getX() + (1 - SMOOTHING_FACTOR) * lastLocation.getX();
        double smoothedY = SMOOTHING_FACTOR * rawLocation.getY() + (1 - SMOOTHING_FACTOR) * lastLocation.getY();
        return new Point(smoothedX, smoothedY);
    }
    
    /**
     * Belirli bir kullanıcının konum state'ini sıfırla
     */
    public void resetUserLocationState(String userId) {
        userLocationStates.remove(userId);
    }
    
    /**
     * Tüm kullanıcıların konum state'lerini sıfırla
     */
    public void resetAllLocationStates() {
        userLocationStates.clear();
    }
    
    /**
     * Kullanıcı konum durumu (internal class)
     */
    private static class LocationState {
        Point lastLocation;
        long lastUpdateTime;
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

