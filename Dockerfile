FROM maven:3.9-eclipse-temurin-22-alpine AS builder

WORKDIR /app

COPY pom.xml .
COPY core ./core
COPY cli ./cli

RUN apk --update upgrade && \
    apk add --no-cache libjxl && \
    ln -s /usr/lib/libjxl.so.0.10.2 /usr/lib/libjxl.so && \
    mvn -B package 
FROM eclipse-temurin:22-jre-alpine

LABEL maintainer="cmahnke@gmail.com"
LABEL org.opencontainers.image.source="https://github.com/cmahnke/fliiifenleger"

WORKDIR /app

COPY --from=builder /app/cli/target/fliiifenleger-cli.jar .

RUN apk --update upgrade && \
    apk add --no-cache libjxl && \
    ln -s /usr/lib/libjxl.so.0.10.2 /usr/lib/libjxl.so && \
    rm -rf /var/cache/apk/* /root/.cache

ENTRYPOINT ["java", "-jar", "fliiifenleger-cli.jar"]

CMD ["--help"]