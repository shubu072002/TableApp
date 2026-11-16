package com.fci.tableapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // closes idle DB sessions
public class TableExportApplication {
    public static void main(String[] args) {
        SpringApplication.run(TableExportApplication.class, args);
    }
}
