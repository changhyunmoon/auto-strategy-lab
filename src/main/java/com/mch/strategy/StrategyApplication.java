package com.mch.strategy;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StrategyApplication {

    public static void main(String[] args) {
        // 모든 시각은 UTC로 다룬다. 화면에서만 KST로 보여준다.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(StrategyApplication.class, args);
    }
}
