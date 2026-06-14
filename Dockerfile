# ===== Multi-stage build for production =====
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

# Download dependencies (cached layer)
RUN ./mvnw dependency:go-offline -B

# Copy source and build
COPY src src
RUN ./mvnw clean package -DskipTests -B

# ===== Runtime image =====
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# Create directories for recordings & uploads with proper ownership
RUN mkdir -p /app/recordings /app/uploads && \
    chown -R appuser:appgroup /app

COPY --from=builder --chown=appuser:appgroup /app/target/*.jar app.jar

# Switch to non-root user
USER appuser

# Cloud-friendly defaults
ENV PORT=8080
ENV SPRING_PROFILES_ACTIVE=prod
# Tuned for Render starter plan (512 MB – 1 GB RAM):
#   - SerialGC = lowest memory overhead, best for single-core containers
#   - TieredStopAtLevel=1 = interpreter-only, fastest cold start
#   - spring.backgroundpreinitializer.ignore = skip async pre-init
ENV JAVA_OPTS="-server \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=70.0 \
  -XX:InitialRAMPercentage=25.0 \
  -XX:+UseSerialGC \
  -XX:TieredStopAtLevel=1 \
  -Djava.security.egd=file:/dev/./urandom \
  -Dspring.backgroundpreinitializer.ignore=true \
  -Dspring.jmx.enabled=false"

EXPOSE ${PORT}

# Health check — start-period=120s gives Spring Boot enough time to start cold
HEALTHCHECK --interval=30s --timeout=15s --start-period=120s --retries=3 \
  CMD wget -q --spider http://localhost:${PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Dserver.port=${PORT} -jar app.jar"]
