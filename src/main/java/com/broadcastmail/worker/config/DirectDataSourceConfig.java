package com.broadcastmail.worker.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(DirectDataSourceProperties.class)
public class DirectDataSourceConfig {

    @Bean(name = "directDataSource")
    public DataSource directDataSource(DirectDataSourceProperties props) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.url());
        ds.setUsername(props.username());
        ds.setPassword(props.password());
        ds.setDriverClassName(props.driverClassName());
        if (props.hikari() != null) {
            ds.setMaximumPoolSize(props.hikari().maximumPoolSize());
            ds.setPoolName(props.hikari().poolName());
        }
        return ds;
    }
}
