package cn.bugstack.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话请求的命令对象，支持把文本、外部文件和小块内联数据放在同一条消息里发给模型。
 *
 * <p>所属层次：领域层的实体（命令实体），只装数据不含业务判断。</p>
 *
 * <p>谁会调用它：{@code ChatService#handleMessage(ChatCommandEntity)} 这条复合消息入口，
 * 由 trigger 层或领域内部先把请求内容归类装进来，再统一转成 ADK 的 Content 结构。</p>
 *
 * <p>身份字段必须由可信调用方填充：userId 决定这条消息写进谁的会话，
 * 如果直接用前端传来的值，任何人都能往别人的会话里塞消息。</p>
 *
 * <p>它不负责什么：不校验附件是否存在、不判断会话归属、不调用模型，这些都在 ChatService 里做。</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatCommandEntity {

    /** 本次对话使用哪个已装配好的 Agent；装配链按它注册 Runner，取不到就说明配置没装配成功。 */
    private String agentId;
    /** 本次对话真正归属的用户编号，必须是可信身份；会话查询和消息落库都以它做归属校验，防止越权。 */
    private String userId;
    /** 消息挂在哪个平台会话下；新建会话的命令里可以为空，由 ChatService 先建会话再补上。 */
    private String sessionId;
    /** 用户这次说的话，按段拆成多条；转成 ADK Content 时按顺序拼进同一轮输入，顺序错了语义就变了。 */
    private List<Content.Text> texts;
    /** 让模型自己去读的外部文件引用（只传 URI 不传内容），适合大文件，避免把二进制塞进请求体。 */
    private List<Content.File> files;
    /** 直接随请求携带的小块二进制（如截图），不落对象存储，因此只适合小体积内容。 */
    private List<Content.InlineData> inlineDatas;

    /**
     * 复合内容的命名空间，本身不存数据，只把三种输入形态的定义收在一起，避免类名污染外层包。
     *
     * <p>它不负责什么：不做格式校验、不做大小限制，这些约束由上层服务和存储层决定。</p>
     */
    @Data
    public static class Content {

        /**
         * 一段纯文本输入。
         *
         * <p>最常见的一种内容，用户在输入框里敲的字最终就落在这里。</p>
         */
        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Text {
            /** 用户原始文本；会原样进入模型输入并落库成用户消息，因此不要在这里做截断或改写。 */
            private String message;
        }

        /**
         * 一个由模型自行读取的外部文件引用。
         *
         * <p>只带地址和类型，文件本体留在对象存储里。模型侧按 URI 拉取，
         * 所以 URI 必须是模型服务能访问到的地址，本机路径是拉不到的。</p>
         */
        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class File {
            /** 文件的可访问地址，交给模型侧去拉取；地址失效时模型看不到文件内容但不会中断对话。 */
            private String fileUri;
            /** 文件的 MIME 类型，模型据此决定按图片、音频还是文档来解析；填错会导致内容被当成乱码。 */
            private String mimeType;
        }

        /**
         * 一块随请求一起发送的小型二进制内容。
         *
         * <p>与 File 的区别：内容直接放在内存里传走，不经过对象存储，链路更短但体积必须小，
         * 否则会撑爆请求体和模型的单次输入上限。</p>
         */
        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        public static class InlineData {
            /** 原始字节内容，直接进入模型请求体；体积过大会导致请求被拒或超时。 */
            private byte[] bytes;
            /** 这块字节的 MIME 类型，模型靠它判断该怎么解码；缺失或填错内容就无法被识别。 */
            private String mimeType;
        }

    }

    /**
     * 组装一条「只是要建会话」的命令：只带 Agent 和用户，不带任何消息内容。
     *
     * <p>用在用户还没说话、前端先要一个 sessionId 的场景。返回的是新对象，不改动当前实例，
     * 因此可以安全地在任意实例上调用。</p>
     *
     * <p>不写库、不调模型，只做对象组装。</p>
     */
    public ChatCommandEntity buildSessionCommand(String agentId, String userId) {
        // 新建一个空命令，避免污染调用方手里的那个实例。
        ChatCommandEntity chatCommandEntity = new ChatCommandEntity();
        // 指定会话要绑定的 Agent，后续这个会话的每轮对话都用它执行。
        chatCommandEntity.setAgentId(agentId);
        // 指定会话归属的用户，落库后这个用户之外的人不能读写该会话。
        chatCommandEntity.setUserId(userId);
        // 返回只含身份信息的命令；texts 等内容字段留空，表示这次不发消息。
        return chatCommandEntity;
    }

    /**
     * 组装一条最常见的「发一句话」命令：身份 + 一段文本。
     *
     * <p>把单条字符串包成只有一个元素的文本列表，让单文本请求也能走复合内容那套统一处理逻辑，
     * 避免为简单场景再写一条分支。</p>
     *
     * <p>返回新对象，不写库、不调模型。sessionId 留空，由上层决定是复用已有会话还是新建。</p>
     */
    public ChatCommandEntity buildChatCommand(String agentId, String userId, String message) {
        // 新建命令对象，保证返回值与调用方持有的实例互不影响。
        ChatCommandEntity chatCommandEntity = new ChatCommandEntity();
        // 绑定执行本轮对话的 Agent。
        chatCommandEntity.setAgentId(agentId);
        // 绑定消息归属的可信用户。
        chatCommandEntity.setUserId(userId);

        // 准备文本容器；即使只有一句话也用列表承载，和多段文本走同一条转换路径。
        List<Content.Text> texts = new ArrayList<>();
        // 把这句话包成一条文本内容放进去。
        texts.add(new Content.Text(message));

        // 把文本列表挂到命令上，后续转成 ADK 输入时按列表顺序拼接。
        chatCommandEntity.setTexts(texts);

        // 返回可直接发给模型的完整命令。
        return chatCommandEntity;
    }

}
