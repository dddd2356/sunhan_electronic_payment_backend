package sunhan.sunhanbackend.config;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            // JSR310 (Java Time) 모듈 등록 — LocalDateTime 등 직렬화/역직렬화 지원
            builder.modulesToInstall(new JavaTimeModule(), new Hibernate6Module());
            // ISO-8601 문자열로 직렬화 (타임스탬프로 하지 않음)
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            builder.featuresToDisable(MapperFeature.REQUIRE_HANDLERS_FOR_JAVA8_TIMES);
        };
    }
}