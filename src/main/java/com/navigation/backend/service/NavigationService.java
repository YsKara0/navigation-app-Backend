package com.navigation.backend.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import org.springframework.stereotype.Service;

import com.navigation.backend.model.Point;

@Service
public class NavigationService {

    private final Map<String, GraphNode> nodes = new HashMap<>();
    
    // Oda ID -> Node ID eşleştirmesi
    private final Map<String, String> roomToNodeMap = new HashMap<>();

    public NavigationService() {
        initializeGraph();
    }

    /**
     * Gerçek bina haritasına göre navigasyon grafiği
     * Koordinatlar piksel cinsinden (18 px = 1m)
     */
    private void initializeGraph() {
        // ========== KORIDOR WAYPOINT'LERİ ==========
        // Ana koridor (y=225, merkez hattı)
        addNode("corridor-w1", 245, 225, "Koridor Kavşak");      // Sol koridor bağlantısı
        addNode("corridor-w2", 360, 225, "Koridor 141-160");
        addNode("corridor-w3", 490, 225, "Koridor 142-159");
        addNode("corridor-w4", 630, 225, "Koridor 143-158");
        addNode("corridor-w5", 760, 225, "Koridor 144-157");
        addNode("corridor-w6", 870, 225, "Koridor Merdiven-1");
        addNode("corridor-w7", 1000, 225, "Koridor 146-156");
        addNode("corridor-w8", 1175, 225, "Koridor 147-155");
        addNode("corridor-w9", 1310, 225, "Koridor 148-WC");
        addNode("corridor-w10", 1430, 225, "Koridor 149-151");
        addNode("corridor-w11", 1575, 225, "Koridor 150-131");

        // Sol koridor (x=245, dikey)
        addNode("left-w1", 245, 345, "Sol Koridor Üst");
        addNode("left-w2", 245, 435, "Sol Koridor Orta");
        addNode("left-w3", 245, 540, "Sol Koridor Alt");
        addNode("left-w4", 245, 695, "Giriş");

        // ========== KUZEY SIRA ODALARI (üst sıra) ==========
        addNode("room-161", 250, 110, "TTO Ofisi");
        addNode("room-160", 370, 110, "Derslik 160");
        addNode("room-159", 500, 110, "Derslik 159");
        addNode("room-158", 630, 110, "Derslik 158");
        addNode("room-157", 760, 110, "Derslik 157");
        addNode("stairs-1", 870, 110, "Merdiven 1");
        addNode("room-156", 1000, 110, "Kimya Lab");
        addNode("room-155", 1160, 110, "Modelleme Lab");
        addNode("wc-1", 1275, 110, "WC");
        addNode("room-151", 1385, 110, "Maket Atölyesi");
        addNode("room-131", 1540, 110, "Temel Elektronik Lab");

        // ========== GÜNEY SIRA ODALARI (alt sıra) ==========
        addNode("room-141", 360, 345, "Areli İletişim USAM");
        addNode("room-142", 490, 345, "Derslik 142");
        addNode("room-143", 620, 345, "Derslik 143");
        addNode("room-144", 750, 345, "Derslik 144");
        addNode("room-145", 870, 345, "Müh. Öğr. Çal. Ofisi");
        addNode("room-146", 1010, 345, "Fizik Lab");
        addNode("room-147", 1175, 345, "Büyük Veri IoT Lab");
        addNode("room-148", 1310, 345, "Araştırma Görev Girişi");
        addNode("room-149", 1430, 345, "Öğrenci Proje Ofisi");
        addNode("room-150", 1575, 345, "Kalibrasyon Lab");

        // ========== SOL KORİDOR ODALARI ==========
        addNode("yemekhane", 105, 230, "Yemekhane");
        addNode("wc-bay", 105, 330, "WC Bay");
        addNode("stairs-left", 105, 435, "Merdiven 2");
        addNode("room-139", 105, 540, "Derslik 139");
        addNode("room-120", 360, 500, "Derslik 120");
        addNode("entrance", 245, 695, "Giriş");

        // ========== BAĞLANTILAR ==========
        // Ana koridor bağlantıları (yatay)
        connect("corridor-w1", "corridor-w2");
        connect("corridor-w2", "corridor-w3");
        connect("corridor-w3", "corridor-w4");
        connect("corridor-w4", "corridor-w5");
        connect("corridor-w5", "corridor-w6");
        connect("corridor-w6", "corridor-w7");
        connect("corridor-w7", "corridor-w8");
        connect("corridor-w8", "corridor-w9");
        connect("corridor-w9", "corridor-w10");
        connect("corridor-w10", "corridor-w11");

        // Sol koridor bağlantıları (dikey)
        connect("corridor-w1", "left-w1");
        connect("left-w1", "left-w2");
        connect("left-w2", "left-w3");
        connect("left-w3", "left-w4");

        // Kuzey odalar -> Koridor
        connect("room-161", "corridor-w1");
        connect("room-160", "corridor-w2");
        connect("room-159", "corridor-w3");
        connect("room-158", "corridor-w4");
        connect("room-157", "corridor-w5");
        connect("stairs-1", "corridor-w6");
        connect("room-156", "corridor-w7");
        connect("room-155", "corridor-w8");
        connect("wc-1", "corridor-w9");
        connect("room-151", "corridor-w10");
        connect("room-131", "corridor-w11");

        // Güney odalar -> Koridor
        connect("room-141", "corridor-w2");
        connect("room-142", "corridor-w3");
        connect("room-143", "corridor-w4");
        connect("room-144", "corridor-w5");
        connect("room-145", "corridor-w6");
        connect("room-146", "corridor-w7");
        connect("room-147", "corridor-w8");
        connect("room-148", "corridor-w9");
        connect("room-149", "corridor-w10");
        connect("room-150", "corridor-w11");

        // Sol koridor odaları
        connect("yemekhane", "corridor-w1");
        connect("wc-bay", "left-w1");
        connect("stairs-left", "left-w2");
        connect("room-139", "left-w3");
        connect("room-120", "left-w2");
        connect("entrance", "left-w4");

        // Oda ID -> Node ID eşleştirmesi
        initRoomMapping();
    }

    private void initRoomMapping() {
        // Sayısal oda ID'lerini node'larla eşleştir
        roomToNodeMap.put("161", "room-161");
        roomToNodeMap.put("160", "room-160");
        roomToNodeMap.put("159", "room-159");
        roomToNodeMap.put("158", "room-158");
        roomToNodeMap.put("157", "room-157");
        roomToNodeMap.put("156", "room-156");
        roomToNodeMap.put("155", "room-155");
        roomToNodeMap.put("151", "room-151");
        roomToNodeMap.put("131", "room-131");
        roomToNodeMap.put("141", "room-141");
        roomToNodeMap.put("142", "room-142");
        roomToNodeMap.put("143", "room-143");
        roomToNodeMap.put("144", "room-144");
        roomToNodeMap.put("145", "room-145");
        roomToNodeMap.put("146", "room-146");
        roomToNodeMap.put("147", "room-147");
        roomToNodeMap.put("148", "room-148");
        roomToNodeMap.put("149", "room-149");
        roomToNodeMap.put("150", "room-150");
        roomToNodeMap.put("139", "room-139");
        roomToNodeMap.put("120", "room-120");
        
        // Özel alanlar
        roomToNodeMap.put("entrance", "entrance");
        roomToNodeMap.put("giris", "entrance");
        roomToNodeMap.put("giriş", "entrance");
        roomToNodeMap.put("yemekhane", "yemekhane");
        roomToNodeMap.put("wc", "wc-1");
        roomToNodeMap.put("wc-1", "wc-1");
        roomToNodeMap.put("wc-bay", "wc-bay");
        roomToNodeMap.put("merdiven", "stairs-1");
        roomToNodeMap.put("stairs-1", "stairs-1");
        roomToNodeMap.put("stairs-left", "stairs-left");
        roomToNodeMap.put("merdiven-2", "stairs-left");
    }

    private void addNode(String id, double x, double y, String name) {
        nodes.put(id, new GraphNode(id, x, y, name));
    }

    private void connect(String id1, String id2) {
        GraphNode n1 = nodes.get(id1);
        GraphNode n2 = nodes.get(id2);
        
        if (n1 != null && n2 != null) {
            double distance = calculateDistance(n1, n2);
            n1.neighbors.put(n2, distance);
            n2.neighbors.put(n1, distance);
        }
    }

    /**
     * En kısa yolu hesaplar
     * @param startLocation Kullanıcının mevcut konumu (piksel)
     * @param target Hedef oda ID veya ismi (örn: "147", "Fizik Lab", "entrance")
     * @return Waypoint listesi
     */
    public List<Point> calculateShortestPath(Point startLocation, String target) {
        // Başlangıç noktasına en yakın node'u bul
        GraphNode startNode = findClosestNode(startLocation);
        
        // Hedef node'u bul
        GraphNode endNode = findTargetNode(target);

        if (startNode == null || endNode == null) {
            return Collections.emptyList();
        }

        return runDijkstra(startNode, endNode);
    }

    /**
     * Hedef node'u çeşitli yollarla arar
     */
    private GraphNode findTargetNode(String target) {
        if (target == null || target.isEmpty()) {
            return null;
        }

        String normalizedTarget = target.toLowerCase().trim();

        // 1. Önce room mapping'den bak
        if (roomToNodeMap.containsKey(normalizedTarget)) {
            return nodes.get(roomToNodeMap.get(normalizedTarget));
        }

        // 2. Direkt node ID olarak bak
        if (nodes.containsKey(normalizedTarget)) {
            return nodes.get(normalizedTarget);
        }

        // 3. İsimle ara
        return findNodeByName(target);
    }

    private List<Point> runDijkstra(GraphNode start, GraphNode end) {
        Map<GraphNode, Double> distances = new HashMap<>();
        Map<GraphNode, GraphNode> previous = new HashMap<>();
        PriorityQueue<GraphNode> queue = new PriorityQueue<>(Comparator.comparingDouble(n -> distances.getOrDefault(n, Double.MAX_VALUE)));

        for (GraphNode node : nodes.values()) {
            distances.put(node, Double.MAX_VALUE);
        }
        distances.put(start, 0.0);
        queue.add(start);

        while (!queue.isEmpty()) {
            GraphNode current = queue.poll();

            if (current.equals(end)) break;

            for (Map.Entry<GraphNode, Double> neighborEntry : current.neighbors.entrySet()) {
                GraphNode neighbor = neighborEntry.getKey();
                double weight = neighborEntry.getValue();
                double newDist = distances.get(current) + weight;

                if (newDist < distances.get(neighbor)) {
                    distances.put(neighbor, newDist);
                    previous.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }

        List<Point> path = new ArrayList<>();
        GraphNode current = end;
        while (current != null) {
            path.add(new Point(current.x, current.y));
            current = previous.get(current);
        }
        Collections.reverse(path);
        return path;
    }

    private GraphNode findClosestNode(Point p) {
        GraphNode closest = null;
        double minDist = Double.MAX_VALUE;

        for (GraphNode node : nodes.values()) {
            double dist = Math.sqrt(Math.pow(node.x - p.getX(), 2) + Math.pow(node.y - p.getY(), 2));
            if (dist < minDist) {
                minDist = dist;
                closest = node;
            }
        }
        return closest;
    }

    private GraphNode findNodeByName(String name) {
        String lowerName = name.toLowerCase();
        for (GraphNode node : nodes.values()) {
            if (node.name.toLowerCase().contains(lowerName)) {
                return node;
            }
        }
        return null;
    }

    private double calculateDistance(GraphNode n1, GraphNode n2) {
        return Math.sqrt(Math.pow(n1.x - n2.x, 2) + Math.pow(n1.y - n2.y, 2));
    }

    /**
     * Tüm odaların listesini döner (mobil app için)
     */
    public List<Map<String, Object>> getAllRooms() {
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (GraphNode node : nodes.values()) {
            if (node.id.startsWith("room-") || node.id.equals("entrance") || 
                node.id.equals("yemekhane") || node.id.startsWith("wc") ||
                node.id.startsWith("stairs")) {
                Map<String, Object> room = new HashMap<>();
                room.put("id", node.id);
                room.put("name", node.name);
                room.put("x", node.x);
                room.put("y", node.y);
                rooms.add(room);
            }
        }
        return rooms;
    }

    private static class GraphNode {
        final String id;
        final double x, y;
        final String name;
        final Map<GraphNode, Double> neighbors = new HashMap<>();

        public GraphNode(String id, double x, double y, String name) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.name = name;
        }
    }
}
