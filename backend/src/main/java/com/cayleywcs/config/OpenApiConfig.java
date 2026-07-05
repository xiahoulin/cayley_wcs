package com.cayleywcs.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                .pathsToMatch("/open/**")
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
