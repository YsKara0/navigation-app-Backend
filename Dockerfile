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

# Uygulama başlat
ENTRYPOINT ["java", "-jar", "app.jar"]
