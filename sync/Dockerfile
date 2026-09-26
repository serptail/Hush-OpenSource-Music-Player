FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle :sync:installDist --no-daemon

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && pip3 install --no-cache-dir --break-system-packages --upgrade yt-dlp \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/sync/build/install/sync/ /opt/hush-sync/
ENV YTDLP_PATH=/usr/local/bin/yt-dlp
EXPOSE 8080
ENTRYPOINT ["/opt/hush-sync/bin/sync"]
