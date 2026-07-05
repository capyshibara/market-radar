package vn.techcomlife.marketradar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MarketRadarApplication {
    public static void main(String[] args) {
        SpringApplication.run(MarketRadarApplication.class, args);
    }
}
