# Two stages: build with the JDK, run on the JRE.
#
# The runtime image has no Maven, no source and no build cache, which keeps it
# small and means the thing you run is only the thing you shipped.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first, so a source change does not re-download the internet.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped HERE and only here: they need Docker themselves, via
# Testcontainers, and a container cannot reliably start containers. Run them on
# the host with `mvn test`, which is what CI does.
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache wget
COPY --from=build /build/target/digital-wallet-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
