package com.navigation.backend.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.navigation.backend.model.Beacon;
import com.navigation.backend.model.Point;

/**
 * Proximity (Yakınlık) Tabanlı Konum Belirleme Servisi
 * 
 * En güçlü sinyale sahip beacon'ın konumunu kullanıcının konumu olarak kabul eder.
 * Basit ve güvenilir, özellikle oda bazlı navigasyon için idealdir.
 */
@Service
public class ProximityService {

    private final MapService mapService;

    // RSSI-Mesafe parametreleri (TrilaterationService ile aynı)
    private static final double TX_POWER = -59.0;
    private static final double BASE_PATH_LOSS_EXPONENT = 2.2;
    private static final int RSSI_NEAR_THRESHOLD = -60;
    private static final int RSSI_FAR_THRESHOLD = -80;
    private static final double MIN_DISTANCE = 0.5;
    private static final double MAX_DISTANCE = 15.0;
    private static final double DISTANCE_CALIBRATION_FACTOR = 1.15;
    private static final int MIN_VALID_RSSI = -90;

    // Koridor sınırları (piksel cinsinden)
    // Ana Koridor: yatay, x: 200-1650, y: 180-270
    private static final double MAIN_CORRIDOR_X_MIN = 200;
    private static final double MAIN_CORRIDOR_X_MAX = 1650;
    private static final double MAIN_CORRIDOR_Y_MIN = 180;
    private static final double MAIN_CORRIDOR_Y_MAX = 270;
    
    // Sol Koridor: dikey, x: 200-290, y: 270-700
    private static final double LEFT_CORRIDOR_X_MIN = 200;
    private static final double LEFT_CORRIDOR_X_MAX = 290;
    private static final double LEFT_CORRIDOR_Y_MIN = 270;
    private static final double LEFT_CORRIDOR_Y_MAX = 700;

    public ProximityService(MapService mapService) {
        this.mapService = mapService;
    }

    /**
     * En yakın beacon'ı bulur ve onun konumunu döner
     * @param beaconReadings Mobilden gelen beacon listesi
     * @return ProximityResult - konum ve en yakın beacon bilgisi
     */
    public ProximityResult calculateProximityLocation(List<Map<String, Object>> beaconReadings) {
        if (beaconReadings == null || beaconReadings.isEmpty()) {
            return new ProximityResult(null, null, null, 0);
        }

        String strongestBeaconId = null;
        int strongestRssi = Integer.MIN_VALUE;
        Beacon strongestBeacon = null;

        for (Map<String, Object> reading : beaconReadings) {
            // "beaconId", "id" veya "macAddress" alanını kontrol et
            String beaconId = (String) reading.getOrDefault("beaconId", 
                              reading.getOrDefault("id", reading.get("macAddress")));
            Integer rssi = getIntValue(reading.get("rssi"));

            if (beaconId == null || rssi == null) continue;

            // MAC adresini normalize et ve ters versiyonunu da dene
            Beacon beacon = findBeaconByMac(beaconId);
            
            if (beacon == null) {
                System.out.println("[ProximityService] Beacon bulunamadi: " + beaconId);
                continue;
            }

            // En güçlü sinyali bul (RSSI değeri -30'a yaklaştıkça güçlü)
            if (rssi > strongestRssi) {
                strongestRssi = rssi;
                strongestBeaconId = beacon.getUuid(); // Veritabanındaki UUID'yi kullan
                strongestBeacon = beacon;
            }
        }

        if (strongestBeacon == null) {
            return new ProximityResult(null, null, null, 0);
        }

        Point location = new Point(strongestBeacon.getX(), strongestBeacon.getY());
        String nearestRoom = mapService.getNearestRoom(strongestBeaconId);
        double estimatedDistance = rssiToDistance(strongestRssi);

        return new ProximityResult(location, strongestBeaconId, nearestRoom, estimatedDistance);
    }

    /**
     * MAC adresine göre beacon bulur (normal ve ters formatta dener)
     */
    private Beacon findBeaconByMac(String macAddress) {
        if (macAddress == null) return null;
        
        // 1. Direkt ara
        Beacon beacon = mapService.getBeacon(macAddress.toUpperCase());
        if (beacon != null) return beacon;
        
        // 2. Ters MAC formatını dene (08:92:72:87:8D:D6 -> D6:8D:87:72:92:08)
        String reversedMac = reverseMacAddress(macAddress);
        beacon = mapService.getBeacon(reversedMac);
        if (beacon != null) {
            System.out.println("[ProximityService] Beacon ters MAC ile bulundu: " + macAddress + " -> " + reversedMac);
            return beacon;
        }
        
        return null;
    }

    /**
     * MAC adresini tersine çevirir
     * Örn: 08:92:72:87:8D:D6 -> D6:8D:87:72:92:08
     */
    private String reverseMacAddress(String mac) {
        if (mac == null) return null;
        String[] parts = mac.toUpperCase().split(":");
        if (parts.length != 6) return mac.toUpperCase();
        
        StringBuilder reversed = new StringBuilder();
        for (int i = parts.length - 1; i >= 0; i--) {
            reversed.append(parts[i]);
            if (i > 0) reversed.append(":");
        }
        return reversed.toString();
    }

    /**
     * Birden fazla beacon'dan ağırlıklı ortalama ile daha hassas konum hesaplar
     * (Weighted Centroid - Proximity tabanlı ama biraz daha hassas)
     */
    public ProximityResult calculateWeightedProximity(List<Map<String, Object>> beaconReadings) {
        if (beaconReadings == null || beaconReadings.isEmpty()) {
            return new ProximityResult(null, null, null, 0);
        }

        double sumX = 0;
        double sumY = 0;
        double totalWeight = 0;
        
        String strongestBeaconId = null;
        int strongestRssi = Integer.MIN_VALUE;

        for (Map<String, Object> reading : beaconReadings) {
            // "beaconId", "id" veya "macAddress" alanını kontrol et
            String beaconId = (String) reading.getOrDefault("beaconId", 
                              reading.getOrDefault("id", reading.get("macAddress")));
            Integer rssi = getIntValue(reading.get("rssi"));

            if (beaconId == null || rssi == null) continue;

            // MAC adresini normalize et ve ters versiyonunu da dene
            Beacon beacon = findBeaconByMac(beaconId);
            if (beacon == null) continue;

            // RSSI'dan ağırlık hesapla (güçlü sinyal = yüksek ağırlık)
            // RSSI genelde -30 ile -100 arasında, -30'a yakın = güçlü
            double weight = Math.pow(10, (rssi + 100) / 20.0);

            sumX += beacon.getX() * weight;
            sumY += beacon.getY() * weight;
            totalWeight += weight;

            if (rssi > strongestRssi) {
                strongestRssi = rssi;
                strongestBeaconId = beacon.getUuid(); // Veritabanındaki UUID'yi kullan
            }
        }

        if (totalWeight == 0) {
            return new ProximityResult(null, null, null, 0);
        }

        double finalX = sumX / totalWeight;
        double finalY = sumY / totalWeight;

        // Konumu koridor sınırlarına kısıtla
        Point constrainedLocation = constrainToCorridor(new Point(finalX, finalY));

        String nearestRoom = mapService.getNearestRoom(strongestBeaconId);
        double estimatedDistance = rssiToDistance(strongestRssi);

        return new ProximityResult(constrainedLocation, strongestBeaconId, nearestRoom, estimatedDistance);
    }

    /**
     * Hesaplanan konumu koridor sınırlarına kısıtlar
     * Konum oda içindeyse en yakın koridor noktasına çeker
     */
    private Point constrainToCorridor(Point point) {
        if (point == null) return null;
        
        double x = point.getX();
        double y = point.getY();
        
        // Önce hangi koridora daha yakın olduğunu belirle
        boolean inMainCorridor = isInMainCorridor(x, y);
        boolean inLeftCorridor = isInLeftCorridor(x, y);
        
        // Zaten bir koridorda ise değiştirme
        if (inMainCorridor || inLeftCorridor) {
            return point;
        }
        
        // Koridor dışında - en yakın koridor noktasını bul
        Point nearestMain = getNearestPointInMainCorridor(x, y);
        Point nearestLeft = getNearestPointInLeftCorridor(x, y);
        
        double distToMain = distance(x, y, nearestMain.getX(), nearestMain.getY());
        double distToLeft = distance(x, y, nearestLeft.getX(), nearestLeft.getY());
        
        // Daha yakın koridora çek
        if (distToMain <= distToLeft) {
            System.out.println("[ProximityService] Konum koridor dışı, ana koridora çekildi: (" + 
                (int)x + "," + (int)y + ") -> (" + (int)nearestMain.getX() + "," + (int)nearestMain.getY() + ")");
            return nearestMain;
        } else {
            System.out.println("[ProximityService] Konum koridor dışı, sol koridora çekildi: (" + 
                (int)x + "," + (int)y + ") -> (" + (int)nearestLeft.getX() + "," + (int)nearestLeft.getY() + ")");
            return nearestLeft;
        }
    }
    
    private boolean isInMainCorridor(double x, double y) {
        return x >= MAIN_CORRIDOR_X_MIN && x <= MAIN_CORRIDOR_X_MAX &&
               y >= MAIN_CORRIDOR_Y_MIN && y <= MAIN_CORRIDOR_Y_MAX;
    }
    
    private boolean isInLeftCorridor(double x, double y) {
        return x >= LEFT_CORRIDOR_X_MIN && x <= LEFT_CORRIDOR_X_MAX &&
               y >= LEFT_CORRIDOR_Y_MIN && y <= LEFT_CORRIDOR_Y_MAX;
    }
    
    private Point getNearestPointInMainCorridor(double x, double y) {
        double clampedX = Math.max(MAIN_CORRIDOR_X_MIN, Math.min(x, MAIN_CORRIDOR_X_MAX));
        double clampedY = Math.max(MAIN_CORRIDOR_Y_MIN, Math.min(y, MAIN_CORRIDOR_Y_MAX));
        return new Point(clampedX, clampedY);
    }
    
    private Point getNearestPointInLeftCorridor(double x, double y) {
        double clampedX = Math.max(LEFT_CORRIDOR_X_MIN, Math.min(x, LEFT_CORRIDOR_X_MAX));
        double clampedY = Math.max(LEFT_CORRIDOR_Y_MIN, Math.min(y, LEFT_CORRIDOR_Y_MAX));
        return new Point(clampedX, clampedY);
    }
    
    private double distance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    /**
     * RSSI değerinden tahmini mesafe hesaplar (adaptif model)
     * @param rssi Sinyal gücü (dBm)
     * @return Metre cinsinden tahmini mesafe
     */
    private double rssiToDistance(int rssi) {
        // Adaptif Path Loss Exponent
        double pathLossExponent;
        
        if (rssi >= RSSI_NEAR_THRESHOLD) {
            pathLossExponent = BASE_PATH_LOSS_EXPONENT;
        } else if (rssi >= RSSI_FAR_THRESHOLD) {
            double ratio = (double)(RSSI_NEAR_THRESHOLD - rssi) / (RSSI_NEAR_THRESHOLD - RSSI_FAR_THRESHOLD);
            pathLossExponent = BASE_PATH_LOSS_EXPONENT + (ratio * 0.5);
        } else {
            pathLossExponent = BASE_PATH_LOSS_EXPONENT + 0.8;
        }
        
        // Path Loss Model
        double rawDistance = Math.pow(10, (TX_POWER - rssi) / (10 * pathLossExponent));
        double calibratedDistance = rawDistance * DISTANCE_CALIBRATION_FACTOR;
        
        return Math.max(MIN_DISTANCE, Math.min(calibratedDistance, MAX_DISTANCE));
    }

    private Integer getIntValue(Object value) {
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof Double) {
            return ((Double) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Proximity sonuç sınıfı
     */
    public static class ProximityResult {
        private final Point location;
        private final String nearestBeaconId;
        private final String nearestRoom;
        private final double estimatedDistance;

        public ProximityResult(Point location, String nearestBeaconId, String nearestRoom, double estimatedDistance) {
            this.location = location;
            this.nearestBeaconId = nearestBeaconId;
            this.nearestRoom = nearestRoom;
            this.estimatedDistance = estimatedDistance;
        }

        public Point getLocation() { return location; }
        public String getNearestBeaconId() { return nearestBeaconId; }
        public String getNearestRoom() { return nearestRoom; }
        public double getEstimatedDistance() { return estimatedDistance; }
        
        public boolean isValid() { return location != null; }
    }
}
