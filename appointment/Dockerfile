# ---- build ----
# The build needs JDK 25 and pulls dependencies once; the runtime image carries only the JRE.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy the build descriptor first so the dependency layer is cached between code changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---- runtime ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# Run unprivileged: the service handles patient data and needs no root.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build /app/build/libs/appointment-service.jar /app/appointment-service.jar
USER app

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
    CMD ["sh", "-c", "wget -qO- http://127.0.0.1:${PORT}/api/health >/dev/null || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/appointment-service.jar"]
