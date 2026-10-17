package com.dutyscheduler.duty.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * The client's built files, which the image copies into {@code static/}.
 *
 * <p>
 * Vite names every asset by a hash of its contents, so a file under
 * {@code /assets} never changes: a browser may keep it for a year. The page
 * itself, which names those files, is left to the default and fetched fresh,
 * so a new release is picked up on the next load. In development nothing is
 * here; Vite serves the client and this has nothing to serve.
 */
@Configuration
public class StaticAssetsConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
