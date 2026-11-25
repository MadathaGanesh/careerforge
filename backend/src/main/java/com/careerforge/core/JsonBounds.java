package com.careerforge.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.jackson2.autoconfigure.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class JsonBounds {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer bounds() {
        return builder -> builder.postConfigurer(mapper -> {
            mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(1024 * 1024).maxNestingDepth(40)
                    .maxStringLength(300000).maxNumberLength(80).build());
            mapper.coercionConfigFor(LogicalType.Textual)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
            mapper.coercionConfigFor(LogicalType.Boolean)
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        });
    }
}
