package sunhan.sunhanbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${file.upload.employment-dir}")
    private String employmentDir;

    @Value("${file.upload.leave-dir}")
    private String leaveDir;

    @Value("${file.upload.work-schedule-dir}")
    private String workScheduleDir;

    @Value("${file.upload.sign-dir}")
    private String signDir;

    @Value("${file.upload.consent-dir}")
    private String consentDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ⭐ React 앱 설정 - 수정된 버전
        registry.addResourceHandler("/sunhan-eap", "/sunhan-eap/", "/sunhan-eap/**")
                .addResourceLocations("classpath:/static/sunhan-eap/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location)
                            throws IOException {

                        // 빈 경로면 index.html 반환
                        if (resourcePath == null || resourcePath.isEmpty() || resourcePath.equals("/")) {
                            return location.createRelative("index.html");
                        }

                        Resource requestedResource = location.createRelative(resourcePath);

                        // 실제 파일이 존재하면 반환
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

                        // 없으면 index.html 반환 (React Router 지원)
                        Resource indexHtml = location.createRelative("index.html");
                        return (indexHtml.exists() && indexHtml.isReadable()) ? indexHtml : null;
                    }
                });

        // ⭐⭐⭐ 이 부분 추가 (기존 업로드 폴더 설정들 바로 위에)
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/sunhan-eap/")
                .resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        // API 요청은 무시
                        if (resourcePath.startsWith("api/") || resourcePath.startsWith("uploads/")) {
                            return null;
                        }

                        Resource requestedResource = location.createRelative(resourcePath);

                        // 파일이 실제로 존재하면 반환
                        if (requestedResource.exists() && requestedResource.isReadable()) {
                            return requestedResource;
                        }

                        // 나머지는 index.html (React Router)
                        Resource indexHtml = location.createRelative("index.html");
                        return (indexHtml.exists() && indexHtml.isReadable()) ? indexHtml : null;
                    }
                });

        // 기존 업로드 폴더 설정들
        registry.addResourceHandler("/uploads/employmentContract/**")
                .addResourceLocations(pathToFileUri(employmentDir));

        registry.addResourceHandler("/uploads/leave_application/**")
                .addResourceLocations(pathToFileUri(leaveDir));

        registry.addResourceHandler("/uploads/sign_image/**")
                .addResourceLocations(pathToFileUri(signDir));

        registry.addResourceHandler("/uploads/work_schedule/**")
                .addResourceLocations(workScheduleDir);

        registry.addResourceHandler("/uploads/consent_agreement/**")
                .addResourceLocations(pathToFileUri(consentDir));


        registry.addResourceHandler("/css/**")
                .addResourceLocations("classpath:/static/sunhan-eap/static/css/");

        registry.addResourceHandler("/js/**")
                .addResourceLocations("classpath:/static/sunhan-eap/static/js/");

        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/sunhan-eap/static/");
    }

    private String pathToFileUri(String path) {
        String normalized = path.replace("\\", "/");
        if (!normalized.endsWith("/")) normalized += "/";
        return "file:///" + normalized;
    }
}