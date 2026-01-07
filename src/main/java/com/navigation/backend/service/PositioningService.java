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
 * Aktif rota varsa konumu rota üzerine kilitler (snap to route).
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
    
    // Maksimum hareket hızı (piksel/saniye) - Hızlı yürüyüş ~2 m/s = ~36 px/s
    private static final double MAX_SPEED_PIXELS_PER_SEC = 90.0; // Artırıldı - daha akıcı
    
    // ===== NORMAL MOD (Rota yok - stabilite öncelikli) =====
    private static final double SMOOTHING_FACTOR_MOVING = 0.5;  // Hareket ederken
    private static final double SMOOTHING_FACTOR_STATIC = 0.15; // Dururken
    
    // ===== NAVİGASYON MODU (Rota var - hız öncelikli) =====
    private static final double NAV_SMOOTHING_FACTOR_MOVING = 0.75; // Çok hızlı tepki
    private static final double NAV_SMOOTHING_FACTOR_STATIC = 0.35; // Dururken bile responsive
    private static final double NAV_MIN_MOVEMENT_THRESHOLD = 4.0;   // Daha hassas hareket algılama
    
    // Hareket algılama eşiği (bu hızın üstünde = hareket ediyor)
    private static final double MOVEMENT_SPEED_THRESHOLD = 15.0; // px/s - düşürüldü, daha erken hareket algılama
    
    // Minimum hareket eşiği - JITTER FILTER (bu kadar piksel değişmezse güncelleme yapma)
    private static final double MIN_MOVEMENT_THRESHOLD = 6.0; // Düşürüldü - daha responsive
    
    // Jitter buffer - son N konumun ortalaması
    private static final int JITTER_BUFFER_SIZE = 2; // Düşürüldü - daha az gecikme
    
    // Varsayılan kullanıcı ID'si (tek kullanıcı senaryosu için)
    private static final String DEFAULT_USER = "default";

    // ============== SNAP TO ROUTE PARAMETRELERİ ==============
    // Kullanıcı bazlı aktif rotalar (userId -> Route waypoints)
    private final Map<String, List<Point>> userActiveRoutes = new ConcurrentHashMap<>();
    
    // Rotaya snap yapılacak maksimum mesafe (piksel)
    // Bu mesafeden uzaksa kullanıcı rotadan çıkmış sayılır
    private static final double SNAP_TO_ROUTE_THRESHOLD = 60.0; // ~3.3 metre
    
    // Snap to route özelliği açık/kapalı
    private boolean snapToRouteEnabled = true;

    public PositioningService(ProximityService proximityService, TrilaterationService trilaterationService) {
        this.proximityService = proximityService;
        this.trilaterationService = trilaterationService;
    }

    /**
     * Varsayılan modla konum hesaplar
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings) {
        return calculateLocation(beaconReadings, defaultMode, DEFAULT_USER, false);
    }

    /**
     * Belirtilen modla konum hesaplar (varsayılan kullanıcı)
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings, PositioningMode mode) {
        return calculateLocation(beaconReadings, mode, DEFAULT_USER, false);
    }
    
    /**
     * Belirtilen mod ve navigation mode ile konum hesaplar
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings, PositioningMode mode, boolean navigationMode) {
        return calculateLocation(beaconReadings, mode, DEFAULT_USER, navigationMode);
    }

    /**
     * Belirtilen mod ve kullanıcı için konum hesaplar (eski uyumluluk için)
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings, PositioningMode mode, String userId) {
        return calculateLocation(beaconReadings, mode, userId, false);
    }
    
    /**
     * Belirtilen mod, kullanıcı ve navigation mode için konum hesaplar
     * @param navigationMode Mobile'dan gelen explicit navigation mode flag'i
     */
    public PositioningResult calculateLocation(List<Map<String, Object>> beaconReadings, PositioningMode mode, String userId, boolean navigationMode) {
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

        // Smoothing ve Snap to Route uygula
        if (rawResult.isValid()) {
            // Explicit navigation mode VEYA aktif rota varsa navigation parametreleri kullan
            boolean useNavigationParams = navigationMode || (userActiveRoutes.containsKey(userId) && !userActiveRoutes.get(userId).isEmpty());
            PositioningResult smoothedResult = applySmoothingAndSpeedLimit(rawResult, userId, useNavigationParams);
            
            // Aktif rota varsa ve snap to route açıksa, konumu rotaya kilitle
            if (snapToRouteEnabled && userActiveRoutes.containsKey(userId)) {
                return applySnapToRoute(smoothedResult, userId);
            }
            
            return smoothedResult;
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
     * Eski metod - uyumluluk için
     */
    private PositioningResult applySmoothingAndSpeedLimit(PositioningResult rawResult, String userId) {
        boolean isNavigationMode = userActiveRoutes.containsKey(userId) && !userActiveRoutes.get(userId).isEmpty();
        return applySmoothingAndSpeedLimit(rawResult, userId, isNavigationMode);
    }
    
    /**
     * Konum değişikliklerini yumuşatır ve maksimum hız sınırı uygular
     * @param isNavigationMode Explicit navigation mode flag - mobile'dan veya rota varlığından
     */
    private PositioningResult applySmoothingAndSpeedLimit(PositioningResult rawResult, String userId, boolean isNavigationMode) {
        LocationState state = userLocationStates.computeIfAbsent(userId, k -> new LocationState());
        
        Point rawLocation = rawResult.getLocation();
        long currentTime = System.currentTimeMillis();
        
        // Navigasyon moduna göre parametreler seç
        double minMovementThreshold = isNavigationMode ? NAV_MIN_MOVEMENT_THRESHOLD : MIN_MOVEMENT_THRESHOLD;
        double smoothingMoving = isNavigationMode ? NAV_SMOOTHING_FACTOR_MOVING : SMOOTHING_FACTOR_MOVING;
        double smoothingStatic = isNavigationMode ? NAV_SMOOTHING_FACTOR_STATIC : SMOOTHING_FACTOR_STATIC;
        
        // İlk konum ise direkt kaydet
        if (state.lastLocation == null) {
            state.lastLocation = rawLocation;
            state.lastUpdateTime = currentTime;
            state.addToBuffer(rawLocation);
            return rawResult;
        }
        
        // Zaman farkı (saniye)
        double deltaTime = (currentTime - state.lastUpdateTime) / 1000.0;
        if (deltaTime < 0.05) deltaTime = 0.05; // Minimum 50ms - daha hızlı güncelleme
        
        double dx = rawLocation.getX() - state.lastLocation.getX();
        double dy = rawLocation.getY() - state.lastLocation.getY();
        double distance = Math.sqrt(dx * dx + dy * dy);
        
        // JITTER FILTER: Çok küçük hareket ise güncelleme yapma (titreme önleme)
        // Navigasyon modunda daha hassas
        if (distance < minMovementThreshold) {
            // Navigasyon modunda bile buffer'dan al ama daha az filtreleme
            Point returnLocation = isNavigationMode ? state.lastLocation : state.getBufferedLocation();
            return new PositioningResult(
                returnLocation,
                rawResult.getMode(),
                rawResult.getConfidence(),
                rawResult.getNearestBeaconId(),
                rawResult.getNearestRoom(),
                rawResult.getEstimatedDistance()
            );
        }
        
        // Hız hesapla (piksel/saniye)
        double speed = distance / deltaTime;
        
        // Adaptif smoothing: hareket ederken hızlı tepki, dururken stabil
        double smoothingFactor = (speed > MOVEMENT_SPEED_THRESHOLD) 
            ? smoothingMoving 
            : smoothingStatic;
        
        Point smoothedLocation;
        
        // Maksimum hız aşılıyorsa, hareketi sınırla
        if (speed > MAX_SPEED_PIXELS_PER_SEC) {
            // Maksimum mesafe = max hız * zaman
            double maxDistance = MAX_SPEED_PIXELS_PER_SEC * deltaTime;
            double ratio = maxDistance / distance;
            
            double newX = state.lastLocation.getX() + dx * ratio;
            double newY = state.lastLocation.getY() + dy * ratio;
            
            // Sonra smoothing uygula (hareket halinde = hızlı tepki)
            smoothedLocation = applyAdaptiveSmoothing(state.lastLocation, new Point(newX, newY), smoothingMoving);
        } else {
            // Adaptif smoothing uygula
            smoothedLocation = applyAdaptiveSmoothing(state.lastLocation, rawLocation, smoothingFactor);
        }
        
        // Navigasyon modunda jitter buffer kullanma - direkt sonucu al
        Point finalLocation;
        if (isNavigationMode) {
            finalLocation = smoothedLocation;
            state.clearBuffer(); // Buffer'ı temizle
        } else {
            // Normal modda jitter buffer kullan
            state.addToBuffer(smoothedLocation);
            finalLocation = state.getBufferedLocation();
        }
        
        // State güncelle
        state.lastLocation = finalLocation;
        state.lastUpdateTime = currentTime;
        
        return new PositioningResult(
            finalLocation,
            rawResult.getMode(),
            rawResult.getConfidence(),
            rawResult.getNearestBeaconId(),
            rawResult.getNearestRoom(),
            rawResult.getEstimatedDistance()
        );
    }
    
    /**
     * Adaptif Exponential Moving Average (EMA) ile smoothing
     * newLocation = alpha * rawLocation + (1 - alpha) * lastLocation
     * @param smoothingFactor Yüksek değer = hızlı tepki, düşük değer = stabil
     */
    private Point applyAdaptiveSmoothing(Point lastLocation, Point rawLocation, double smoothingFactor) {
        double smoothedX = smoothingFactor * rawLocation.getX() + (1 - smoothingFactor) * lastLocation.getX();
        double smoothedY = smoothingFactor * rawLocation.getY() + (1 - smoothingFactor) * lastLocation.getY();
        return new Point(smoothedX, smoothedY);
    }

    // ============== SNAP TO ROUTE METODLARI ==============
    
    /**
     * Konumu aktif rota üzerine kilitler (snap to route)
     * Kullanıcı rotada yürürken sağa sola sapmaları önler
     */
    private PositioningResult applySnapToRoute(PositioningResult result, String userId) {
        List<Point> route = userActiveRoutes.get(userId);
        if (route == null || route.size() < 2) {
            return result;
        }
        
        Point currentLocation = result.getLocation();
        
        // En yakın rota segmentini ve o segment üzerindeki noktayı bul
        SnapResult snapResult = findNearestPointOnRoute(currentLocation, route);
        
        // Eğer rotaya çok uzaksa (eşik aşıldı), snap yapma - kullanıcı rotadan çıkmış
        if (snapResult.distance > SNAP_TO_ROUTE_THRESHOLD) {
            System.out.println("[PositioningService] Kullanıcı rotadan çıkmış: " + 
                String.format("%.1f px > %.1f px eşik", snapResult.distance, SNAP_TO_ROUTE_THRESHOLD));
            return result; // Orijinal konumu döndür
        }
        
        System.out.println("[PositioningService] Snap to route: (" + 
            (int)currentLocation.getX() + "," + (int)currentLocation.getY() + ") -> (" +
            (int)snapResult.snappedPoint.getX() + "," + (int)snapResult.snappedPoint.getY() + 
            ") mesafe=" + String.format("%.1f", snapResult.distance) + "px");
        
        return new PositioningResult(
            snapResult.snappedPoint,
            result.getMode(),
            result.getConfidence(),
            result.getNearestBeaconId(),
            result.getNearestRoom(),
            result.getEstimatedDistance()
        );
    }
    
    /**
     * Rota üzerindeki en yakın noktayı bulur
     * Her segment (çizgi parçası) için hesaplar ve en yakınını döner
     */
    private SnapResult findNearestPointOnRoute(Point location, List<Point> route) {
        double minDistance = Double.MAX_VALUE;
        Point nearestPoint = route.get(0);
        
        // Her segment için kontrol et
        for (int i = 0; i < route.size() - 1; i++) {
            Point segmentStart = route.get(i);
            Point segmentEnd = route.get(i + 1);
            
            // Bu segment üzerindeki en yakın noktayı bul
            Point pointOnSegment = getNearestPointOnSegment(location, segmentStart, segmentEnd);
            double distance = distanceBetween(location, pointOnSegment);
            
            if (distance < minDistance) {
                minDistance = distance;
                nearestPoint = pointOnSegment;
            }
        }
        
        return new SnapResult(nearestPoint, minDistance);
    }
    
    /**
     * Bir çizgi segmenti üzerindeki en yakın noktayı hesaplar
     * Matematiksel projeksiyon kullanır
     */
    private Point getNearestPointOnSegment(Point p, Point segmentStart, Point segmentEnd) {
        double x1 = segmentStart.getX();
        double y1 = segmentStart.getY();
        double x2 = segmentEnd.getX();
        double y2 = segmentEnd.getY();
        double px = p.getX();
        double py = p.getY();
        
        // Segment vektörü
        double dx = x2 - x1;
        double dy = y2 - y1;
        
        // Segment uzunluğunun karesi
        double segmentLengthSquared = dx * dx + dy * dy;
        
        // Segment çok kısa ise başlangıç noktasını döndür
        if (segmentLengthSquared < 0.0001) {
            return segmentStart;
        }
        
        // Projeksiyon parametresi t (0-1 arası segment üzerinde)
        double t = ((px - x1) * dx + (py - y1) * dy) / segmentLengthSquared;
        
        // t'yi 0-1 arasında sınırla (segment dışına çıkmasın)
        t = Math.max(0, Math.min(1, t));
        
        // Segment üzerindeki nokta
        double nearestX = x1 + t * dx;
        double nearestY = y1 + t * dy;
        
        return new Point(nearestX, nearestY);
    }
    
    /**
     * İki nokta arası mesafe
     */
    private double distanceBetween(Point p1, Point p2) {
        double dx = p2.getX() - p1.getX();
        double dy = p2.getY() - p1.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }
    
    /**
     * Snap sonucu (internal class)
     */
    private static class SnapResult {
        final Point snappedPoint;
        final double distance;
        
        SnapResult(Point snappedPoint, double distance) {
            this.snappedPoint = snappedPoint;
            this.distance = distance;
        }
    }
    
    // ============== ROTA YÖNETİM METODLARI ==============
    
    /**
     * Kullanıcı için aktif rota ayarlar
     * @param userId Kullanıcı ID (session ID)
     * @param route Rota waypoint listesi
     */
    public void setActiveRoute(String userId, List<Point> route) {
        if (route != null && route.size() >= 2) {
            userActiveRoutes.put(userId, route);
            System.out.println("[PositioningService] Aktif rota ayarlandı: " + userId + 
                " (" + route.size() + " waypoint)");
        }
    }
    
    /**
     * Varsayılan kullanıcı için aktif rota ayarlar
     */
    public void setActiveRoute(List<Point> route) {
        setActiveRoute(DEFAULT_USER, route);
    }
    
    /**
     * Kullanıcının aktif rotasını temizler
     */
    public void clearActiveRoute(String userId) {
        userActiveRoutes.remove(userId);
        System.out.println("[PositioningService] Aktif rota temizlendi: " + userId);
    }
    
    /**
     * Varsayılan kullanıcının aktif rotasını temizler
     */
    public void clearActiveRoute() {
        clearActiveRoute(DEFAULT_USER);
    }
    
    /**
     * Kullanıcının aktif rotası var mı?
     */
    public boolean hasActiveRoute(String userId) {
        return userActiveRoutes.containsKey(userId);
    }
    
    /**
     * Snap to route özelliğini aç/kapat
     */
    public void setSnapToRouteEnabled(boolean enabled) {
        this.snapToRouteEnabled = enabled;
        System.out.println("[PositioningService] Snap to route: " + (enabled ? "AÇIK" : "KAPALI"));
    }
    
    /**
     * Snap to route özelliği açık mı?
     */
    public boolean isSnapToRouteEnabled() {
        return snapToRouteEnabled;
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
     * Jitter buffer ile titreme önleme
     */
    private static class LocationState {
        Point lastLocation;
        long lastUpdateTime;
        // Jitter buffer - son konumları tutar
        java.util.LinkedList<Point> locationBuffer = new java.util.LinkedList<>();
        
        void addToBuffer(Point p) {
            locationBuffer.addLast(p);
            if (locationBuffer.size() > JITTER_BUFFER_SIZE) {
                locationBuffer.removeFirst();
            }
        }
        
        Point getBufferedLocation() {
            if (locationBuffer.isEmpty()) return lastLocation;
            double sumX = 0, sumY = 0;
            for (Point p : locationBuffer) {
                sumX += p.getX();
                sumY += p.getY();
            }
            return new Point(sumX / locationBuffer.size(), sumY / locationBuffer.size());
        }
        
        void clearBuffer() {
            locationBuffer.clear();
        }
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

