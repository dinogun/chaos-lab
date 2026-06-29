# ============================================================
# Stage 1 — Build
# Uses IBM Semeru Runtimes (Eclipse OpenJ9 JDK) as required
# by the PRD constraint: "Semeru java 25 docker images from
# Adoptium marketplace".  Semeru 21 LTS is the current stable.
# ============================================================
FROM docker.io/library/maven:3.9.9-ibm-semeru-21-jammy AS builder

WORKDIR /build

# Cache Maven dependencies before copying source
COPY pom.xml .
RUN mvn -B -q -e -C -DskipTests dependency:go-offline \
        -Dliberty.skip=true || true

# Copy source and build — Liberty Maven plugin assembles a complete
# server tree under target/liberty/wlp/ (no zip extraction needed)
COPY src ./src
RUN mvn -B -q -DskipTests package

# ============================================================
# Stage 2 — Runtime
# IBM Semeru Runtimes JRE 21 (Eclipse OpenJ9)
# Copy the pre-assembled wlp directory directly — no unzip, no apt-get.
# ============================================================
FROM docker.io/library/ibm-semeru-runtimes:open-21-jre-jammy

# JVM tuning: container-aware memory, crash on OOM for clean restart
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75 \
    -Xdump:heap:events=systhrow+throw,filter=java/lang/OutOfMemoryError,file=/dumps/heapdump.%Y%m%d.%H%M%S.%pid.phd \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/dumps/heapdump.hprof \
    -Dfile.encoding=UTF-8"

# Copy the fully assembled Liberty server from builder stage directly.
# The Liberty Maven plugin places the complete server at target/liberty/wlp/
# which already contains: runtime, features, server.xml, and the deployed WAR.
COPY --from=builder /build/target/liberty/wlp /opt/wlp

# Copy H2 JDBC driver to Liberty shared resources so the jdbcDriver libraryRef
# in server.xml can locate it. (${shared.resource.dir} = /opt/wlp/usr/shared/resources)
COPY --from=builder /build/src/main/liberty/shared/resources/h2-2.2.224.jar \
     /opt/wlp/usr/shared/resources/h2-2.2.224.jar

# Directories for heap dumps and structured logs
RUN mkdir -p /dumps /logs \
 && chown -R 1001:0 /opt/wlp /dumps /logs \
 && chmod -R g=u /opt/wlp /dumps /logs

USER 1001

# Liberty HTTP port
EXPOSE 9080
# Liberty HTTPS port (optional)
EXPOSE 9443

# Prometheus metrics / health endpoints also served on 9080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:9080/health/ready || exit 1

ENTRYPOINT ["/opt/wlp/bin/server", "run", "defaultServer"]
