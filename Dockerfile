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
    && chown -R appuser:appgroup /app

USER appuser

EXPOSE 8081

ENTRYPOINT ["/app/docker-entrypoint.sh"]