package sunhan.sunhanbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {

// React 정적 파일들을 서빙하고, 존재하지 않는 경로는 index.html로 포워딩*

        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requestedResource = location.createRelative(resourcePath);

// API 경로는 제외*

                        if (resourcePath.startsWith("api/")) {
                            return null;
                        }

// 파일이 존재하면 반환*

                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

// 파일이 없으면 index.html 반환 (React Router 지원)*

                        return location.createRelative("index.html");
                    }
                });
    }
}
