package org.kkobi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springdoc.core.*;
import org.springdoc.webmvc.core.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.datetime.standard.DateTimeFormatterRegistrar;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewResolverRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.servlet.view.JstlView;

import java.util.List;

@Configuration
@EnableWebMvc
@ComponentScan(basePackages = {
        "org.kkobi.controller",
        "org.kkobi.exception",
        "org.kkobi.users.controller",
        "org.kkobi.product.controller",
        "org.kkobi.product.deposit.controller",
        "org.kkobi.product.saving.controller",
        "org.kkobi.product.holding.controller",
        "org.kkobi.game.controller",
        "org.kkobi.assessment.controller",
        "org.kkobi.account.controller",
        "org.kkobi.persona.controller",
        "org.kkobi.external.kis.controller",
        "org.kkobi.external.kis.websocket",
        "org.kkobi.securities.controller",
        "org.kkobi.trade.controller",
        "org.kkobi.leaderboard.controller",
        "org.kkobi.quiz.controller",
        "org.kkobi.notification.controller",
        "org.kkobi.event.controller",
        "org.kkobi.event.game.controller"
})
@Import({
        SpringDocConfigProperties.class,
        SpringDocConfiguration.class,
        SpringDocUIConfiguration.class,
        SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,
        SwaggerUiConfigProperties.class,
        SwaggerUiOAuthProperties.class,
        SwaggerConfig.class,
        CacheOrGroupedOpenApiCondition.class,
        JacksonAutoConfiguration.class,
        OpenApiConfig.class
})
public class ServletConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry){
        registry
                .addResourceHandler("/resources/**")
                .addResourceLocations("/resources/");
    }

    @Override
    public void configureViewResolvers(ViewResolverRegistry registry){
        InternalResourceViewResolver bean = new InternalResourceViewResolver();

        bean.setViewClass(JstlView.class);
        bean.setPrefix("/WEB-INF/views/");
        bean.setSuffix(".jsp");

        registry.viewResolver(bean);
    }

    // @ModelAttribute 바인딩 시 LocalDate 등 java.time 타입을 ISO 포맷(yyyy-MM-dd)으로 파싱.
    // @EnableWebMvc 기본 ConversionService는 로케일 기반 SHORT 포맷을 사용하므로 명시적으로 등록 필요.
    @Override
    public void addFormatters(FormatterRegistry registry) {
        DateTimeFormatterRegistrar registrar = new DateTimeFormatterRegistrar();
        registrar.setUseIsoFormat(true);
        registrar.registerFormatters(registry);
    }

    @Bean
    public MultipartResolver multipartResolver(){
        StandardServletMultipartResolver resolver = new StandardServletMultipartResolver();
        return resolver;
    }

    // LocalDate/LocalDateTime을 ISO 문자열로 직렬화하도록 Jackson 컨버터를 등록한다.
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        converters.add(new ByteArrayHttpMessageConverter());
        converters.add(new MappingJackson2HttpMessageConverter(mapper));
    }
}
