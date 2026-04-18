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
# Tuned for Render free tier (512 MB RAM):
#   - SerialGC uses least memory overhead for single-core containers
#   - MaxRAMPercentage lets JVM self-tune if memory limit changes
ENV JAVA_OPTS="-server \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=70.0 \
  -XX:InitialRAMPercentage=30.0 \
  -XX:+UseSerialGC \
  -Djava.security.egd=file:/dev/./urandom \
  -Dspring.backgroundpreinitializer.ignore=true"

EXPOSE ${PORT}

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -q --spider http://localhost:${PORT}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Dserver.port=${PORT} -jar app.jar"]
