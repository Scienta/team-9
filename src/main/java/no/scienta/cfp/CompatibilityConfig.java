package no.scienta.cfp;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(CompatibilityConfig.class);

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

    /**
     * A query or form parameter Tomcat can't decode ({@code ?sort=%zz}) was dropped by Tomcat 9 and the request went
     * on without it. Tomcat 11 still drops it but also throws on the first parameter read, which became a 400 with an
     * ERROR stack trace. Parameters are parsed here once, and the failure swallowed: Tomcat parses only once, so later
     * reads see the parameters that did decode, as on 2.7.
     */
    @Bean
    public FilterRegistrationBean<Filter> lenientParametersFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>((request, response, chain) -> {
            try {
                request.getParameterMap();
            } catch (InvalidParameterException e) {
                log.debug("ignored undecodable request parameters: " + e.getMessage());
            }
            chain.doFilter(request, response);
        });
        registration.setName("lenientParametersFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
