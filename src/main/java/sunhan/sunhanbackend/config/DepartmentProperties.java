package sunhan.sunhanbackend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "department")
public class DepartmentProperties {
    private Map<String, String> names = new HashMap<>();
}
