# Build aşaması
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Çalıştırma aşaması
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# JAR dosyasını kopyala
COPY --from=build /app/target/*.jar app.jar

# Port
EXPOSE 8080

# Health check - uygulama hazır mı kontrol et
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

# Uygulama başlat
ENTRYPOINT ["java", "-jar", "app.jar"]
