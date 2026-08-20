package org.kkobi.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;

@Configuration
@EnableTransactionManagement
@PropertySource({"classpath:/application.properties"})
@ComponentScan(basePackages = {
        "org.kkobi.assessment.calculator",
        "org.kkobi.assessment.config",
        "org.kkobi.assessment.scheduler",
        "org.kkobi.assessment.service",
        "org.kkobi.assessment.validator",
        "org.kkobi.game.calculator",
        "org.kkobi.game.service",
        "org.kkobi.product.holding.service",
        "org.kkobi.account.service",
        "org.kkobi.persona.service",
        "org.kkobi.external.kis.config",
        "org.kkobi.external.kis.auth",
        "org.kkobi.external.kis.client",
        "org.kkobi.external.kis.service",
        "org.kkobi.securities.service",
        "org.kkobi.trade.service",
        "org.kkobi.trade.policy",
        "org.kkobi.trade.engine",
        "org.kkobi.trade.scheduler",
        "org.kkobi.leaderboard.service",
        "org.kkobi.event.service",
        "org.kkobi.event.game.service",
        "org.kkobi.event.leaderboard.service"
})
@MapperScan(basePackages = {
        "org.kkobi.assessment.mapper",
        "org.kkobi.game.mapper",
        "org.kkobi.product.mapper",
        "org.kkobi.account.mapper",
        "org.kkobi.securities.mapper",
        "org.kkobi.persona.mapper",
        "org.kkobi.trade.mapper",
        "org.kkobi.leaderboard.mapper",
        "org.kkobi.event.mapper",
        "org.kkobi.event.game.mapper",
        "org.kkobi.event.leaderboard.mapper",
})
@Import(RedisConfig.class)
public class RootConfig {
    @Value("${jdbc.driver}") String driver;
    @Value("${jdbc.url}") String url;
    @Value("${jdbc.username}") String username;
    @Value("${jdbc.password}") String password;

    @Bean
    public DataSource dataSource(){
        HikariConfig config = new HikariConfig();

        config.setDriverClassName(driver);
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);

        HikariDataSource dataSource = new HikariDataSource(config);
        return dataSource;
    }

    @Autowired
    ApplicationContext applicationContext;

    @Bean
    public SqlSessionFactory sqlSessionFactory() throws Exception{
        SqlSessionFactoryBean sqlSessionFactory = new SqlSessionFactoryBean();
        sqlSessionFactory.setConfigLocation(applicationContext.getResource("classpath:/mybatis-config.xml"));
        sqlSessionFactory.setDataSource(dataSource());

        return(SqlSessionFactory) sqlSessionFactory.getObject();
    }

    @Bean
    public DataSourceTransactionManager transactionManager(){
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource());

        return manager;
    }

    // 금융감독원 등 외부 API 호출에 사용하는 RestTemplate Bean 등록
    @Bean
    public RestTemplate restTemplate(){
        return new RestTemplate();
    }
}
