# --- Build stage ---
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --create-home --shell /usr/sbin/nologin beacon \
    && mkdir -p /data/uploads \
    && chown -R beacon:beacon /data/uploads

WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN chown beacon:beacon app.jar

USER beacon
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
