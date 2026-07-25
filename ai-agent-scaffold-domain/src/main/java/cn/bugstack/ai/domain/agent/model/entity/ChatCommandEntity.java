package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 一次复合聊天请求；身份字段必须由可信调用方填充。 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatCommandEntity {

    /** 本次运行的 Agent。 */
    private String agentId;
    /** 可信用户。 */
    private String userId;
    /** 平台会话；新会话命令可为空。 */
    private String sessionId;
    /** 文本内容列表。 */
    private List<Content.Text> texts;
    /** 外部文件引用列表。 */
    private List<Content.File> files;
    /** 小型内联二进制列表。 */
    private List<Content.InlineData> inlineDatas;

    /** 复合内容命名空间。 */
    @Data
    public static class Content {

        /** 单条文本内容。 */
        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Text {
            /** 用户文本。 */
            private String message;
        }

        /** 可由模型读取的外部文件引用。 */
        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class File {
            /** 文件 URI。 */
            private String fileUri;
            /** 文件 MIME。 */
            private String mimeType;
        }

        /** 直接随请求携带的小型内容。 */
        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class InlineData {
            /** 原始字节。 */
            private byte[] bytes;
            /** 内容 MIME。 */
            private String mimeType;
        }

    }

    /** 构造只包含 Agent 和用户的新会话命令。 */
    public ChatCommandEntity buildSessionCommand(String agentId, String userId) {
        ChatCommandEntity chatCommandEntity = new ChatCommandEntity();
        chatCommandEntity.setAgentId(agentId);
        chatCommandEntity.setUserId(userId);
        return chatCommandEntity;
    }

    /** 构造单文本聊天命令。 */
    public ChatCommandEntity buildChatCommand(String agentId, String userId, String message) {
        ChatCommandEntity chatCommandEntity = new ChatCommandEntity();
        chatCommandEntity.setAgentId(agentId);
        chatCommandEntity.setUserId(userId);

        List<Content.Text> texts = new ArrayList<>();
        texts.add(new Content.Text(message));

        chatCommandEntity.setTexts(texts);

        return chatCommandEntity;
    }

}
