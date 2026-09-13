package com.reviewpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Spring Boot 应用启动入口，负责引导整个代码评审服务。 */
@SpringBootApplication
/** ReviewPilot 应用的 Spring Boot 启动类，负责启动整个后端服务。 */
public class ReviewPilotApplication {

    public static void main(String[] args) { SpringApplication.run(ReviewPilotApplication.class, args); }

}
