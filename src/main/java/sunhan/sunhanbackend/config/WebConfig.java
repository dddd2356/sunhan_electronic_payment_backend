package sunhan.sunhanbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${file.upload.employment-dir}")
    private String employmentDir;

    @Value("${file.upload.leave-dir}")
    private String leaveDir;

    @Value("${file.upload.sign-dir}")
    private String signDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 로컬 개발용 상대경로 업로드 폴더
        // employment
        registry.addResourceHandler("/uploads/employmentContract/**")
                .addResourceLocations(pathToFileUri(employmentDir));

        // leave
        registry.addResourceHandler("/uploads/leave_application/**")
                .addResourceLocations(pathToFileUri(leaveDir));

        // sign
        registry.addResourceHandler("/uploads/sign_image/**")
                .addResourceLocations(pathToFileUri(signDir));

    }

    private String pathToFileUri(String path) {
        // e.g. "file:///C:/path/to/dir/"
        String normalized = path.replace("\\", "/");
        if (!normalized.endsWith("/")) normalized += "/";
        return "file:///" + normalized;
    }
}