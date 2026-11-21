ARG JRE_IMAGE

# Expects target/extracted from make api-build, which packages the jar with the cached Maven repository.
FROM ${JRE_IMAGE}
# hadolint ignore=DL3017
RUN apk upgrade --no-cache
WORKDIR /app
COPY target/extracted/dependencies/ ./
COPY target/extracted/spring-boot-loader/ ./
COPY target/extracted/snapshot-dependencies/ ./
COPY target/extracted/application/ ./
EXPOSE 8080 8081
USER 10001:10001
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
