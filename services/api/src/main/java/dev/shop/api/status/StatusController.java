package dev.shop.api.status;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class StatusController {

    private static final String UNKNOWN_VERSION = "unknown";

    private final ObjectProvider<BuildProperties> buildProperties;

    StatusController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping(path = "/api/status", produces = MediaType.APPLICATION_JSON_VALUE)
    StatusResponse status() {
        String version = buildProperties.stream()
                .map(BuildProperties::getVersion)
                .findFirst()
                .orElse(UNKNOWN_VERSION);
        return new StatusResponse("ok", version);
    }

    record StatusResponse(String status, String version) {}
}
