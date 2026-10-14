# The Interchange dedicated server: the same game host a phone uses, with no
# screen, keeping its game in /state. The build context is the repository root:
#
#     docker compose up --build

# JDK 25 to match gradle/gradle-daemon-jvm.properties, so Gradle doesn't download one.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# Wrapper and build scripts first, so a change to the game doesn't download Gradle again.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties /src/
COPY gradle /src/gradle
RUN chmod +x gradlew && ./gradlew --version > /dev/null

COPY sim /src/sim
COPY net /src/net
COPY server /src/server
COPY dedicated /src/dedicated
# Named in settings.gradle.kts, so it has to be here though the server doesn't use it.
COPY packtool /src/packtool
COPY packs/base /src/packs/base

# No Android SDK here, so settings.gradle.kts leaves the app out.
RUN ./gradlew --no-daemon :dedicated:installDist

# A Java runtime with only the modules the server loads.
RUN set -eu; \
    MODULES="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 21 \
        /src/dedicated/build/install/interchange-server/lib/*.jar)"; \
    jlink --add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files \
          --compress=zip-6 --output /javaruntime

RUN set -eu; \
    groupadd --gid 10003 interchange; \
    useradd --uid 10003 --gid 10003 --no-create-home --shell /usr/sbin/nologin interchange; \
    mkdir -p /stage/opt/interchange /stage/state /stage/tmp; \
    cp -a /src/dedicated/build/install/interchange-server/lib /stage/opt/interchange/lib; \
    chmod -R a+rX /stage/opt/interchange; \
    chown -R 10003:10003 /stage/state /stage/tmp; \
    chmod 1777 /stage/tmp

# A static busybox, for the healthcheck and a shell to look round with.
FROM busybox:stable-musl AS busybox

FROM gcr.io/distroless/base-debian12

COPY --from=build /javaruntime /opt/java
COPY --from=build /stage/opt/interchange /opt/interchange
COPY --from=build --chown=10003:10003 /stage/state /state
COPY --from=build /stage/tmp /tmp
COPY --from=build /etc/passwd /etc/passwd
COPY --from=build /etc/group /etc/group
COPY --from=busybox /bin/busybox /bin/busybox

ENV JAVA_HOME=/opt/java \
    INTERCHANGE_STATE_DIR=/state \
    INTERCHANGE_PORT=45688

VOLUME ["/state"]
EXPOSE 45688/tcp 45687/udp

USER 10003:10003

HEALTHCHECK --interval=30s --timeout=3s --start-period=120s \
    CMD ["/bin/busybox", "nc", "-z", "127.0.0.1", "45688"]

# Java run directly, as there's no shell for Gradle's start script. JVM tuning goes in JAVA_TOOL_OPTIONS.
ENTRYPOINT ["/opt/java/bin/java", "-XX:MaxRAMPercentage=75", \
            "-cp", "/opt/interchange/lib/*", "com.rm.interchange.dedicated.MainKt"]
