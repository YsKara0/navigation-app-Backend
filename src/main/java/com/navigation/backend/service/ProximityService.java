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
            // "beaconId" veya "id" alanını kontrol et
            String beaconId = (String) reading.getOrDefault("beaconId", reading.get("id"));
            Integer rssi = getIntValue(reading.get("rssi"));

            if (beaconId == null || rssi == null) continue;

            // En güçlü sinyali bul (RSSI değeri -30'a yaklaştıkça güçlü)
            if (rssi > strongestRssi) {
                Beacon beacon = mapService.getBeacon(beaconId);
                if (beacon != null) {
                    strongestRssi = rssi;
                    strongestBeaconId = beaconId;
                    strongestBeacon = beacon;
                }
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
            // "beaconId" veya "id" alanını kontrol et
            String beaconId = (String) reading.getOrDefault("beaconId", reading.get("id"));
            Integer rssi = getIntValue(reading.get("rssi"));

            if (beaconId == null || rssi == null) continue;

            Beacon beacon = mapService.getBeacon(beaconId);
            if (beacon == null) continue;

            // RSSI'dan ağırlık hesapla (güçlü sinyal = yüksek ağırlık)
            // RSSI genelde -30 ile -100 arasında, -30'a yakın = güçlü
            double weight = Math.pow(10, (rssi + 100) / 20.0);

            sumX += beacon.getX() * weight;
            sumY += beacon.getY() * weight;
            totalWeight += weight;

            if (rssi > strongestRssi) {
                strongestRssi = rssi;
                strongestBeaconId = beaconId;
            }
        }

        if (totalWeight == 0) {
            return new ProximityResult(null, null, null, 0);
        }

        double finalX = sumX / totalWeight;
        double finalY = sumY / totalWeight;

        Point location = new Point(finalX, finalY);
        String nearestRoom = mapService.getNearestRoom(strongestBeaconId);
        double estimatedDistance = rssiToDistance(strongestRssi);

        return new ProximityResult(location, strongestBeaconId, nearestRoom, estimatedDistance);
    }

    /**
     * RSSI değerinden tahmini mesafe hesaplar
     * @param rssi Sinyal gücü (dBm)
     * @return Metre cinsinden tahmini mesafe
     */
    private double rssiToDistance(int rssi) {
        // Path Loss Model: distance = 10 ^ ((TxPower - RSSI) / (10 * n))
        // TxPower: 1 metre mesafedeki RSSI (genellikle -59 dBm)
        // n: Ortam faktörü (2.0 açık alan, 2.5-4.0 iç mekan)
        double txPower = -59.0;
        double n = 2.5; // İç mekan için
        
        return Math.pow(10, (txPower - rssi) / (10 * n));
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
