package io.tactical.server;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.List;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(title = "Tactical Orchestration", version = "1"),
    security = @SecurityRequirement(name = "sessionBearer"))
@SecurityScheme(name = "sessionBearer", type = SecuritySchemeType.HTTP, scheme = "bearer")
class OpenApiConfiguration {
  @Bean
  OpenAPI apiModel() {
    // Schema comes from the same DTO used by the error handler, not a second JSON definition.
    return new OpenAPI()
        .components(new Components().schemas(ModelConverters.getInstance().read(ApiError.class)));
  }

  @Bean
  OperationCustomizer boundaryResponses() {
    return (operation, handler) -> {
      boolean publicEndpoint = handler.hasMethodAnnotation(SecurityRequirements.class);
      if (publicEndpoint) operation.setSecurity(List.of());
      else operation.getResponses().addApiResponse("401", error("Session Bearer token required"));
      operation
          .getResponses()
          .addApiResponse("413", error("Request body exceeds configured limit"));
      operation.getResponses().addApiResponse("415", error("Unsupported content type or encoding"));
      operation.getResponses().addApiResponse("500", error("Internal server error"));
      operation.getResponses().addApiResponse("404", error("Resource not found"));
      operation
          .getResponses()
          .addApiResponse("409", error("Stale draft version or immutable revision"));
      operation
          .getResponses()
          .addApiResponse("429", error("Local scenario resource limit reached"));
      if (operation.getRequestBody() != null) {
        operation.getResponses().addApiResponse("400", error("Invalid version or request payload"));
      }
      return operation;
    };
  }

  private ApiResponse error(String description) {
    return new ApiResponse()
        .description(description)
        .content(
            new Content()
                .addMediaType(
                    "application/json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
  }
}
