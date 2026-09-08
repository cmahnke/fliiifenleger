# Stage 1: compile the c2pa Rust crate to wasm32-wasip1.
# Kept separate from the Maven builder: the Alpine `rust` package tracks an
# older toolchain than c2pa's MSRV and only ships a wasm32-unknown-unknown
# stdlib, so the official rust image is used instead.  The Rust toolchain,
# cargo registry, and build cache stay isolated in this stage's layers —
# nothing of it reaches the final image.
FROM rust:1-alpine AS rust-builder

RUN apk add --no-cache musl-dev

COPY jc2pa/src/main/rust /rust
WORKDIR /rust

RUN rustup target add wasm32-wasip1 && \
    cargo build --target wasm32-wasip1 --release

# Stage 2: build the Java modules with Maven.  The WASM module compiled in
# stage 1 is copied into the jc2pa resources so the Maven build uses the
# prebuilt artifact (skip-rust path) instead of invoking cargo itself.
FROM maven:3-eclipse-temurin-25-alpine AS builder

WORKDIR /app

COPY pom.xml .
COPY core ./core
COPY cli ./cli
COPY jc2pa ./jc2pa

COPY --from=rust-builder /rust/target/wasm32-wasip1/release/c2pa_wasm.wasm \
     jc2pa/src/main/resources/wasm/c2pa_wasm.wasm

RUN apk --update upgrade && \
    apk add --no-cache libjxl && \
    ln -s /usr/lib/libjxl.so.0.10.2 /usr/lib/libjxl.so && \
    mvn --batch-mode package \
        -Dmaven.cargo.skip=true -Dmaven.rustup.skip=true

FROM eclipse-temurin:25-jre-alpine

LABEL maintainer="cmahnke@gmail.com"
LABEL org.opencontainers.image.source="https://github.com/cmahnke/fliiifenleger"

WORKDIR /app

COPY --from=builder /app/cli/target/fliiifenleger-cli.jar .

RUN apk --update upgrade && \
    apk add --no-cache libjxl && \
    ln -s /usr/lib/libjxl.so.0.10.2 /usr/lib/libjxl.so && \
    rm -rf /var/cache/apk/* /root/.cache

# --enable-native-access suppresses the JDK 24+ restricted-method warning
# triggered by the JXL imageio plugin's FFM (Panama) usage.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "fliiifenleger-cli.jar"]

CMD ["--help"]
