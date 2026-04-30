package com.hmdp.config;

import com.hmdp.utils.RefreashTokenInterceptor;
import com.hmdp.utils.loginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
  @Resource
  private StringRedisTemplate stringRedisTemplate;

    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new loginInterceptor(stringRedisTemplate))
                .excludePathPatterns(//放行
                        "/voucher/**",
                        "/user/code",
                        "/user/login",
                        "/blog/hot",
                        "/shop/**",
                        "/upload/**",
                        "/shop-type"

                ).order(1);
        registry.addInterceptor(new RefreashTokenInterceptor(stringRedisTemplate)).excludePathPatterns(
                "/**"
        ).order(0);
    }
}
