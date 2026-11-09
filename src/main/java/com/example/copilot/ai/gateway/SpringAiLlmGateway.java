package com.example.copilot.ai.gateway;

import com.example.copilot.ai.entity.AiSetting;
import com.example.copilot.ai.mapper.AiSettingMapper;
import com.example.copilot.common.exception.BusinessException;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LlmGateway 的唯一生产实现：用 Spring AI 1.0 的 OpenAI 兼容协议装配对话与向量模型，
 * 把业务层的 ChatTurn / 文本翻译成供应商请求，并把响应收敛成 LlmChatResult。
 * <p>在 AI 链路中的位置：所有大模型调用（RAG 问答、知识库向量化、系统设置页连接测试）
 * 都经过这里；运行期配置来自 ai_setting 单行表，可在阿里云百炼（BAILIAN，qwen-plus +
 * text-embedding-v4，1024 维）与本机 Ollama（OLLAMA，deepseek-r1:8b + nomic-embed-text，
 * 768 维）之间切换，业务代码零改动。
 * <p>关键设计：
 * <ul>
 *   <li>按配置哈希缓存 ModelBundle，避免每次调用都重建 OpenAiApi / RestClient；缓存超过
 *       6 个配置时整体清空，配置变更后由业务层调用 clearCache 主动失效。</li>
 *   <li>连接超时 10 秒、读取超时 180 秒：连接要快失败，而大模型生成可能很慢。</li>
 *   <li>completionsPath / embeddingsPath 固定为 OpenAI 兼容路径，使同一套代码兼容百炼与
 *       Ollama 的兼容端点。</li>
 *   <li>Ollama 不传 dimensions 参数（其端点不支持该参数会报错），百炼等才按配置维度传。</li>
 *   <li>底层异常统一转成 502 BusinessException，并只暴露截断后的根因摘要，避免泄漏
 *       堆栈、密钥或供应商内部信息。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringAiLlmGateway implements LlmGateway {

    /** 读取 ai_setting 单行运行时配置；模型缓存与元信息方法都依赖它。 */
    private final AiSettingMapper settingMapper;
    /**
     * 模型对象缓存：key 为配置哈希、value 为该配置下可复用的 ModelBundle。
     * 用 ConcurrentHashMap 保证并发调用下缓存读写安全；配置一变，key 变，自然命中不到旧对象。
     */
    private final Map<String, ModelBundle> cache = new ConcurrentHashMap<>();

    /**
     * 一组与某份配置绑定的模型对象：对话模型、向量模型及其来源配置。
     * <p>把配置与两个重对象打包缓存，chat / embed 共用同一份 OpenAiApi 连接设置；
     * 用 record 表达不可变快照，缓存命中后直接复用，避免重复构建与连接开销。
     *
     * @param setting        生成本 Bundle 的运行时配置，同时作为元信息来源
     * @param chatModel      已按对话模型与默认参数装配好的 Spring AI 对话模型
     * @param embeddingModel 已按向量模型与维度装配好的 Spring AI 向量模型
     */
    public record ModelBundle(AiSetting setting, OpenAiChatModel chatModel, EmbeddingModel embeddingModel) {
    }

    /**
     * 读取当前生效的运行时配置（ai_setting 单行表）。
     * <p>边界：表为空（尚未在系统设置中配置）时抛 503 SERVICE_UNAVAILABLE，
     * 而不是让后续调用带着空配置产生难以定位的空指针。每次都查库而非缓存配置本身，
     * 使改配置后立即可见；重对象的复用由 buildBundle 负责。
     *
     * @return 当前单行配置，非 null
     * @throws BusinessException 503，配置缺失
     */
    public AiSetting currentSetting() {
        AiSetting setting = settingMapper.selectById(AiSetting.SINGLETON_ID);
        // 单行表理论上恒有一行；为空说明系统尚未初始化配置，以 503 快速失败，避免空配置下游报错。
        if (setting == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI 模型尚未配置，请先在系统设置中配置");
        }
        return setting;
    }

    /**
     * 清空模型缓存。系统设置页保存配置后由业务层调用。
     * <p>为什么必须清：缓存的 ModelBundle 绑定旧 baseUrl / apiKey / 模型 / 维度，
     * 不清就会继续用旧连接与旧模型应答；清空后下次调用按新配置重新装配。
     * 注意切换 Embedding 模型或维度后，除清缓存外还必须重建全部向量索引，否则检索维度不匹配。
     */
    public void clearCache() {
        // 整体清空而非逐条删除：配置切换后旧 Bundle 全部作废，下次调用按新配置重建。
        cache.clear();
    }

    /**
     * 按配置获取（或创建并缓存）对应的 ModelBundle。
     * <p>流程：先用配置哈希查缓存，命中即复用；未命中且缓存已超过 6 份时整体清空，
     * 再调用 createBundle 装配新对象放入缓存。
     * <p>边界：缓存淘汰采用超过 6 个整体清空的粗粒度策略，实现简单且配置切换本不频繁；
     * 并发下可能重复创建，但 put 覆盖不影响正确性。
     *
     * @param setting 本次调用使用的配置
     * @return 与该配置绑定的可复用模型组
     */
    public ModelBundle buildBundle(AiSetting setting) {
        // 缓存 key 由 provider/baseUrl/apiKey/chatModel/embeddingModel/dim/temperature/maxTokens 拼成，任一配置变化即失效。
        String key = cacheKey(setting);
        ModelBundle bundle = cache.get(key);
        // 命中缓存直接复用已装配的 OpenAiApi 与两个模型，省去重建连接与选项的开销。
        if (bundle != null) {
            return bundle;
        }
        // 缓存淘汰：不同配置切换会不断留下旧 Bundle，超过 6 个时整体清空，避免长期堆积与占用连接资源。
        if (cache.size() > 6) {
            // 粗粒度清空而非逐条 LRU，实现简单；随后本次调用立即重建回填。
            cache.clear();
        }
        // 未命中：按当前配置新建模型组（含超时、路径、维度分支），放入缓存供后续复用。
        ModelBundle created = createBundle(setting);
        cache.put(key, created);
        return created;
    }

    /**
     * 依据一份配置完整装配一套 OpenAI 兼容模型对象，是缓存未命中时的实际构造逻辑。
     * <p>关键规则：
     * <ul>
     *   <li>连接超时 10 秒、读取超时 180 秒，分别控制建连与等待生成；</li>
     *   <li>completionsPath 固定 /chat/completions、embeddingsPath 固定 /embeddings，
     *       以 OpenAI 兼容协议统一百炼与 Ollama；</li>
     *   <li>对话默认参数：温度缺省 0.3、最大 Token 缺省 1500，实际调用可由 chatWith 覆盖；</li>
     *   <li>只有非 Ollama 且配置维度大于 0 时才传 dimensions；Ollama 端点不接收该参数，
     *       传入会报错，故其维度交给模型自身决定；</li>
     *   <li>encodingFormat 用 float，返回浮点向量便于余弦相似度计算。</li>
     * </ul>
     *
     * @param setting 运行时配置
     * @return 新装配的模型组
     */
    private ModelBundle createBundle(AiSetting setting) {
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        // 连接超时 10 秒：地址不通、网络异常要尽快暴露，不拖住请求线程。
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        // 读取超时 180 秒：大模型生成（尤其推理模型）可能耗时很长，读超时须远大于连接超时。
        requestFactory.setReadTimeout(Duration.ofSeconds(180));

        // OpenAI 兼容客户端：对话固定 /chat/completions、向量固定 /embeddings，百炼与 Ollama 共用这套路径；baseUrl / apiKey 全部取自运行时配置。
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(setting.getBaseUrl())
                .apiKey(setting.getApiKey())
                .completionsPath("/chat/completions")
                .embeddingsPath("/embeddings")
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        // 对话默认生成参数；配置缺省时兜底温度 0.3、最大 Token 1500，实际调用可由 chatWith 单次覆盖。
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(setting.getChatModel())
                .temperature(setting.getTemperature() == null ? 0.3 : setting.getTemperature())
                .maxTokens(setting.getMaxTokens() == null ? 1500 : setting.getMaxTokens())
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(chatOptions)
                .retryTemplate(RetryUtils.SHORT_RETRY_TEMPLATE)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();

        // 向量选项：encodingFormat 用 float 返回浮点向量，便于余弦相似度计算。
        var embeddingOptionsBuilder = OpenAiEmbeddingOptions.builder()
                .model(setting.getEmbeddingModel())
                .encodingFormat("float");
        // 判定是否本机 Ollama：其 OpenAI 兼容端点不支持 dimensions 参数，传入会直接报错。
        boolean ollama = "OLLAMA".equalsIgnoreCase(setting.getProvider());
        // Ollama 维度分支：仅非 Ollama 且配置维度大于 0 时才显式传 dimensions；Ollama 交由模型自身决定维度，避免请求被拒。
        if (!ollama && setting.getEmbeddingDim() != null && setting.getEmbeddingDim() > 0) {
            embeddingOptionsBuilder.dimensions(setting.getEmbeddingDim());
        }
        // 复用同一份 api 装配向量模型，MetadataMode.NONE 不拉取额外元数据，减少响应体积。
        OpenAiEmbeddingModel embeddingModel = new OpenAiEmbeddingModel(api, MetadataMode.NONE,
                embeddingOptionsBuilder.build(), RetryUtils.SHORT_RETRY_TEMPLATE, ObservationRegistry.NOOP);
        return new ModelBundle(setting, chatModel, embeddingModel);
    }

    /**
     * 由 8 个配置字段拼出缓存 key：提供方、地址、密钥、对话模型、向量模型、维度、温度、最大 Token。
     * <p>把 apiKey 也纳入 key，是为了让密钥轮换后立即失效旧连接；任一字段变化都生成新 key，
     * 使旧 Bundle 自然不再命中。用 String.valueOf 统一处理 null，避免拼串时空指针。
     * key 仅存在于内存，不落库、不打日志，避免密钥外泄。
     *
     * @param s 运行时配置
     * @return 配置哈希 key
     */
    private String cacheKey(AiSetting s) {
        // 竖线分隔 8 个字段构成配置指纹；String.valueOf 兼容 null，任一字段变动即得到新 key。
        return String.join("|", String.valueOf(s.getProvider()), String.valueOf(s.getBaseUrl()),
                String.valueOf(s.getApiKey()), String.valueOf(s.getChatModel()),
                String.valueOf(s.getEmbeddingModel()), String.valueOf(s.getEmbeddingDim()),
                String.valueOf(s.getTemperature()), String.valueOf(s.getMaxTokens()));
    }

    /**
     * LlmGateway.chat 的实现：以当前单行配置补全上下文，委托给 chatWith。
     * <p>业务层不传配置，这里统一用 currentSetting，保证同一次请求内配置一致；
     * 温度与 maxTokens 由调用方按场景给定（RAG 问答固定 0.2）。
     *
     * @param turns       业务组装好的消息轮次
     * @param temperature 单次采样温度
     * @param maxTokens   单次最大生成 Token
     * @return 统一结果
     */
    @Override
    public LlmChatResult chat(List<ChatTurn> turns, double temperature, int maxTokens) {
        // 取当前生效配置后委托 chatWith，由后者完成消息映射、调用、Token 统计与异常转换。
        return chatWith(currentSetting(), turns, temperature, maxTokens);
    }

    /**
     * 对话调用核心：把 ChatTurn 列表映射为 Spring AI Message，发起调用并封装统一结果。
     * <p>关键规则：
     * <ul>
     *   <li>role 按 system / assistant / 其他 映射为 SystemMessage / AssistantMessage /
     *       UserMessage，未知角色降级为用户消息，保证不会因脏数据漏发；</li>
     *   <li>用入参 temperature 与 maxTokens 构造本次 ChatOptions，单次覆盖配置默认值，
     *       互不影响缓存里那份 Bundle 的默认参数；</li>
     *   <li>从响应 Usage 取 promptTokens / completionTokens，取不到兜底 0；正文判空兜底空串；</li>
     *   <li>latencyMs 为发起调用到拿到响应的端到端耗时；</li>
     *   <li>任何底层异常统一转为 502 BusinessException，日志与对外信息只带截断后的根因摘要。</li>
     * </ul>
     *
     * @param setting     本次调用使用的配置（连接测试可传未落库的临时配置）
     * @param turns       消息轮次列表
     * @param temperature 单次采样温度
     * @param maxTokens   单次最大生成 Token
     * @return 含正文、Token、耗时、模型与提供方的统一结果
     * @throws BusinessException 502，底层调用失败
     */
    public LlmChatResult chatWith(AiSetting setting, List<ChatTurn> turns, double temperature, int maxTokens) {
        // 复用该配置缓存的模型组，避免每次对话都重建客户端与连接。
        ModelBundle bundle = buildBundle(setting);
        List<Message> messages = new ArrayList<>();
        // 按业务给定顺序逐轮转换，顺序即模型看到的上下文顺序。
        for (ChatTurn turn : turns) {
            // 消息类型 switch：system 走系统消息、assistant 走历史回复，其余一律降级为用户消息，脏 role 也不会漏发。
            switch (turn.role()) {
                // System 规则优先级最高，通常只出现在首轮。
                case "system" -> messages.add(new SystemMessage(turn.content()));
                // 历史模型回复，让模型理解多轮上下文。
                case "assistant" -> messages.add(new AssistantMessage(turn.content()));
                // user 及任何未知角色统一按 UserMessage 处理，避免异常或消息丢失。
                default -> messages.add(new UserMessage(turn.content()));
            }
        }
        // 单次覆盖：用入参 temperature / maxTokens 构造本次选项（RAG 固定 0.2 低温），不影响缓存中 Bundle 的默认参数。
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(setting.getChatModel())
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
        // 计时起点：只统计真实模型调用耗时，不含消息映射与后续结果清洗。
        long start = System.currentTimeMillis();
        try {
            // 发起同步调用；超时、鉴权、限流、协议错误等底层异常由 catch 统一收口。
            ChatResponse response = bundle.chatModel().call(new Prompt(messages, options));
            long latency = System.currentTimeMillis() - start;
            String content = "";
            // 结果判空：响应链路逐层判空，正文为 null 兜底空串，绝不把 null 抛给业务层。
            if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                content = text == null ? "" : text;
            }
            int promptTokens = 0;
            int completionTokens = 0;
            // Usage 判空：并非所有兼容端点都返回 Token 统计，metadata / usage 缺失时保持 0。
            if (response != null && response.getMetadata() != null) {
                Usage usage = response.getMetadata().getUsage();
                if (usage != null) {
                    // Token 统计兜底：任一计数为 null 记 0，保证 cs_ai_run 与看板聚合不出现空值。
                    promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
                    completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
                }
            }
            // 统一封装结果，并冗余带回模型与提供方，供落库与前端展示。
            return new LlmChatResult(content, promptTokens, completionTokens, latency,
                    setting.getChatModel(), setting.getProvider());
        } catch (Exception e) {
            // 日志只记根因摘要，不打印完整堆栈与请求体，避免密钥或内部信息泄漏。
            log.warn("大模型调用失败 provider={} model={} msg={}", setting.getProvider(),
                    setting.getChatModel(), rootMessage(e));
            // 异常统一转 502 BusinessException，业务层据此降级为 AI_UNAVAILABLE 转人工；对外信息经 shorten 截断到 220 字符。
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "AI 模型调用失败: " + shorten(rootMessage(e)));
        }
    }

    /**
     * 单文本向量化：取当前配置的向量模型，把文本映射为浮点向量。
     * <p>边界：底层异常统一转 502；知识入库切片与检索问题都走此方法，保证写入与查询
     * 落在同一向量空间、同一维度。切换 Embedding 模型 / 维度后必须重建全部向量索引。
     *
     * @param text 待向量化文本
     * @return 与当前配置维度一致的向量
     * @throws BusinessException 502，向量化服务调用失败
     */
    @Override
    public float[] embed(String text) {
        // 复用当前配置缓存的向量模型，与知识入库 / 检索保持同一模型与维度。
        ModelBundle bundle = buildBundle(currentSetting());
        try {
            // 真正发起向量化；底层异常由 catch 统一转 502。
            return bundle.embeddingModel().embed(text);
        } catch (Exception e) {
            log.warn("Embedding 调用失败 msg={}", rootMessage(e));
            // 向量化失败同样转 502，并只回传截断后的根因摘要。
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "向量化服务调用失败: " + shorten(rootMessage(e)));
        }
    }

    /**
     * 批量向量化：一次请求处理多条文本，用于知识库批量导入以减少网络往返。
     * <p>边界：空列表直接返回空结果，不发起调用；返回结果数量必须与入参一致，
     * 不一致说明供应商漏返或错序，立即报错，防止向量与切片错位污染索引；异常统一转 502。
     *
     * @param texts 待向量化文本列表
     * @return 与 texts 等长且顺序对应的向量列表
     * @throws BusinessException 502，向量化服务调用失败
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        // 空列表短路：没有输入就不建模型、不发请求，直接返回不可变空列表。
        if (texts.isEmpty()) {
            return List.of();
        }
        ModelBundle bundle = buildBundle(currentSetting());
        try {
            List<float[]> vectors = bundle.embeddingModel().embed(texts);
            // 校验返回数量：批量结果必须逐条对应，数量不符立即失败，避免向量与知识切片错配污染索引。
            if (vectors.size() != texts.size()) {
                throw new IllegalStateException("向量数量与文本数量不一致");
            }
            return vectors;
        } catch (Exception e) {
            log.warn("批量 Embedding 调用失败 msg={}", rootMessage(e));
            // 批量向量化失败统一转 502，错误信息经 shorten 截断后回传。
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "向量化服务调用失败: " + shorten(rootMessage(e)));
        }
    }

    /**
     * 返回当前提供方标识（BAILIAN / OLLAMA），供 cs_ai_run 与前端展示。
     *
     * @return 当前提供方
     */
    @Override
    public String provider() {
        return currentSetting().getProvider();
    }

    /**
     * 返回当前对话模型名，供调用记录与前端展示。
     *
     * @return 当前对话模型名
     */
    @Override
    public String chatModelName() {
        return currentSetting().getChatModel();
    }

    /**
     * 返回当前 Embedding 模型名；检索时据此过滤，只复用同模型产出的向量。
     *
     * @return 当前 Embedding 模型名
     */
    @Override
    public String embeddingModelName() {
        return currentSetting().getEmbeddingModel();
    }

    /**
     * 返回当前向量维度；配置为 null 时按 1024 兜底，与百炼 text-embedding-v4 默认一致。
     * <p>该值决定向量索引列宽与余弦计算，切换模型导致维度变化时必须重建全部索引。
     *
     * @return 当前向量维度
     */
    @Override
    public int embeddingDim() {
        Integer dim = currentSetting().getEmbeddingDim();
        // 维度缺省兜底 1024（与百炼 text-embedding-v4 一致），避免配置为 null 时建索引拿不到维度。
        return dim == null ? 1024 : dim;
    }

    /**
     * 系统设置页的对话连接测试：用传入配置发一条极简提示词，返回耗时、模型与样例输出。
     * <p>设计意图：测试面向管理员页面，失败必须返回结果对象（ok=false + 截断错误），
     * 而不是抛异常打断整个设置页；因此内部捕获所有异常并转成结构化 Map。
     * 采用 0.1 低温与 512 Token 上限，尽量快速、确定、少耗 Token。
     *
     * @param setting 待验证的配置，通常是页面上尚未保存的临时值
     * @return 含 ok、latencyMs、model、sample 或 error 的结果 Map，永不抛异常
     */
    public Map<String, Object> testChat(AiSetting setting) {
        long start = System.currentTimeMillis();
        try {
            // 固定提示词 + 0.1 低温 + 512 Token，用最小代价验证链路是否连通。
            LlmChatResult result = chatWith(setting, List.of(ChatTurn.user("请只回复四个字：连接成功")), 0.1, 512);
            String sample = shorten(result.content());
            // 推理模型可能只输出思考过程、正文为空，给管理员一句可读兜底而非误判失败。
            if (sample.isBlank()) {
                sample = "模型已响应（推理模型可能只返回思考过程）";
            }
            return Map.of("ok", true, "latencyMs", System.currentTimeMillis() - start,
                    "model", Objects.toString(setting.getChatModel(), ""),
                    "sample", sample);
        } catch (Exception e) {
            // 连接测试失败不抛异常：转成 ok=false 结构化结果返回，保证设置页可用；错误信息经 shorten 截断。
            return Map.of("ok", false, "latencyMs", System.currentTimeMillis() - start,
                    "error", shorten(rootMessage(e)));
        }
    }

    /**
     * 系统设置页的向量连接测试：用传入配置对固定文本做一次向量化，返回实际维度。
     * <p>返回 dimension 供管理员核对与配置里 embeddingDim 是否一致；失败同样返回结构化结果，
     * 不抛异常。
     *
     * @param setting 待验证的配置
     * @return 含 ok、latencyMs、model、dimension 或 error 的结果 Map，永不抛异常
     */
    public Map<String, Object> testEmbedding(AiSetting setting) {
        long start = System.currentTimeMillis();
        try {
            // 刻意用 createBundle 而非 buildBundle：按临时配置新建且不读写缓存，避免测试配置污染线上模型缓存。
            ModelBundle bundle = createBundle(setting);
            float[] vector = bundle.embeddingModel().embed("企业智能客服连接测试");
            return Map.of("ok", true, "latencyMs", System.currentTimeMillis() - start,
                    "model", Objects.toString(setting.getEmbeddingModel(), ""),
                    "dimension", vector.length);
        } catch (Exception e) {
            // 与 testChat 一致，失败返回结果而非抛异常，错误信息截断后回传。
            return Map.of("ok", false, "latencyMs", System.currentTimeMillis() - start,
                    "error", shorten(rootMessage(e)));
        }
    }

    /**
     * 沿异常 cause 链下钻到最根本的原因，取其对用户最有信息量的 message。
     * <p>设计意图：Spring AI / RestClient 常把真实错误包在多层异常里，顶层 message 往往只是
     * 泛泛的调用失败；取根因能让日志与 502 提示更有价值。
     * <p>边界：while 条件同时排除 cause 指向自身的情况，防止异常链成环导致死循环；
     * 根因 message 为 null 时退化为异常类简名，保证返回值非 null。
     *
     * @param throwable 捕获到的任意异常
     * @return 根因描述，非 null
     */
    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        // 逐层剥离包装异常取根因；并用 getCause() != current 防自引用成环，避免死循环。
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        // 根因没有 message 时退化为异常类简名，保证日志与对外提示始终有可读内容。
        return message == null ? current.getClass().getSimpleName() : message;
    }

    /**
     * 把错误信息截断到 220 字符，超出部分以省略号结尾。
     * <p>设计意图：供应商错误响应可能包含大段 HTML、请求回显甚至敏感内容，直接写日志或
     * 返回前端既难读又有泄漏风险；统一截断可控制体积、保护信息。
     * <p>边界：入参为 null 返回空串，不抛空指针。
     *
     * @param text 原始错误文本，可为 null
     * @return 截断后的文本，非 null
     */
    private String shorten(String text) {
        // null 兜底为空串，避免上游判空遗漏导致空指针。
        if (text == null) {
            return "";
        }
        // 超过 220 字符只保留前段并加省略号，控制日志与 API 响应体积、避免泄漏大段内容。
        return text.length() > 220 ? text.substring(0, 220) + "..." : text;
    }
}
