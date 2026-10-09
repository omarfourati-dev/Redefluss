# --- Svelte app ---
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# --- Kotlin fat JAR with the app in its resources ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# One JVM with bounded memory (small Docker Desktop VMs); raise with --build-arg GRADLE_XMX=2g
ARG GRADLE_XMX=900m
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx${GRADLE_XMX} -Dkotlin.compiler.execution.strategy=in-process"
# Optional: trust an extra root CA (TLS-inspecting proxy / antivirus) via `--secret id=extra_ca,src=ca.pem`
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then keytool -importcert -noprompt -cacerts -storepass changeit -alias extra-ca -file /run/secrets/extra_ca; fi
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
COPY --from=web /web/build/ src/main/resources/static/
RUN ./gradlew --no-daemon buildFatJar -x test

# --- runtime ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/build/libs/redefluss.jar ./redefluss.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["java", "-jar", "/app/redefluss.jar"]
