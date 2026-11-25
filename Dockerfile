# Runtime image for the ledger service. Build the jar first: ./mvnw -DskipTests package
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 1001 ledger
WORKDIR /app
COPY target/ledger-service-0.0.1-SNAPSHOT.jar app.jar
USER ledger

EXPOSE 8080
# Size the heap from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
