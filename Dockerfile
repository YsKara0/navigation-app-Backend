# Build aşaması - daha stabil official image
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml .
# Dependency'leri önce indir (cache için)
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# Çalıştırma aşaması - küçük ve hızlı
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# JAR dosyasını kopyala
COPY --from=build /app/target/*.jar app.jar

# Port
EXPOSE 8080

# Health check - root endpoint'i kontrol et
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Uygulama başlat
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
