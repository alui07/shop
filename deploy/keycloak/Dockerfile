ARG KEYCLOAK_IMAGE

FROM ${KEYCLOAK_IMAGE} AS build
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true
RUN /opt/keycloak/bin/kc.sh build

# The optimised build is required: without it Keycloak re-augments at start-up and writes into /opt/keycloak/lib,
# which a read-only root filesystem forbids.
FROM ${KEYCLOAK_IMAGE}
COPY --from=build --chown=1000:0 /opt/keycloak/ /opt/keycloak/
COPY --chown=1000:0 shop-realm.json /opt/keycloak/data/import/shop-realm.json
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
