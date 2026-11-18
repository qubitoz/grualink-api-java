package mx.grualink.api;

/**
 * One entry of {@code error.details} in a 400/422 response.
 *
 * @param field dotted path of the offending field, for example {@code contact.phone}
 * @param issue one of {@code required}, {@code invalid_type}, {@code too_long}, {@code invalid_format},
 *     {@code out_of_range}, {@code unknown_field}, {@code invalid_value}, {@code duplicate}
 */
public record ErrorDetail(String field, String issue) {}
