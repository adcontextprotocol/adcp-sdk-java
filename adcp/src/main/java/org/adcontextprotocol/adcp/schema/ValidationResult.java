package org.adcontextprotocol.adcp.schema;

import com.networknt.schema.ValidationMessage;

import java.util.Set;

/**
 * Result of a schema validation operation. Contains the set of validation
 * errors (empty if validation passed).
 *
 * @param errors the set of validation messages (empty on success)
 */
public record ValidationResult(Set<ValidationMessage> errors) {

    /**
     * Returns {@code true} if the instance is valid against the schema.
     */
    public boolean isValid() {
        return errors.isEmpty();
    }

    /**
     * Returns a human-readable summary of all validation errors.
     */
    public String summary() {
        if (isValid()) return "valid";
        var sb = new StringBuilder();
        for (var error : errors) {
            sb.append(error.getMessage()).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
