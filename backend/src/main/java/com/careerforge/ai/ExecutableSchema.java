package com.careerforge.ai;

import com.careerforge.core.ApiError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

enum ExecutableSchema {
    RESUME("resume-extract-v1"), JOB("job-extract-v1"), EDITS("tailoring-proposals-v2"),
    QUESTIONS("interview-questions-v2"), FEEDBACK("interview-feedback-v2"), FIT("fit-v3");

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ObjectNode contract;
    private final JsonSchema validator;
    private final String id;

    ExecutableSchema(String id) {
        this.id = id;
        try (var file = ExecutableSchema.class.getResourceAsStream("/contracts/" + id + ".json")) {
            var mapper = new ObjectMapper();
            contract = (ObjectNode) mapper.readTree(file);
            validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(contract);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load contract " + id, e);
        }
    }

    JsonNode providerSchema() {
        ObjectNode provider = contract.deepCopy();
        provider.remove("$schema");
        ObjectNode fields = (ObjectNode) provider.get("properties");
        fields.remove("mode");
        simplify(provider);
        return provider;
    }

    private void simplify(JsonNode node) {
        if (node instanceof ObjectNode object) {
            object.remove(Arrays.asList("minLength", "maxLength", "maxItems", "minItems"));
            object.elements().forEachRemaining(this::simplify);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(this::simplify);
        }
    }

    void validateInput(Object candidate) {
        if (!validator.validate(JSON.valueToTree(candidate)).isEmpty()) throw ApiError.invalid("Data does not match " + id);
    }

    void validateProvider(JsonNode candidate) {
        if (!validator.validate(candidate).isEmpty()) {
            throw new ApiError(HttpStatus.BAD_GATEWAY, "AI_OUTPUT_INVALID", "AI response does not match " + id);
        }
    }
}
