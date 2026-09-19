package com.iitm.beacon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BeaconApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeaconApplication.class, args);
    }
}
