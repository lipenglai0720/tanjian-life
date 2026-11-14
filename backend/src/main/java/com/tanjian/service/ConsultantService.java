package com.tanjian.service;

import com.tanjian.tools.AiSkillTool;
import com.tanjian.tools.OrderTool;
import com.tanjian.tools.ShopTool;
import com.tanjian.tools.VoucherTool;
import com.tanjian.utils.ReservationTool;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;
import reactor.core.publisher.Flux;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "openAiChatModel",
        streamingChatModel = "openAiStreamingChatModel",
        chatMemoryProvider = "chatMemoryProvider",
        tools = {"shopTool", "voucherTool", "orderTool", "reservationTool", "aiSkillTool"}
)
public interface ConsultantService {
    @SystemMessage(fromResource = "system.txt")
    Flux<String> chat(@MemoryId String memoryId, @UserMessage String message);
}
