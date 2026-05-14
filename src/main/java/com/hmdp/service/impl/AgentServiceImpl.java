package com.hmdp.service.impl;


import com.hmdp.entity.Shop;
import com.hmdp.service.IAgentService;


import com.hmdp.service.IShopService;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.chat.SystemMessage;
import dev.ai4j.openai4j.chat.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import cn.hutool.core.util.StrUtil;
import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements IAgentService {
    @Resource
    private IShopService shopService;
@Resource
    private final OpenAiChatModel chatLanguageModel;

    @Override
    public String chat(Long userId, String userMessage) {
        // 1. 意图识别
        String intent = detectIntent(userMessage);
        String toolResult = "";

        // 2. 调用对应的工具方法
        if ("shop".equals(intent)) {
            String keyword = extractKeyword(userMessage);
            // 注意：你需要确保 IShopService 中有 searchShops 方法
            List<Shop> shops = shopService.searchShops(keyword, null);
            toolResult = shops.isEmpty()
                    ? "未找到相关店铺"
                    : shops.stream()
                    .map(s -> String.format("【%s】评分 %.1f | 地址：%s",
                            s.getName(), s.getScore(), s.getAddress()))
                    .collect(Collectors.joining("\n"));
        } else if ("order".equals(intent)) {
            // 暂时没有订单实体，直接返回提示
            toolResult = "订单功能正在开发中，您可以先浏览店铺信息。";
        }

        // 3. 组装系统提示词
        String systemPrompt = "你是黑马点评的 AI 导购小智。" +
                (toolResult.isEmpty()
                        ? "请用热情友好的语气陪用户闲聊，引导用户说出自己的需求。"
                        : "请基于以下信息回答用户：\n" + toolResult);

        // 4. 调用大模型
        List<Message> messages = Arrays.asList(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage)
        );
        ChatResponse chatResponse = chatLanguageModel.chat(
                ChatRequest.builder().messages((ChatMessage) messages).build()
        );
        String reply = chatResponse.aiMessage().text();
        log.info("AI 导购回复: {}", reply);
        return reply;
    }

    private String detectIntent(String msg) {
        if (StrUtil.containsAny(msg, "订单", "下单", "我的单", "快递", "物流", "到哪")) {
            return "order";
        }
        if (StrUtil.containsAny(msg, "推荐", "好吃", "有什么", "附近", "川菜", "火锅", "烧烤", "奶茶")) {
            return "shop";
        }
        return "chat";
    }


    private String extractKeyword(String msg) {
        // 简单去掉一些口语化前缀
        return msg.replaceAll("推荐|好吃|附近|有什么|想吃|喝", "").trim();
    }
}
