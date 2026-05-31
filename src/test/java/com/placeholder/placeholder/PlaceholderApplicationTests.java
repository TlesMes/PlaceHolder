package com.placeholder.placeholder;

import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PlaceholderApplicationTests extends MySQLIntegrationTest {

    @Test
    void contextLoads() {
    }

}
