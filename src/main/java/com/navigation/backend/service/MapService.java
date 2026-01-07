package com.navigation.backend.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.navigation.backend.model.Beacon;
import com.navigation.backend.repository.BeaconRepository;

import jakarta.annotation.PostConstruct;

@Service
public class MapService {

    private final BeaconRepository beaconRepository;

    // Beacon ID -> En yakın oda eşleştirmesi
    private final Map<String, String> beaconToRoomMap = new HashMap<>();

    public MapService(BeaconRepository beaconRepository) {
        this.beaconRepository = beaconRepository;
    }

    @PostConstruct
    public void initDatabase() {
        // Eger veritabani bossa, ornek verileri yukle
        if (beaconRepository.count() == 0) {
            System.out.println("Veritabani bos, gercek beacon verileri yukleniyor...");
            
            // Gerçek RTLS Beacon MAC adresleri ve konumları (18 px/m)
            // Format: MAC adresi (MOBILE FORMATINDA - 08:92:72:87:XX:XX), X (piksel), Y (piksel)
            // NOT: Mobile uygulama MAC adreslerini bu formatta gönderiyor
            
            // Ana koridor - güney duvarı
            beaconRepository.save(new Beacon("08:92:72:87:9C:72", 789, 184));  // Beacon 1 (B1) - Derslik 157
            beaconRepository.save(new Beacon("08:92:72:87:9A:AE", 758, 262));  // Beacon 2
            beaconRepository.save(new Beacon("08:92:72:87:8E:7A", 600, 266));  // Beacon 3
            beaconRepository.save(new Beacon("08:92:72:87:9C:96", 966, 266));  // Beacon 5
            beaconRepository.save(new Beacon("08:92:72:87:9C:86", 871, 263));  // Beacon 7
            beaconRepository.save(new Beacon("08:92:72:87:8F:E6", 452, 265));  // Beacon 8
            beaconRepository.save(new Beacon("08:92:72:87:8F:CE", 329, 262));  // Beacon 11
            // Ana koridor - kuzey duvarı
            beaconRepository.save(new Beacon("08:92:72:87:8F:CA", 1143, 264)); // Beacon 4
            beaconRepository.save(new Beacon("08:92:72:87:9B:36", 1085, 183)); // Beacon 6
            beaconRepository.save(new Beacon("08:92:72:84:0A:66", 657, 184));  // Beacon 9
            beaconRepository.save(new Beacon("08:92:72:87:8F:1A", 405, 187));  // Beacon 10
            beaconRepository.save(new Beacon("08:92:72:87:9B:0E", 232, 185));  // Beacon 12
            
            // Sol koridor
            beaconRepository.save(new Beacon("08:92:72:87:8D:D6", 198, 232));  // Beacon 13
            beaconRepository.save(new Beacon("08:92:72:87:8E:06", 198, 329));  // Beacon 14
            beaconRepository.save(new Beacon("08:92:72:87:9A:72", 527, 186));  // Beacon 15
            
            System.out.println("15 beacon yuklendi (MAC adresleriyle - mobile formatinda).");
        }

        // Beacon -> Oda eşleştirmelerini yükle
        initBeaconRoomMapping();
    }

    private void initBeaconRoomMapping() {
        // Her beacon MAC adresi için en yakın oda (MOBILE FORMATINDA)
        beaconToRoomMap.put("08:92:72:87:9C:72", "157");  // Beacon 1 (B1)
        beaconToRoomMap.put("08:92:72:87:9A:AE", "144");  // Beacon 2
        beaconToRoomMap.put("08:92:72:87:8E:7A", "143");  // Beacon 3
        beaconToRoomMap.put("08:92:72:87:8F:CA", "147");  // Beacon 4
        beaconToRoomMap.put("08:92:72:87:9C:96", "146");  // Beacon 5
        beaconToRoomMap.put("08:92:72:87:9B:36", "156");  // Beacon 6
        beaconToRoomMap.put("08:92:72:87:9C:86", "145");  // Beacon 7
        beaconToRoomMap.put("08:92:72:87:8F:E6", "142");  // Beacon 8
        beaconToRoomMap.put("08:92:72:84:0A:66", "158");  // Beacon 9
        beaconToRoomMap.put("08:92:72:87:8F:1A", "160");  // Beacon 10
        beaconToRoomMap.put("08:92:72:87:8F:CE", "141");  // Beacon 11
        beaconToRoomMap.put("08:92:72:87:9B:0E", "161");  // Beacon 12
        beaconToRoomMap.put("08:92:72:87:8D:D6", "yemekhane");  // Beacon 13
        beaconToRoomMap.put("08:92:72:87:8E:06", "139");  // Beacon 14
        beaconToRoomMap.put("08:92:72:87:9A:72", "159");  // Beacon 15
    }

    public Beacon getBeacon(String uuid) {
        // Veritabanindan beacon'i bul
        Optional<Beacon> beacon = beaconRepository.findById(uuid);
        return beacon.orElse(null);
    }

    /**
     * Beacon ID'ye göre en yakın odayı döner
     */
    public String getNearestRoom(String beaconId) {
        return beaconToRoomMap.getOrDefault(beaconId, "unknown");
    }

    /**
     * Tüm beacon'ları döner
     */
    public Iterable<Beacon> getAllBeacons() {
        return beaconRepository.findAll();
    }
}
