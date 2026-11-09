package com.example.copilot.ai.service;

import com.example.copilot.ai.dto.AiSettingVO;
import com.example.copilot.ai.dto.UpdateAiSettingRequest;
import com.example.copilot.ai.entity.AiProviderKey;
import com.example.copilot.ai.entity.AiSetting;
import com.example.copilot.ai.gateway.SpringAiLlmGateway;
import com.example.copilot.ai.mapper.AiProviderKeyMapper;
import com.example.copilot.ai.mapper.AiSettingMapper;
import com.example.copilot.common.config.AppProperties;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 运行时配置服务：维护 ai_setting 单行配置，并管理各提供方 API Key 的保存与复用。
 *
 * <p>在 RAG 链路中的位置：AiChatService 每次问答通过 currentSetting 读取 provider、
 * chatModel、maxTokens；SpringAiLlmGateway 依据配置构建 ChatClient 与 EmbeddingModel。
 *
 * <p>关键设计与约束：
 * <ul>
 * <li>单行配置：以 SINGLETON_ID 为唯一主键，首次访问用配置文件百炼参数初始化；</li>
 * <li>按提供方保存 Key：ai_provider_key 以 provider 为主键，切换 BAILIAN / OLLAMA 时
 * 各自复用已存 Key，脱敏回显值（含 ****）不会被当作新 Key 覆盖；</li>
 * <li>出参脱敏：view / runtime 均不返回明文 Key，view 只给 apiKeyMasked 与预置参数；</li>
 * <li>切换后清缓存：update 成功必须调用 gateway.clearCache，
 * 否则网关仍复用按旧配置哈希缓存的客户端；</li>
 * <li>Embedding 变更：切换 embeddingModel / embeddingDim 后既有向量与新模型
 * 语义空间或维度不一致，需另行重建向量索引，本服务只清运行时缓存不重建索引；</li>
 * <li>权限：配置读写仅 ADMIN，由 AdminAiController 类级注解统一拦截，
 * Service 层不重复鉴权；test 仅探测连通性、不落库。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigService {

    // 依赖：settingMapper 读写单行配置，providerKeyMapper 读写按提供方保存的 Key，
    // properties 提供百炼 / Ollama 预置参数，gateway 在配置变更后清理缓存并用于连接测试。
    private final AiSettingMapper settingMapper;
    private final AiProviderKeyMapper providerKeyMapper;
    private final AppProperties properties;
    private final SpringAiLlmGateway gateway;

    /**
     * 读取当前生效配置，委托 ensureDefault 保证单行记录一定存在。
     * 问答主链路、审计记录与 /runtime 出参均以此为准。
     */
    public AiSetting currentSetting() {
        return ensureDefault();
    }

    /**
     * 保证单行配置存在：按 SINGLETON_ID 查询，命中直接返回；
     * 未命中则用配置文件中百炼的 baseUrl / apiKey / chatModel / embeddingModel / embeddingDim
     * 初始化一行，provider 固定 BAILIAN、temperature 固定 0.3、maxTokens 取百炼配置值。
     *
     * <p>加事务：初始化插入与后续读取在同一语义下完成，避免并发首次访问产生多行。
     * 边界：仅在表为空时插入，已有配置（含管理员改过的 Key）不会被覆盖。
     */
    @Transactional
    public AiSetting ensureDefault() {
        // 单行表以固定 SINGLETON_ID 为主键，不做列表查询。
        AiSetting setting = settingMapper.selectById(AiSetting.SINGLETON_ID);
        if (setting != null) {
            return setting;
        }
        AppProperties.Provider bailian = properties.getAi().getBailian();
        // 首次初始化：以配置文件百炼参数为缺省值，temperature 兜底 0.3。
        setting = new AiSetting();
        setting.setId(AiSetting.SINGLETON_ID);
        setting.setProvider("BAILIAN");
        setting.setBaseUrl(bailian.getBaseUrl());
        setting.setApiKey(bailian.getApiKey());
        setting.setChatModel(bailian.getChatModel());
        setting.setEmbeddingModel(bailian.getEmbeddingModel());
        setting.setEmbeddingDim(bailian.getEmbeddingDim());
        setting.setTemperature(0.3);
        setting.setMaxTokens(bailian.getMaxTokens());
        settingMapper.insert(setting);
        return setting;
    }

    /**
     * 预置各提供方 Key：把配置文件里百炼与 Ollama 的 apiKey 分别写入 ai_provider_key
     * （仅当该 provider 尚无记录），并把当前生效配置里的 Key 按其 provider 也保存一份。
     *
     * <p>目的：管理员在页面切换提供方时，即便不重新填 Key 也能复用已保存值；
     * 已有记录一律不覆盖，避免用配置文件旧值冲掉页面新填的 Key。加事务保证批量写入一致。
     */
    @Transactional
    public void ensureProviderKeys() {
        ensureProviderKey("BAILIAN", properties.getAi().getBailian().getApiKey());
        ensureProviderKey("OLLAMA", properties.getAi().getOllama().getApiKey());
        AiSetting setting = currentSetting();
        if (StringUtils.hasText(setting.getApiKey())) {
            AiProviderKey key = providerKeyMapper.selectById(setting.getProvider());
            if (key == null) {
                upsertProviderKey(setting.getProvider(), setting.getApiKey());
            }
        }
    }

    /**
     * 单个提供方 Key 预置：apiKey 为空或该 provider 已有记录时什么也不做，否则写入。
     * 边界：只补缺、不覆盖，保护管理员后续修改。
     */
    private void ensureProviderKey(String provider, String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return;
        }
        if (providerKeyMapper.selectById(provider) == null) {
            upsertProviderKey(provider, apiKey);
        }
    }

    /**
     * 按 provider 主键 upsert：存在则 update，不存在则 insert。
     * 这是按提供方保存与复用 API Key 的落地点，切换 BAILIAN / OLLAMA 后仍能取回各自 Key。
     */
    private void upsertProviderKey(String provider, String apiKey) {
        AiProviderKey key = new AiProviderKey();
        key.setProvider(provider);
        key.setApiKey(apiKey);
        if (providerKeyMapper.selectById(provider) == null) {
            providerKeyMapper.insert(key);
        } else {
            providerKeyMapper.updateById(key);
        }
    }

    /**
     * 解析本次应使用的明文 Key，是脱敏出参不被回写覆盖的关键。
     *
     * <p>优先级：
     * <ul>
     * <li>请求携带了非空且不含脱敏占位 **** 的 Key，视为用户新输入，trim 后返回；</li>
     * <li>否则查 ai_provider_key 中该 provider 已保存的 Key；</li>
     * <li>否则若目标 provider 与当前配置相同且当前 Key 非空，复用当前 Key；</li>
     * <li>都没有返回 null，由调用方决定是否报错。</li>
     * </ul>
     *
     * <p>设计意图：前端把 apiKeyMasked 原样回传时含 ****，若直接入库会覆盖真实 Key，
     * 因此先识别脱敏占位并回退到已存值，保证看得到但改不坏。
     */
    private String resolveApiKey(String provider, String requested, AiSetting current) {
        // 含 **** 说明是脱敏回显值，不能当作新 Key；只有真实新输入才采用。
        if (StringUtils.hasText(requested) && !requested.contains("****")) {
            return requested.trim();
        }
        AiProviderKey saved = providerKeyMapper.selectById(provider);
        if (saved != null && StringUtils.hasText(saved.getApiKey())) {
            return saved.getApiKey();
        }
        if (provider.equals(current.getProvider()) && StringUtils.hasText(current.getApiKey())) {
            return current.getApiKey();
        }
        return null;
    }

    /**
     * 管理端查看配置：返回当前配置 VO 与百炼 / Ollama 预置参数。
     *
     * <p>脱敏：apiKey 经 mask 后以 apiKeyMasked 出参，presets 只含模型与地址等参数、
     * 不含任何 Key；出参永不暴露明文凭据。
     */
    public AiSettingVO view() {
        AiSetting setting = currentSetting();
        Map<String, Map<String, Object>> presets = new LinkedHashMap<>();
        presets.put("BAILIAN", preset("阿里云百炼（DashScope）", properties.getAi().getBailian()));
        presets.put("OLLAMA", preset("本地 Ollama", properties.getAi().getOllama()));
        return AiSettingVO.builder()
                .provider(setting.getProvider())
                .baseUrl(setting.getBaseUrl())
                .apiKeyMasked(mask(setting.getApiKey()))
                .chatModel(setting.getChatModel())
                .embeddingModel(setting.getEmbeddingModel())
                .embeddingDim(setting.getEmbeddingDim())
                .temperature(setting.getTemperature())
                .maxTokens(setting.getMaxTokens())
                .updateTime(setting.getUpdateTime())
                .presets(presets)
                .build();
    }

    /**
     * 构造单个提供方的预置参数（label / baseUrl / chatModel / embeddingModel /
     * embeddingDim / maxTokens），供前端切换时回填表单；刻意不包含 apiKey。
     */
    private Map<String, Object> preset(String label, AppProperties.Provider provider) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("baseUrl", provider.getBaseUrl());
        map.put("chatModel", provider.getChatModel());
        map.put("embeddingModel", provider.getEmbeddingModel());
        map.put("embeddingDim", provider.getEmbeddingDim());
        map.put("maxTokens", provider.getMaxTokens());
        return map;
    }

    /**
     * 更新运行时配置（管理端 PUT），是本服务最核心的写路径。
     *
     * <p>流程：
     * <ul>
     * <li>provider 归一化后只接受 BAILIAN / OLLAMA，否则抛业务异常；</li>
     * <li>baseUrl 去尾部斜杠；经 resolveApiKey 解析 Key，无有效 Key 直接报错
     * （Ollama 可填任意占位值）；</li>
     * <li>解析出的 Key 写入当前配置并 upsertProviderKey 按提供方持久化，供后续切换复用；</li>
     * <li>chatModel / embeddingModel / embeddingDim 落库，temperature 缺省 0.3、
     * maxTokens 缺省 1500；</li>
     * <li>切回百炼时若保留 Key 不是 sk- 格式，回退配置文件百炼 Key，
     * 避免切到 Ollama 再切回被占位值覆盖导致调用失败；Ollama 无 Key 时用配置占位；</li>
     * <li>记录 updatedBy，写库后 gateway.clearCache 清理网关缓存，返回脱敏后的 view。</li>
     * </ul>
     *
     * <p>约束：加事务；出参为脱敏 VO；Embedding 模型或维度变化后需另行重建向量索引，
     * 本方法只清运行时缓存，不重建索引。异常均以 BusinessException 返回，不吞错。
     */
    @Transactional
    public AiSettingVO update(UpdateAiSettingRequest request) {
        AiSetting setting = currentSetting();
        String provider = request.getProvider().trim().toUpperCase();
        // 提供方白名单：当前网关只实现百炼与本地 Ollama，其它值直接拒绝。
        if (!"BAILIAN".equals(provider) && !"OLLAMA".equals(provider)) {
            throw BusinessException.badRequest("暂仅支持 BAILIAN 或 OLLAMA");
        }
        setting.setProvider(provider);
        setting.setBaseUrl(trimSlash(request.getBaseUrl()));
        String resolvedKey = resolveApiKey(provider, request.getApiKey(), setting);
        if (!StringUtils.hasText(resolvedKey)) {
            throw BusinessException.badRequest("该提供方还没有保存 API Key，请填写后再保存（Ollama 可填写任意占位值）");
        }
        setting.setApiKey(resolvedKey);
        // 按提供方持久化 Key：下次切回该 provider 时 resolveApiKey 能直接复用，无需重填。
        upsertProviderKey(provider, resolvedKey);
        setting.setChatModel(request.getChatModel().trim());
        setting.setEmbeddingModel(request.getEmbeddingModel().trim());
        setting.setEmbeddingDim(request.getEmbeddingDim());
        setting.setTemperature(request.getTemperature() == null ? 0.3 : request.getTemperature());
        setting.setMaxTokens(request.getMaxTokens() == null ? 1500 : request.getMaxTokens());
        // 切换回百炼时兜底：如果保留的 Key 不是百炼格式，使用配置文件中的默认 Key，
        // 避免切换到 Ollama 再切回时因 Key 被占位值覆盖导致调用失败。
        // 增强说明：切回百炼时，若从 ai_provider_key 复用的 Key 是当初 Ollama 的占位值，
        // 会让百炼鉴权失败；此处校验 sk- 前缀并回退配置文件默认 Key 兜底，
        // 正是为了覆盖百炼到 Ollama 再回到百炼的切换路径。
        if ("BAILIAN".equals(provider)
                && (setting.getApiKey() == null || !setting.getApiKey().startsWith("sk-"))) {
            setting.setApiKey(properties.getAi().getBailian().getApiKey());
        }
        if ("OLLAMA".equals(provider) && !StringUtils.hasText(setting.getApiKey())) {
            setting.setApiKey(properties.getAi().getOllama().getApiKey());
        }
        // 最终兜底：任何分支结束后 Key 仍为空都报错，避免带着空 Key 清缓存、污染运行时。
        if (!StringUtils.hasText(setting.getApiKey())) {
            throw BusinessException.badRequest("API Key 不能为空（Ollama 可填写任意占位值）");
        }
        // 审计字段：记录本次修改人，便于追溯配置变更。
        setting.setUpdatedBy(CurrentUser.id());
        settingMapper.updateById(setting);
        // 配置哈希缓存清理：SpringAiLlmGateway 按配置缓存 ChatClient / EmbeddingModel，
        // 不清理会继续用旧 provider、旧模型或旧 Key 调用，故每次更新后必须失效全量缓存。
        // 注意：embeddingModel / embeddingDim 变化还会使既有向量与新模型语义空间或维度不一致，
        // 清缓存之外还需重建向量索引，否则检索结果会失准甚至不可用。
        gateway.clearCache();
        log.info("AI 设置已更新 provider={} chatModel={} embeddingModel={}", setting.getProvider(),
                setting.getChatModel(), setting.getEmbeddingModel());
        return view();
    }

    /**
     * 连接测试：用请求参数（缺省回退当前配置）构造一个临时 probe，
     * 分别调用网关的 testChat 与 testEmbedding，返回 provider / chat / embedding 三项结果。
     *
     * <p>边界：不落库、不清缓存、不改动当前配置；温度固定 0.1、maxTokens 固定 512，
     * 用最小代价探测地址、Key 与模型是否可用，供管理员保存前验证。
     */
    public Map<String, Object> test(UpdateAiSettingRequest request) {
        AiSetting current = currentSetting();
        AiSetting probe = new AiSetting();
        probe.setProvider(StringUtils.hasText(request.getProvider())
                ? request.getProvider().trim().toUpperCase() : current.getProvider());
        probe.setBaseUrl(StringUtils.hasText(request.getBaseUrl())
                ? trimSlash(request.getBaseUrl()) : current.getBaseUrl());
        String probeKey = resolveApiKey(probe.getProvider(), request.getApiKey(), current);
        probe.setApiKey(probeKey == null ? "" : probeKey);
        probe.setChatModel(StringUtils.hasText(request.getChatModel())
                ? request.getChatModel().trim() : current.getChatModel());
        probe.setEmbeddingModel(StringUtils.hasText(request.getEmbeddingModel())
                ? request.getEmbeddingModel().trim() : current.getEmbeddingModel());
        probe.setEmbeddingDim(request.getEmbeddingDim() == null
                ? current.getEmbeddingDim() : request.getEmbeddingDim());
        // 探测用固定小参数：温度 0.1 更稳定，maxTokens 512 控制测试成本。
        probe.setTemperature(0.1);
        probe.setMaxTokens(512);

        // 返回结构固定为 provider / chat / embedding，前端据此分别提示对话与向量能力是否连通。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", probe.getProvider());
        result.put("chat", gateway.testChat(probe));
        result.put("embedding", gateway.testEmbedding(probe));
        return result;
    }

    /**
     * 运行时摘要（供普通用户的 /api/ai/runtime）：
     * 仅返回 provider / chatModel / embeddingModel / embeddingDim 四项。
     *
     * <p>关键：绝不返回 API Key，也不需要 ADMIN 权限；
     * 与 view 的区别是 view 给管理端脱敏详情与预置参数，runtime 只暴露非敏感的模型信息。
     */
    public Map<String, Object> runtime() {
        AiSetting setting = currentSetting();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("provider", setting.getProvider());
        map.put("chatModel", setting.getChatModel());
        map.put("embeddingModel", setting.getEmbeddingModel());
        map.put("embeddingDim", setting.getEmbeddingDim());
        return map;
    }

    /**
     * 去掉 baseUrl 尾部斜杠，避免与网关拼接路径时出现双斜杠导致 404。
     * 边界：null 原样返回，非斜杠结尾不做处理。
     */
    private String trimSlash(String url) {
        if (url == null) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Key 脱敏：空值显示未配置；长度不超过 10 只返回 ****；
     * 否则保留前 6 位与后 4 位、中间用 **** 遮蔽，兼顾可辨识与不泄露完整凭据。
     * 该结果仅供展示，回传时由 resolveApiKey 识别 **** 并拒绝覆盖。
     */
    private String mask(String key) {
        if (!StringUtils.hasText(key)) {
            return "未配置";
        }
        if (key.length() <= 10) {
            return "****";
        }
        return key.substring(0, Math.min(6, key.length())) + "****" + key.substring(key.length() - 4);
    }
}
