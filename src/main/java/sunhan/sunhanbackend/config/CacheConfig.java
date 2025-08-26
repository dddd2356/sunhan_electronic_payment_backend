package sunhan.sunhanbackend.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching // Enables Spring's caching annotations
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager("userCache","userRoleCache", "deptCache", "deptJobLevelCache", "signatureCache", "deptManagerCache","hrStaffCache","jobLevelCache","jobLevelUsersCache","formTemplate");
    }
}