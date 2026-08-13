FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY faultpilot-agent-jvm/pom.xml ./faultpilot-agent-jvm/pom.xml
RUN mvn -f faultpilot-agent-jvm/pom.xml -DskipTests dependency:go-offline

COPY faultpilot-agent-jvm/src ./faultpilot-agent-jvm/src
RUN mvn -f faultpilot-agent-jvm/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system faultpilot && useradd --system --gid faultpilot --home-dir /app faultpilot
WORKDIR /app
COPY --from=build /workspace/faultpilot-agent-jvm/target/faultpilot-agent-jvm-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir -p /app/data && chown -R faultpilot:faultpilot /app

USER faultpilot
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
