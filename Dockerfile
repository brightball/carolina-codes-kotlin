# eclipse-temurin:27-* is unpublished on JDK 27 GA day; openjdk:27-rc is the official image and reports feature 27 (27+35).
FROM openjdk:27-rc AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src src
RUN chmod +x gradlew && ./gradlew --no-daemon installDist

FROM openjdk:27-rc-slim
WORKDIR /app
COPY --from=build /app/build/install/carolina-codes-kotlin /app
ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=55.0 -XX:+UseG1GC -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1"
EXPOSE 8080
CMD ["/app/bin/carolina-codes-kotlin"]
