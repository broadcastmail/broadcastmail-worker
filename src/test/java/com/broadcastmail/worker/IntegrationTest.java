package com.broadcastmail.worker;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class IntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestContainersConfiguration.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", TestContainersConfiguration.POSTGRES::getUsername);
        registry.add("spring.datasource.password", TestContainersConfiguration.POSTGRES::getPassword);
        registry.add("app.datasource.direct.url", TestContainersConfiguration.POSTGRES::getJdbcUrl);
        registry.add("app.datasource.direct.username", TestContainersConfiguration.POSTGRES::getUsername);
        registry.add("app.datasource.direct.password", TestContainersConfiguration.POSTGRES::getPassword);
    }
}
