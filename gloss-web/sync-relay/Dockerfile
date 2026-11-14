FROM dart:3.13 AS build

WORKDIR /source
COPY pubspec.yaml pubspec.lock ./
RUN dart pub get --enforce-lockfile
COPY analysis_options.yaml ./
COPY bin ./bin
COPY lib ./lib
RUN mkdir -p /output && dart compile exe bin/relay.dart -o /output/gloss-relay

# Distroless with glibc for the AOT binary; runs as uid 65532 (nonroot).
FROM gcr.io/distroless/cc-debian12:nonroot

COPY --from=build /output/gloss-relay /gloss-relay
USER nonroot:nonroot
ENV PORT=8080
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["/gloss-relay"]
