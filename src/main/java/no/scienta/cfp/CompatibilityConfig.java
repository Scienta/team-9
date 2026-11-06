package no.scienta.cfp;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.UrlHandlerFilter;

/**
 * Behaviour Spring Boot 2.7 had and Boot 4 dropped, kept so clients see no difference
 * (workflow/specs/spring-boot-4.md).
 */
@Configuration
public class CompatibilityConfig {

    /**
     * {@code /speakers/} and {@code /speakers/{id}/} must keep working like the URLs without the slash (the legacy
     * web client sends them). Boot 2.7 matched a trailing slash on every path; Spring 6+ doesn't, so the slash is
     * stripped before the request reaches the controllers. Runs right after {@link RequestLoggingFilter}, so the log
     * still shows the path the client sent.
     */
    @Bean
    public FilterRegistrationBean<UrlHandlerFilter> trailingSlashFilter() {
        FilterRegistrationBean<UrlHandlerFilter> registration = new FilterRegistrationBean<>(
                UrlHandlerFilter.trailingSlashHandler("/**").wrapRequest().build());
        registration.setName("trailingSlashFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * Error bodies were {@code application/json;charset=UTF-8} on 2.7, like every other response. Boot 4 loses the
     * forced charset on the dispatch to /error, so it's set again there.
     */
    @Bean
    public FilterRegistrationBean<Filter> errorCharsetFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            chain.doFilter(request, response);
        });
        registration.setName("errorCharsetFilter");
        registration.setDispatcherTypes(DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
