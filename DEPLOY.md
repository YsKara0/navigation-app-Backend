# Coolify Deploy Talimatları

## 🚀 Backend'i Coolify'a Deploy Etme

### Adım 1: GitHub'a Push
Backend klasörünü GitHub'a yükle:

```bash
cd backend
git init
git add .
git commit -m "Initial commit - Indoor Navigation Backend"
git remote add origin https://github.com/KULLANICI_ADI/navigation-backend.git
git push -u origin main
```

### Adım 2: Coolify'da Proje Oluştur

1. Coolify paneline git
2. **"Add New Resource"** → **"Application"** seç
3. **GitHub** bağlantısını seç ve repo'yu bul
4. **Build Pack:** `Dockerfile` seç
5. **Port:** `8080` yaz
6. **Domain:** `navigation-app.bayessoft.com.tr` zaten ayarlı

### Adım 3: Environment Variables (Opsiyonel)

Coolify panelinde şu değişkenleri ekleyebilirsin:
```
JAVA_OPTS=-Xmx512m -Xms256m
```

### Adım 4: Deploy

**"Deploy"** butonuna bas. Coolify otomatik olarak:
1. Dockerfile'ı bulur
2. Maven ile build eder
3. Docker image oluşturur
4. Container'ı başlatır

---

## 🔗 Endpoint'ler

Deploy tamamlandığında:

| Servis | URL |
|--------|-----|
| WebSocket | `wss://navigation-app.bayessoft.com.tr/ws/navigation` |
| Test Sayfası | `https://navigation-app.bayessoft.com.tr/test.html` |
| H2 Console | `https://navigation-app.bayessoft.com.tr/h2-console` |

---

## 📱 Flutter Uygulaması İçin

`navigation_service.dart` dosyasında URL'i güncelle:

```dart
class NavigationService {
  // Localhost yerine production URL
  static const String _wsUrl = 'wss://navigation-app.bayessoft.com.tr/ws/navigation';
  
  // ...
}
```

---

## ⚙️ Dockerfile Açıklaması

```dockerfile
# 1. Build aşaması - Maven ile JAR oluştur
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 2. Çalıştırma aşaması - Sadece JRE ile çalıştır
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Multi-stage build** kullanıyoruz:
- Build aşaması: ~500MB (Maven + JDK)
- Final image: ~150MB (Sadece JRE)

---

## 🔍 Sorun Giderme

### Build Hatası
```bash
# Coolify logs'a bak
# veya lokal olarak test et:
docker build -t navigation-backend .
docker run -p 8080:8080 navigation-backend
```

### WebSocket Bağlantı Hatası
- URL'in `wss://` ile başladığından emin ol (HTTPS için)
- Cloudflare proxy'si WebSocket destekliyor mu kontrol et

### Memory Hatası
Coolify'da resource limits artır:
- Memory: 512MB minimum
- CPU: 0.5 core minimum

---

## 📊 Sistem Gereksinimleri

| Kaynak | Minimum | Önerilen |
|--------|---------|----------|
| RAM | 256MB | 512MB |
| CPU | 0.25 core | 0.5 core |
| Disk | 200MB | 500MB |

---

## ✅ Test

Deploy sonrası test etmek için:

1. **Tarayıcıda aç:** https://navigation-app.bayessoft.com.tr/test.html
2. **Proximity Test:** Beacon seç → Rota görünmeli
3. **WebSocket Test:** Console'da bağlantı mesajı görünmeli

```javascript
// Tarayıcı console'unda test:
const ws = new WebSocket('wss://navigation-app.bayessoft.com.tr/ws/navigation');
ws.onopen = () => console.log('Bağlandı!');
ws.onerror = (e) => console.log('Hata:', e);
```
