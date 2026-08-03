package com.marketradar.report;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.time.Duration;
import java.util.Locale;

/**
 * Batch 7 (i18n): mặc định TIẾNG ANH, chuyển sang tiếng Việt qua link ?lang=vi
 * (LocaleChangeInterceptor đọc param "lang" trên MỌI request, ghi vào cookie —
 * không cần session, không cần lang= lặp lại trên các link sau đó).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final boolean legacyDesksEnabled;

    public WebConfig(@Value("${marketradar.legacy-desks.enabled:false}") boolean legacyDesksEnabled) {
        this.legacyDesksEnabled = legacyDesksEnabled;
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("mr-lang");
        resolver.setDefaultLocale(Locale.ENGLISH);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor lci = new LocaleChangeInterceptor();
        lci.setParamName("lang");
        return lci;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
        registry.addInterceptor(new LegacyDeskAccessGuard(legacyDesksEnabled))
                .addPathPatterns("/desks", "/desks/**",
                        "/report/product", "/report/product/**",
                        "/product/special-issues", "/product/special-issues/**",
                        "/report/weekly", "/report/weekly/**");
    }
}
