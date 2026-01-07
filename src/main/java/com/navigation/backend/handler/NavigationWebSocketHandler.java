package com.navigation.backend.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navigation.backend.model.LocationLog;
import com.navigation.backend.model.Point;
import com.navigation.backend.repository.LocationLogRepository;
import com.navigation.backend.service.NavigationService;
import com.navigation.backend.service.PositioningService;
import com.navigation.backend.service.PositioningService.PositioningMode;
import com.navigation.backend.service.PositioningService.PositioningResult;

@Component
public class NavigationWebSocketHandler extends TextWebSocketHandler {

    private final CopyOnWriteArrayList<WebSocketSession> connectedUsers = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Servisleri enjekte ediyoruz
    private final PositioningService positioningService;
    private final LocationLogRepository locationLogRepository;
   
    private final NavigationService navigationService;

    public NavigationWebSocketHandler(
            PositioningService positioningService,
            LocationLogRepository locationLogRepository,
            
            NavigationService navigationService) {
        this.positioningService = positioningService;
        this.locationLogRepository = locationLogRepository;
       
        this.navigationService = navigationService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        connectedUsers.add(session);
        System.out.println("Yeni baglanti: " + session.getId());
        
        // Hoşgeldin mesajı gönder
        Map<String, Object> welcome = new HashMap<>();
        welcome.put("type", "welcome");
        welcome.put("sessionId", session.getId());
        welcome.put("message", "Indoor Navigation sistemine bağlandınız");
        welcome.put("defaultMode", positioningService.getDefaultMode().name());
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(welcome)));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        connectedUsers.remove(session);
        // Kullanıcının aktif rotasını ve konum state'ini temizle
        positioningService.clearActiveRoute(session.getId());
        positioningService.resetUserLocationState(session.getId());
        System.out.println("Baglanti kapandi: " + session.getId());
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();

        try {
            Map<String, Object> data = objectMapper.readValue(payload, Map.class);
            String messageType = (String) data.getOrDefault("type", "location");

            switch (messageType) {
                case "location":
                    handleLocationRequest(session, data);
                    break;
                case "setMode":
                    handleSetMode(session, data);
                    break;
                case "ping":
                    handlePing(session);
                    break;
                default:
                    sendError(session, "Bilinmeyen mesaj tipi: " + messageType);
            }

        } catch (Exception e) {
            System.err.println("Hata olustu: " + e.getMessage());
            e.printStackTrace();
            sendError(session, "İşlem hatası: " + e.getMessage());
        }
    }

    /**
     * Konum hesaplama isteğini işler
     */
    @SuppressWarnings("unchecked")
    private void handleLocationRequest(WebSocketSession session, Map<String, Object> data) throws Exception {
        if (!data.containsKey("beacons")) {
            sendError(session, "Beacon verisi eksik");
            return;
        }

        List<Map<String, Object>> beaconReadings = (List<Map<String, Object>>) data.get("beacons");
        
        // Debug: Gelen beacon verilerini logla
        System.out.println("[WebSocket] Gelen beacon sayisi: " + beaconReadings.size());
        for (Map<String, Object> beacon : beaconReadings) {
            System.out.println("[WebSocket] Beacon: " + beacon);
        }

        // Konum belirleme modunu al (opsiyonel)
        PositioningMode mode = positioningService.getDefaultMode();
        if (data.containsKey("mode")) {
            try {
                mode = PositioningMode.valueOf((String) data.get("mode"));
            } catch (IllegalArgumentException e) {
                // Geçersiz mod, varsayılanı kullan
            }
        }

        // KONUM HESAPLAMA
        PositioningResult result = positioningService.calculateLocation(beaconReadings, mode);

        if (!result.isValid()) {
            // Daha detaylı hata mesajı
            String errorDetail = result.getErrorMessage();
            if (beaconReadings.size() < 3 && mode == PositioningMode.TRILATERATION) {
                errorDetail += " (Trilateration icin en az 3 beacon gerekli, " + beaconReadings.size() + " beacon alindi)";
            }
            System.out.println("[WebSocket] Konum hesaplanamadi: " + errorDetail);
            sendError(session, errorDetail);
            return;
        }

        // BÖLGE TESPİTİ
        
        // HEDEF BİLGİSİ
        String targetDestination = (String) data.get("target");

        // LOGLAMA
        LocationLog log = new LocationLog(
            session.getId(),
            result.getX(),
            result.getY(),
            result.getNearestRoom(),  // zoneName olarak nearestRoom kullanıyoruz
            targetDestination
        );
        locationLogRepository.save(log);

        // CEVABI HAZIRLA
        Map<String, Object> response = new HashMap<>();
        response.put("type", "location");
        response.put("status", "ok");
        
        // Konum bilgileri
        response.put("x", result.getX());
        response.put("y", result.getY());
        response.put("xMeter", result.getX() / 18.0); // Metre cinsinden
        response.put("yMeter", result.getY() / 18.0);
        
        // Ek bilgiler
        response.put("mode", result.getMode().name());
        response.put("confidence", result.getConfidence());
        response.put("nearestBeacon", result.getNearestBeaconId());
        response.put("nearestRoom", result.getNearestRoom());
        response.put("estimatedDistance", result.getEstimatedDistance());
        
        // ROTA HESAPLAMA
        if (targetDestination != null && !targetDestination.isEmpty()) {
            try {
                List<Point> path = navigationService.calculateShortestPath(result.getLocation(), targetDestination);
                response.put("path", path);
                response.put("hasRoute", true);
                
                // Aktif rotayı PositioningService'e kaydet (snap to route için)
                if (path != null && path.size() >= 2) {
                    positioningService.setActiveRoute(session.getId(), path);
                }
            } catch (Exception e) {
                response.put("hasRoute", false);
                response.put("routeError", e.getMessage());
            }
        } else {
            // Hedef yoksa aktif rotayı temizle
            positioningService.clearActiveRoute(session.getId());
        }

        // MOBILE GERI GÖNDER
        String jsonResponse = objectMapper.writeValueAsString(response);
        session.sendMessage(new TextMessage(jsonResponse));
    }

    /**
     * Konum belirleme modunu değiştirir
     */
    private void handleSetMode(WebSocketSession session, Map<String, Object> data) throws Exception {
        String modeStr = (String) data.get("mode");
        
        if (modeStr == null) {
            sendError(session, "Mode belirtilmedi");
            return;
        }

        try {
            PositioningMode mode = PositioningMode.valueOf(modeStr.toUpperCase());
            positioningService.setDefaultMode(mode);

            Map<String, Object> response = new HashMap<>();
            response.put("type", "modeChanged");
            response.put("status", "ok");
            response.put("mode", mode.name());
            response.put("message", "Konum belirleme modu değiştirildi: " + mode.name());

            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
            
        } catch (IllegalArgumentException e) {
            sendError(session, "Geçersiz mod: " + modeStr + ". Geçerli modlar: PROXIMITY, TRILATERATION, HYBRID, WEIGHTED");
        }
    }

    /**
     * Ping isteğini işler (bağlantı kontrolü)
     */
    private void handlePing(WebSocketSession session) throws Exception {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "pong");
        response.put("timestamp", System.currentTimeMillis());
        response.put("connectedUsers", connectedUsers.size());
        
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
    }

    /**
     * Hata mesajı gönderir
     */
    private void sendError(WebSocketSession session, String errorMessage) throws Exception {
        Map<String, Object> response = new HashMap<>();
        response.put("type", "error");
        response.put("status", "error");
        response.put("message", errorMessage);
        
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
    }

    /**
     * Tüm bağlı kullanıcılara mesaj yayınlar
     */
    public void broadcast(String message) {
        for (WebSocketSession session : connectedUsers) {
            try {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            } catch (Exception e) {
                System.err.println("Broadcast hatasi: " + e.getMessage());
            }
        }
    }

    /**
     * Bağlı kullanıcı sayısını döner
     */
    public int getConnectedUserCount() {
        return connectedUsers.size();
    }
}