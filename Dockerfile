FROM eclipse-temurin:26-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src src
RUN chmod +x gradlew && ./gradlew --no-daemon installDist

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /app/build/install/carolina-codes-kotlin /app
ENV PORT=8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=55.0 -XX:+UseG1GC -XX:ActiveProcessorCount=1 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
CMD ["/app/bin/carolina-codes-kotlin"]
