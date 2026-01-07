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
            // Format: MAC adresi, X (piksel), Y (piksel)
            
            // Ana koridor - güney duvarı
            beaconRepository.save(new Beacon("72:9C:87:72:92:08", 789, 184));  // Beacon 1
            beaconRepository.save(new Beacon("AE:9A:87:72:92:08", 758, 262));  // Beacon 2
            beaconRepository.save(new Beacon("7A:8E:87:72:92:08", 600, 266));  // Beacon 3
            beaconRepository.save(new Beacon("96:9C:87:72:92:08", 966, 266));   // Beacon 5
            beaconRepository.save(new Beacon("86:9C:87:72:92:08", 871, 263));   // Beacon 7
            beaconRepository.save(new Beacon("E6:8F:87:72:92:08", 452, 265));  // Beacon 8
            beaconRepository.save(new Beacon("CE:8F:87:72:92:08", 329, 262));   // Beacon 11
            // Ana koridor - kuzey duvarı
            beaconRepository.save(new Beacon("CA:8F:87:72:92:08", 1143, 264));  // Beacon 4
            beaconRepository.save(new Beacon("36:9B:87:72:92:08", 1085, 183));   // Beacon 6
            beaconRepository.save(new Beacon("66:0A:84:72:92:08", 657, 184));  // Beacon 9
            beaconRepository.save(new Beacon("1A:8F:87:72:92:08", 405, 187));   // Beacon 10
            beaconRepository.save(new Beacon("0E:9B:87:72:92:08", 232, 185));   // Beacon 12
            
            // Sol koridor
            beaconRepository.save(new Beacon("D6:8D:87:72:92:08", 198, 232));   // Beacon 13
            beaconRepository.save(new Beacon("06:8E:87:72:92:08", 198, 329));   // Beacon 14
            beaconRepository.save(new Beacon("72:9A:87:72:92:08", 527, 186));   // Beacon 15
            
            System.out.println("15 beacon yuklendi (MAC adresleriyle).");
        }

        // Beacon -> Oda eşleştirmelerini yükle
        initBeaconRoomMapping();
    }

    private void initBeaconRoomMapping() {
        // Her beacon MAC adresi için en yakın oda
        beaconToRoomMap.put("72:9C:87:72:92:08", "157");  // Beacon 1
        beaconToRoomMap.put("AE:9A:87:72:92:08", "144");  // Beacon 2
        beaconToRoomMap.put("7A:8E:87:72:92:08", "143");  // Beacon 3
        beaconToRoomMap.put("CA:8F:87:72:92:08", "147");  // Beacon 4
        beaconToRoomMap.put("96:9C:87:72:92:08", "146");  // Beacon 5
        beaconToRoomMap.put("36:9B:87:72:92:08", "156");  // Beacon 6
        beaconToRoomMap.put("86:9C:87:72:92:08", "145");  // Beacon 7
        beaconToRoomMap.put("E6:8F:87:72:92:08", "142");  // Beacon 8
        beaconToRoomMap.put("66:0A:84:72:92:08", "158");  // Beacon 9
        beaconToRoomMap.put("1A:8F:87:72:92:08", "160");  // Beacon 10
        beaconToRoomMap.put("CE:8F:87:72:92:08", "141");  // Beacon 11
        beaconToRoomMap.put("0E:9B:87:72:92:08", "161");  // Beacon 12
        beaconToRoomMap.put("D6:8D:87:72:92:08", "yemekhane");  // Beacon 13
        beaconToRoomMap.put("06:8E:87:72:92:08", "139");  // Beacon 14
        beaconToRoomMap.put("72:9A:87:72:92:08", "159");  // Beacon 15
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
