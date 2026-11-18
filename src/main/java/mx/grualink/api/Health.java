package mx.grualink.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Answer of {@code GET /health}: {@code {"status":"ok","version":"v1"}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Health(String status, String version) {}
