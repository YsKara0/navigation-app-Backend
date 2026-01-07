package com.navigation.backend.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.navigation.backend.model.Beacon;
import com.navigation.backend.model.Point;

/**
 * Trilateration (Üçgenleme) Tabanlı Konum Belirleme Servisi
 * 
 * En az 3 beacon'dan gelen sinyal güçleri kullanılarak
 * kullanıcının kesin konumu hesaplanır.
 */
@Service
public class TrilaterationService {

    private final MapService mapService;

    // ============== RSSI-MESAFE DÖNÜŞÜM PARAMETRELERİ ==============
    // Bu değerler ortama göre kalibre edilebilir
    
    // 1 metredeki referans RSSI (beacon üreticisine göre -55 ile -65 arası)
    private static final double TX_POWER = -59.0;
    
    // Temel path loss exponent (iç mekan: 2.0-4.0)
    // Düşük değer = sinyal daha az zayıflar (açık alan)
    // Yüksek değer = sinyal daha çok zayıflar (duvar, engel)
    private static final double BASE_PATH_LOSS_EXPONENT = 2.2;
    
    // Adaptif path loss için RSSI eşikleri
    private static final int RSSI_NEAR_THRESHOLD = -60;   // Yakın mesafe
    private static final int RSSI_FAR_THRESHOLD = -80;    // Uzak mesafe
    
    // Mesafe sınırları (metre)
    private static final double MIN_DISTANCE = 0.5;   // Minimum mesafe (çok yakın sinyaller için)
    private static final double MAX_DISTANCE = 15.0;  // Maximum mesafe (çok zayıf sinyaller için)
    
    // RSSI filtreleme
    private static final int MIN_VALID_RSSI = -90;    // Bu değerden düşük RSSI'lar dikkate alınmaz
    
    // Piksel/Metre oranı
    private static final double PIXELS_PER_METER = 18.0;
    
    // Kalibrasyon faktörü (gerekirse ayarlanır, 1.0 = değişiklik yok)
    // >1.0 = hesaplanan mesafeyi artır, <1.0 = azalt
    private static final double DISTANCE_CALIBRATION_FACTOR = 1.15;

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

    public TrilaterationService(MapService mapService) {
        this.mapService = mapService;
    }

    /**
     * Trilateration ile konum hesaplar
     * @param beaconReadings Mobilden gelen beacon listesi [{id, rssi}, ...]
     * @return TrilaterationResult - hesaplanan konum ve güvenilirlik bilgisi
     */
    public TrilaterationResult calculateLocation(List<Map<String, Object>> beaconReadings) {
        if (beaconReadings == null || beaconReadings.size() < 3) {
            return new TrilaterationResult(null, 0, "Yetersiz beacon sayısı (min 3 gerekli)");
        }

        // Beacon verilerini işle ve en güçlü 3-5 tanesini seç
        List<BeaconReading> readings = parseAndSortReadings(beaconReadings);
        
        if (readings.size() < 3) {
            return new TrilaterationResult(null, 0, "Geçerli beacon sayısı yetersiz");
        }

        // En güçlü 3 beacon ile hesapla
        List<BeaconReading> topThree = readings.subList(0, Math.min(3, readings.size()));
        
        Point location = trilaterateThreeBeacons(
            topThree.get(0).beacon, topThree.get(0).distance,
            topThree.get(1).beacon, topThree.get(1).distance,
            topThree.get(2).beacon, topThree.get(2).distance
        );

        // Daha fazla beacon varsa, Weighted Least Squares ile iyileştir
        if (readings.size() > 3) {
            location = refineWithWeightedLeastSquares(location, readings);
        }

        // Konumu koridor sınırlarına kısıtla
        location = constrainToCorridor(location);

        // Güvenilirlik hesapla (0-1 arası)
        double confidence = calculateConfidence(readings);

        return new TrilaterationResult(location, confidence, "Başarılı");
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
            System.out.println("[TrilaterationService] Konum koridor dışı, ana koridora çekildi: (" + 
                (int)x + "," + (int)y + ") -> (" + (int)nearestMain.getX() + "," + (int)nearestMain.getY() + ")");
            return nearestMain;
        } else {
            System.out.println("[TrilaterationService] Konum koridor dışı, sol koridora çekildi: (" + 
                (int)x + "," + (int)y + ") -> (" + (int)nearestLeft.getX() + "," + (int)nearestLeft.getY() + ")");
            return nearestLeft;
        }
    }
    
    /**
     * Nokta ana koridor içinde mi?
     */
    private boolean isInMainCorridor(double x, double y) {
        return x >= MAIN_CORRIDOR_X_MIN && x <= MAIN_CORRIDOR_X_MAX &&
               y >= MAIN_CORRIDOR_Y_MIN && y <= MAIN_CORRIDOR_Y_MAX;
    }
    
    /**
     * Nokta sol koridor içinde mi?
     */
    private boolean isInLeftCorridor(double x, double y) {
        return x >= LEFT_CORRIDOR_X_MIN && x <= LEFT_CORRIDOR_X_MAX &&
               y >= LEFT_CORRIDOR_Y_MIN && y <= LEFT_CORRIDOR_Y_MAX;
    }
    
    /**
     * Ana koridordaki en yakın noktayı bul
     */
    private Point getNearestPointInMainCorridor(double x, double y) {
        double clampedX = Math.max(MAIN_CORRIDOR_X_MIN, Math.min(x, MAIN_CORRIDOR_X_MAX));
        double clampedY = Math.max(MAIN_CORRIDOR_Y_MIN, Math.min(y, MAIN_CORRIDOR_Y_MAX));
        return new Point(clampedX, clampedY);
    }
    
    /**
     * Sol koridordaki en yakın noktayı bul
     */
    private Point getNearestPointInLeftCorridor(double x, double y) {
        double clampedX = Math.max(LEFT_CORRIDOR_X_MIN, Math.min(x, LEFT_CORRIDOR_X_MAX));
        double clampedY = Math.max(LEFT_CORRIDOR_Y_MIN, Math.min(y, LEFT_CORRIDOR_Y_MAX));
        return new Point(clampedX, clampedY);
    }
    
    /**
     * İki nokta arası mesafe
     */
    private double distance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    /**
     * 3 beacon ile basit trilateration
     * Dairelerin kesişim noktasını bulur
     */
    private Point trilaterateThreeBeacons(
            Beacon b1, double d1,
            Beacon b2, double d2,
            Beacon b3, double d3) {
        
        // Beacon koordinatları (piksel cinsinden)
        double x1 = b1.getX(), y1 = b1.getY();
        double x2 = b2.getX(), y2 = b2.getY();
        double x3 = b3.getX(), y3 = b3.getY();

        // Mesafeleri piksele çevir (PIXELS_PER_METER px/m)
        double r1 = d1 * PIXELS_PER_METER;
        double r2 = d2 * PIXELS_PER_METER;
        double r3 = d3 * PIXELS_PER_METER;

        // Trilateration formülleri
        // İki denklem çözerek x ve y bulunur
        double A = 2 * (x2 - x1);
        double B = 2 * (y2 - y1);
        double C = r1 * r1 - r2 * r2 - x1 * x1 + x2 * x2 - y1 * y1 + y2 * y2;
        
        double D = 2 * (x3 - x2);
        double E = 2 * (y3 - y2);
        double F = r2 * r2 - r3 * r3 - x2 * x2 + x3 * x3 - y2 * y2 + y3 * y3;

        // Determinant kontrolü (paralel çizgiler)
        double det = A * E - B * D;
        if (Math.abs(det) < 0.0001) {
            // Çözüm yok, ağırlıklı ortalama kullan
            return weightedCentroid(b1, d1, b2, d2, b3, d3);
        }

        double x = (C * E - F * B) / det;
        double y = (A * F - D * C) / det;

        return new Point(x, y);
    }

    /**
     * Çözüm bulunamadığında ağırlıklı merkez hesaplar
     */
    private Point weightedCentroid(Beacon b1, double d1, Beacon b2, double d2, Beacon b3, double d3) {
        double w1 = 1.0 / Math.max(d1, 0.1);
        double w2 = 1.0 / Math.max(d2, 0.1);
        double w3 = 1.0 / Math.max(d3, 0.1);
        double total = w1 + w2 + w3;

        double x = (b1.getX() * w1 + b2.getX() * w2 + b3.getX() * w3) / total;
        double y = (b1.getY() * w1 + b2.getY() * w2 + b3.getY() * w3) / total;

        return new Point(x, y);
    }

    /**
     * Weighted Least Squares ile konum iyileştirme
     * 4+ beacon varsa daha hassas sonuç verir
     */
    private Point refineWithWeightedLeastSquares(Point initial, List<BeaconReading> readings) {
        double x = initial.getX();
        double y = initial.getY();

        // Gradient descent benzeri iteratif iyileştirme
        for (int iteration = 0; iteration < 10; iteration++) {
            double sumX = 0, sumY = 0, sumWeight = 0;

            for (BeaconReading reading : readings) {
                double bx = reading.beacon.getX();
                double by = reading.beacon.getY();
                double expectedDist = reading.distance * PIXELS_PER_METER; // metre -> piksel

                double actualDist = Math.sqrt(Math.pow(x - bx, 2) + Math.pow(y - by, 2));
                if (actualDist < 1) actualDist = 1;

                // Hata oranına göre ağırlık
                double error = Math.abs(expectedDist - actualDist);
                double weight = 1.0 / (1.0 + error / 100);

                // Hedef noktaya doğru çek
                double targetX = bx + (x - bx) * (expectedDist / actualDist);
                double targetY = by + (y - by) * (expectedDist / actualDist);

                sumX += targetX * weight;
                sumY += targetY * weight;
                sumWeight += weight;
            }

            if (sumWeight > 0) {
                x = sumX / sumWeight;
                y = sumY / sumWeight;
            }
        }

        return new Point(x, y);
    }

    /**
     * Beacon okumalarını parse edip mesafeye göre sıralar
     */
    private List<BeaconReading> parseAndSortReadings(List<Map<String, Object>> beaconReadings) {
        List<BeaconReading> readings = new ArrayList<>();

        for (Map<String, Object> reading : beaconReadings) {
            // "beaconId", "id" veya "macAddress" alanını kontrol et
            String beaconId = (String) reading.getOrDefault("beaconId", 
                              reading.getOrDefault("id", reading.get("macAddress")));
            Integer rssi = getIntValue(reading.get("rssi"));

            if (beaconId == null || rssi == null) continue;
            
            // Çok zayıf sinyalleri filtrele
            if (rssi < MIN_VALID_RSSI) {
                System.out.println("[TrilaterationService] Sinyal çok zayıf, atlandı: " + beaconId + " RSSI=" + rssi);
                continue;
            }

            // MAC adresini normalize et ve ters versiyonunu da dene
            Beacon beacon = findBeaconByMac(beaconId);
            if (beacon == null) {
                System.out.println("[TrilaterationService] Beacon bulunamadi: " + beaconId);
                continue;
            }

            double distance = rssiToDistance(rssi);
            readings.add(new BeaconReading(beacon, beacon.getUuid(), rssi, distance));
            System.out.println("[TrilaterationService] Beacon: " + beacon.getUuid() + 
                " RSSI=" + rssi + " -> " + String.format("%.2f", distance) + "m");
        }

        // Mesafeye göre sırala (yakından uzağa)
        readings.sort(Comparator.comparingDouble(r -> r.distance));

        return readings;
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
            System.out.println("[TrilaterationService] Beacon ters MAC ile bulundu: " + macAddress + " -> " + reversedMac);
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
     * RSSI değerinden mesafe hesaplar (metre)
     * Adaptif Path Loss Model kullanır
     */
    private double rssiToDistance(int rssi) {
        // Adaptif Path Loss Exponent
        // Yakın mesafede sinyal daha stabil, uzakta daha değişken
        double pathLossExponent;
        
        if (rssi >= RSSI_NEAR_THRESHOLD) {
            // Yakın mesafe (0-3m): Düşük path loss
            pathLossExponent = BASE_PATH_LOSS_EXPONENT;
        } else if (rssi >= RSSI_FAR_THRESHOLD) {
            // Orta mesafe (3-8m): Kademeli artış
            double ratio = (double)(RSSI_NEAR_THRESHOLD - rssi) / (RSSI_NEAR_THRESHOLD - RSSI_FAR_THRESHOLD);
            pathLossExponent = BASE_PATH_LOSS_EXPONENT + (ratio * 0.5); // Max 2.7
        } else {
            // Uzak mesafe (8m+): Yüksek path loss
            pathLossExponent = BASE_PATH_LOSS_EXPONENT + 0.8; // ~3.0
        }
        
        // Path Loss Model: d = 10 ^ ((TxPower - RSSI) / (10 * n))
        double rawDistance = Math.pow(10, (TX_POWER - rssi) / (10 * pathLossExponent));
        
        // Kalibrasyon faktörü uygula
        double calibratedDistance = rawDistance * DISTANCE_CALIBRATION_FACTOR;
        
        // Mesafe sınırlarını uygula
        calibratedDistance = Math.max(MIN_DISTANCE, Math.min(calibratedDistance, MAX_DISTANCE));
        
        return calibratedDistance;
    }

    /**
     * Sonucun güvenilirliğini hesaplar
     */
    private double calculateConfidence(List<BeaconReading> readings) {
        if (readings.isEmpty()) return 0;

        // Faktörler:
        // 1. Beacon sayısı (3=0.6, 4=0.8, 5+=1.0)
        double beaconFactor = Math.min(readings.size() / 5.0, 1.0);

        // 2. En güçlü sinyalin kalitesi (-50'den güçlü = iyi)
        int strongestRssi = readings.get(0).rssi;
        double signalFactor = Math.min(Math.max((strongestRssi + 100) / 50.0, 0), 1.0);

        // 3. Beacon dağılımı (geometrik çeşitlilik)
        double spreadFactor = calculateSpreadFactor(readings);

        return (beaconFactor * 0.3 + signalFactor * 0.4 + spreadFactor * 0.3);
    }

    /**
     * Beacon'ların geometrik dağılımını hesaplar
     */
    private double calculateSpreadFactor(List<BeaconReading> readings) {
        if (readings.size() < 2) return 0;

        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

        for (BeaconReading r : readings) {
            minX = Math.min(minX, r.beacon.getX());
            maxX = Math.max(maxX, r.beacon.getX());
            minY = Math.min(minY, r.beacon.getY());
            maxY = Math.max(maxY, r.beacon.getY());
        }

        double spread = Math.sqrt(Math.pow(maxX - minX, 2) + Math.pow(maxY - minY, 2));
        // 100 piksel üzeri spread = iyi dağılım
        return Math.min(spread / 200.0, 1.0);
    }

    private Integer getIntValue(Object value) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Double) return ((Double) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /**
     * Beacon okuma verisi (internal)
     */
    private static class BeaconReading {
        final Beacon beacon;
        final String id;
        final int rssi;
        final double distance;

        BeaconReading(Beacon beacon, String id, int rssi, double distance) {
            this.beacon = beacon;
            this.id = id;
            this.rssi = rssi;
            this.distance = distance;
        }
    }

    /**
     * Trilateration sonuç sınıfı
     */
    public static class TrilaterationResult {
        private final Point location;
        private final double confidence;
        private final String message;

        public TrilaterationResult(Point location, double confidence, String message) {
            this.location = location;
            this.confidence = confidence;
            this.message = message;
        }

        public Point getLocation() { return location; }
        public double getConfidence() { return confidence; }
        public String getMessage() { return message; }
        public boolean isValid() { return location != null && confidence > 0.3; }
    }
}
