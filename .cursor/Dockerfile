# eclipse-temurin:27-* is unpublished on JDK 27 GA day; Debian tag keeps apt-get for git/curl.
FROM openjdk:27-rc-bookworm
USER root
RUN apt-get update \
 && apt-get install -y --no-install-recommends git curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* || true
