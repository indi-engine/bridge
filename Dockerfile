# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY compose/debezium/pom.xml .
COPY compose/debezium/Bridge.java ./src/main/java/Bridge.java
# Force the SLF4J service provider file into the JAR — the shade plugin drops it otherwise
COPY compose/debezium/src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider \
     ./src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider

RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jdk
WORKDIR /app

COPY --from=build /build/target/bridge.jar .
COPY compose/debezium/properties .
COPY compose/debezium/logback.xml .

# Ensure /data dir exists for offset + schema history files
RUN mkdir -p /data

COPY compose/debezium/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh

ENTRYPOINT ["bash", "-c", "source /usr/local/bin/docker-entrypoint.sh"]
