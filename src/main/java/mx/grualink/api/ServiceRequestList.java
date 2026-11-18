package mx.grualink.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Answer of {@code GET /service-requests?external_ref=...}: up to 20 requests, most recent first. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ServiceRequestList(String object, List<ServiceRequest> data) {}
