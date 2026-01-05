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

    // RSSI-Distance dönüşüm parametreleri
    private static final double TX_POWER = -59.0;  // 1m mesafedeki referans RSSI
    private static final double PATH_LOSS_EXPONENT = 2.5; // İç mekan için (2.0-4.0 arası)

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

        // Güvenilirlik hesapla (0-1 arası)
        double confidence = calculateConfidence(readings);

        return new TrilaterationResult(location, confidence, "Başarılı");
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

        // Mesafeleri piksele çevir (18 px/m)
        double r1 = d1 * 18;
        double r2 = d2 * 18;
        double r3 = d3 * 18;

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
                double expectedDist = reading.distance * 18; // metre -> piksel

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
            // "beaconId" veya "id" alanını kontrol et
            String beaconId = (String) reading.getOrDefault("beaconId", reading.get("id"));
            Integer rssi = getIntValue(reading.get("rssi"));

            if (beaconId == null || rssi == null) continue;

            Beacon beacon = mapService.getBeacon(beaconId);
            if (beacon == null) continue;

            double distance = rssiToDistance(rssi);
            readings.add(new BeaconReading(beacon, beaconId, rssi, distance));
        }

        // Mesafeye göre sırala (yakından uzağa)
        readings.sort(Comparator.comparingDouble(r -> r.distance));

        return readings;
    }

    /**
     * RSSI değerinden mesafe hesaplar (metre)
     */
    private double rssiToDistance(int rssi) {
        // Path Loss Model: d = 10 ^ ((TxPower - RSSI) / (10 * n))
        return Math.pow(10, (TX_POWER - rssi) / (10 * PATH_LOSS_EXPONENT));
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
