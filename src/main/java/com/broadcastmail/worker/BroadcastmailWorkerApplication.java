package com.broadcastmail.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaRepositories(basePackages = {"com.broadcastmail.worker", "com.broadcastmail.common"})
@EntityScan(basePackages = {"com.broadcastmail.worker", "com.broadcastmail.common"})
public class BroadcastmailWorkerApplication {

	public static void main(String[] args) {
		SpringApplication.run(BroadcastmailWorkerApplication.class, args);
	}

}
