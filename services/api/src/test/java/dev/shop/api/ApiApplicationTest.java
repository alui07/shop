package dev.shop.api;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class ApiApplicationTest {

    @Test
    void mainStartsTheSpringApplicationWithTheGivenArguments() {
        String[] args = {"--spring.main.banner-mode=off"};

        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            ApiApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(ApiApplication.class, args));
        }
    }
}
