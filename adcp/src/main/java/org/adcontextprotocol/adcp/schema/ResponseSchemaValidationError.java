package org.adcontextprotocol.adcp.schema;

import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Set;

/**
 * Typed error thrown when a response payload fails JSON Schema validation.
 *
 * <p>This error carries structured attribution so the storyboard runner can
 * branch on validation failures — distinguishing "the agent returned invalid
 * JSON" from transport errors or business-logic rejections.
 *
 * <p>Per 7.x delta: attributes Zod/JSON-Schema rejects to {@code response_schema}
 * and short-circuits step-scope invariants. Java equivalent: typed error from
 * Jackson + json-schema-validator that the storyboard runner can branch on.
 *
 * @see AdcpSchemaValidator
 * @see ValidationResult
 */
public final class ResponseSchemaValidationError extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String schemaUri;
    @SuppressWarnings("serial")
    private final List<SchemaViolation> violations;

    /**
     * Creates a new validation error.
     *
     * @param schemaUri  the schema URI the instance was validated against
     * @param violations the list of specific violations found
     */
    public ResponseSchemaValidationError(String schemaUri, List<SchemaViolation> violations) {
        super(formatMessage(schemaUri, violations));
        this.schemaUri = schemaUri;
        this.violations = List.copyOf(violations);
    }

    /**
     * Creates a validation error from a {@link ValidationResult}.
     *
     * @param schemaUri the schema URI
     * @param result    the validation result (must not be valid)
     * @return a new error with violations extracted from the result
     * @throws IllegalArgumentException if the result is valid
     */
    public static ResponseSchemaValidationError fromResult(String schemaUri, ValidationResult result) {
        if (result.isValid()) {
            throw new IllegalArgumentException("Cannot create error from valid result");
        }
        List<SchemaViolation> violations = result.errors().stream()
                .map(msg -> new SchemaViolation(
                        msg.getInstanceLocation() != null ? msg.getInstanceLocation().toString() : "/",
                        msg.getMessage(),
                        msg.getType()
                ))
                .toList();
        return new ResponseSchemaValidationError(schemaUri, violations);
    }

    /**
     * The schema URI that the response was validated against.
     */
    public String schemaUri() {
        return schemaUri;
    }

    /**
     * The list of individual schema violations.
     */
    public List<SchemaViolation> violations() {
        return violations;
    }

    /**
     * Whether this is a response schema validation error (always true).
     * Useful for the storyboard runner to branch on error category.
     */
    public String errorCategory() {
        return "response_schema";
    }

    private static String formatMessage(String schemaUri, List<SchemaViolation> violations) {
        var sb = new StringBuilder("Response failed schema validation against ");
        sb.append(schemaUri).append(": ").append(violations.size()).append(" violation(s)");
        for (var v : violations) {
            sb.append("\n  - ").append(v.path()).append(": ").append(v.message());
        }
        return sb.toString();
    }

    /**
     * A single schema violation at a specific JSON path.
     *
     * @param path    the JSON Pointer path to the failing value (e.g. "/items/0/name")
     * @param message human-readable description of the violation
     * @param type    the validation keyword that failed (e.g. "type", "required", "pattern")
     */
    public record SchemaViolation(String path, String message, String type) {}
}
