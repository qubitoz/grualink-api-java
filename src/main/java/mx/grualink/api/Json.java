package mx.grualink.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/** The one JSON mapper of the library: unknown fields are ignored, trailing garbage is not. */
final class Json {
  static final ObjectMapper MAPPER = JsonMapper.builder()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  private Json() {}
}
