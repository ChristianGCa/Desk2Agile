FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw ./
COPY pom.xml ./

RUN ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar
COPY docker-entrypoint.sh /app/docker-entrypoint.sh

RUN addgroup -S appgroup && adduser -S appuser -G appgroup \
    && chmod +x /app/docker-entrypoint.sh \
    && mkdir -p /app/certs /app/logs \
    && cp "$JAVA_HOME/lib/security/cacerts" /app/truststore.jks \
    && chown -R appuser:appgroup /app

USER appuser

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -qO- --header="Accept: application/json" http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]