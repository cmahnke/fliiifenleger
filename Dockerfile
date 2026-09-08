FROM maven:3-eclipse-temurin-26-alpine AS builder

WORKDIR /app

COPY pom.xml .
COPY core ./core
COPY cli ./cli
COPY jc2pa ./jc2pa

RUN apk --update upgrade && \
    apk add --no-cache libjxl binaryen rust-wasm && \
    ln -s /usr/lib/libjxl.so.0.10.2 /usr/lib/libjxl.so && \
    mvn -B package
FROM eclipse-temurin:25-jre-alpine

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
