package com.cayleywcs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cayleyWcsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CayleyWCS API")
                        .description("仓库控制系统：多协议设备对接、连接治理、任务调度、实时监控")
                        .version("0.1.0"))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    /** 对外集成分组（M13）：只含 /open/**——第三方系统（WMS/ERP/其他厂商）对接文档从此组导出，避免暴露管理端接口。 */
    @Bean
    public GroupedOpenApi openIntegrationApi() {
        return GroupedOpenApi.builder()
                .group("open")
                .pathsToMatch("/open/**", "/system/token")
                .addOpenApiCustomizer(api -> {
                    for (String name : java.util.List.of("TaskEntity", "TaskChainEntity", "AlarmEntity")) {
                        Schema<?> schema = api.getComponents().getSchemas().get(name);
                        if (schema != null && schema.getProperties() != null) schema.getProperties().remove("tenant_id");
                    }
                    api.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
                    if (!"/system/token".equals(path) && operation.getRequestBody() != null) {
                        operation.getRequestBody().getContent().values().forEach(media -> {
                            Schema<?> data = media.getSchema();
                            ObjectSchema envelope = new ObjectSchema();
                            envelope.addProperty("app_id", new StringSchema());
                            envelope.addProperty("app_key", new StringSchema());
                            envelope.addProperty("access_token", new StringSchema());
                            envelope.addProperty("request_id", new StringSchema().maxLength(64));
                            envelope.addProperty("data", data);
                            envelope.setRequired(java.util.List.of("app_id", "app_key", "access_token", "data"));
                            media.setSchema(envelope);
                        });
                    }
                    if (operation.getResponses() != null) operation.getResponses().values().forEach(response -> {
                        if (response.getContent() == null) return;
                        response.getContent().values().forEach(media -> {
                            Schema<?> old = media.getSchema();
                            if (old != null && old.get$ref() != null) old = api.getComponents().getSchemas().get(old.get$ref().substring(old.get$ref().lastIndexOf('/') + 1));
                            Schema<?> data = old != null && old.getProperties() != null ? (Schema<?>) old.getProperties().get("data") : new ObjectSchema();
                            ObjectSchema envelope = new ObjectSchema();
                            envelope.addProperty("errcode", new IntegerSchema());
                            envelope.addProperty("errmsg", new StringSchema());
                            envelope.addProperty("request_id", new StringSchema());
                            envelope.addProperty("timestamp", new StringSchema().format("date-time"));
                            envelope.addProperty("data", data == null ? new ObjectSchema() : data);
                            media.setSchema(envelope);
                        });
                    });
                }));
                })
                .build();
    }

    /** 管理端分组：全部接口（JWT）。 */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/**")
                .build();
    }
}
