package sunhan.sunhanbackend.config;


import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

@Configuration
public class JpaAliasConfig {

    /**
     * Spring Data JPA가 기본으로 찾는 'entityManagerFactory' 이름의 빈을
     * 'mysqlEntityManagerFactory' 빈으로 alias 등록해 줍니다.
     */
    @Bean(name = "entityManagerFactory")
    public EntityManagerFactory entityManagerFactoryAlias(
            @Qualifier("mysqlEntityManagerFactory")
            LocalContainerEntityManagerFactoryBean mysqlEmfBean) {
        // LocalContainerEntityManagerFactoryBean.getObject()가 실제 EMF 인스턴스를 반환합니다.
        return mysqlEmfBean.getObject();
    }
}
