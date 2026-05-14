package com.hmdp.config;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Data
@Configuration
public class LangChain4jConfig {

    @Value("${langchain4j.openai.base-url}")
    private String baseUrl;

    @Value("${langchain4j.openai.api-key}")
    private String apiKey;

    @Value("${langchain4j.openai.model-name}")
    private String modelName;

    @Value("${langchain4j.openai.timeout:60s}")
    private Duration timeout;

    @Value("${langchain4j.openai.max-retries:2}")
    private Integer maxRetries;

    @Value("${langchain4j.openai.temperature:0.7}")
    private Double temperature;

    @Value("${langchain4j.openai.log-requests:false}")
    private Boolean logRequests;

    @Value("${langchain4j.openai.log-responses:false}")
    private Boolean logResponses;

    /**
     * 核心 Bean：聊天语言模型
     * 通过 baseUrl 可指向任何兼容 OpenAI 格式的 API（豆包、DeepSeek、通义千问等）
     */
    @Bean
    public OpenAiChatModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)         // 如 https://ark.cn-beijing.volces.com/api/v3
                .apiKey(apiKey)
                .modelName(modelName)     // 如 doubao-pro-32k
                .timeout(timeout)
                .maxRetries(maxRetries)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}