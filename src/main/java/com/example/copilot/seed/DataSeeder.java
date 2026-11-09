package com.example.copilot.seed;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.copilot.ai.service.AiConfigService;
import com.example.copilot.common.config.AppProperties;
import com.example.copilot.knowledge.entity.KnowledgeBase;
import com.example.copilot.knowledge.mapper.KbDocumentMapper;
import com.example.copilot.knowledge.mapper.KnowledgeBaseMapper;
import com.example.copilot.knowledge.service.DocumentService;
import com.example.copilot.system.entity.User;
import com.example.copilot.system.mapper.UserMapper;
import com.example.copilot.ticket.entity.Ticket;
import com.example.copilot.ticket.entity.TicketEvent;
import com.example.copilot.ticket.mapper.TicketEventMapper;
import com.example.copilot.ticket.mapper.TicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示数据初始化器：实现 ApplicationRunner，在 Spring Boot 容器就绪后执行一次，
 * 幂等地灌入种子账号、AI 默认配置、示例知识库与示例工单，并提交 6 篇内置 Markdown 文档做向量化。
 * <p>
 * 在业务链路中属于「启动引导」层，仅供本地演示与联调：让平台一启动就有可登录的三级角色账号、
 * 可检索的知识库以及覆盖典型状态的工单，便于直接体验 Copilot 与运营看板。
 * <ul>
 *   <li>总开关：受 properties.seed.enabled 控制，关闭时 run 直接返回，不写任何数据。</li>
 *   <li>幂等：所有插入都以「数据为空 / 记录不存在」为前提——账号按 username 查重、知识库按 name 查重、
 *       知识文档以 hasAnyDocument 判断、工单以 selectCount 判断、AI 配置由 ensureDefault 保证；
 *       重复启动不会重复插入或重复向量化。</li>
 *   <li>演示用途：种子账号使用固定明文口令（admin/agent/agent2/user/user2），仅用于本地演示，
 *       生产必须关闭 seed 开关；口令落库前一律经 PasswordEncoder 加密，不存明文。</li>
 *   <li>6 篇文档通过 DocumentService.uploadSeedDocument 提交后由服务异步解析、切片、Embedding 并落库，
 *       本类只负责「提交」而不阻塞等待向量化完成。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    /** 应用配置：读取 seed 开关（enabled / knowledge）与相关演示参数。 */
    private final AppProperties properties;
    /** 用户 Mapper：按 username 查重并插入种子账号。 */
    private final UserMapper userMapper;
    /** 口令编码器：把演示明文口令加密后再落库。 */
    private final PasswordEncoder passwordEncoder;
    /** AI 配置服务：幂等初始化默认 AiSetting 与各供应商 Key。 */
    private final AiConfigService aiConfigService;
    /** 知识库 Mapper：按名称查重并创建示例知识库。 */
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    /** 知识文档 Mapper：文档表的数据访问入口。 */
    private final KbDocumentMapper documentMapper;
    /** 文档服务：判断是否已有文档、并提交示例文档进入异步解析与向量化链路。 */
    private final DocumentService documentService;
    /** 工单 Mapper：查重并插入示例工单。 */
    private final TicketMapper ticketMapper;
    /** 工单事件 Mapper：写入示例工单的流转事件时间线。 */
    private final TicketEventMapper ticketEventMapper;

    /**
     * ApplicationRunner 入口：容器就绪后执行一次，按开关与幂等策略初始化全套演示数据。
     * <p>
     * 顺序：先建三级角色账号（ADMIN / AGENT / USER，对应全局 JWT 三级角色），再幂等初始化
     * AI 默认配置与供应商 Key，随后按 knowledge 开关灌示例知识库，最后灌示例工单；
     * 结尾打印演示账号提示，便于开箱即用。
     * <p>
     * 边界：seed.enabled 为 false 时直接 return，不写任何数据；各 ensureXxx / seedXxx
     * 内部都已做「数据为空才插入」的幂等判断，重复启动不会产生重复数据；
     * 账号与明文口令仅用于演示，生产必须关闭该开关。
     * @param args 应用启动参数，本实现不依赖
     */
    @Override
    public void run(ApplicationArguments args) {
        // 总开关关闭时直接返回，不触碰任何业务表
        if (!properties.getSeed().isEnabled()) {
            return;
        }
        // 种子账号覆盖 ADMIN / AGENT / USER 三级角色；AGENT 两名、USER 两名用于演示工单协作与数据可见性
        User admin = ensureUser("admin", "admin123", "系统管理员", "ADMIN");
        User agent = ensureUser("agent", "agent123", "客服小林", "AGENT");
        ensureUser("agent2", "agent123", "技术支持阿杰", "AGENT");
        User user = ensureUser("user", "user123", "员工小王", "USER");
        User user2 = ensureUser("user2", "user123", "客户李女士", "USER");

        // AI 配置幂等初始化：默认 AiSetting 与各供应商 Key 已存在则跳过
        aiConfigService.ensureDefault();
        aiConfigService.ensureProviderKeys();

        // 知识库灌入由独立开关控制，关闭时跳过示例文档与向量化
        if (properties.getSeed().isKnowledge()) {
            seedKnowledge(admin);
        }
        // 示例工单用已建账号建立提交人 / 处理人关联；内部按 selectCount 幂等
        seedTickets(agent, user, user2);
        log.info("种子数据初始化完成（账号：admin/admin123、agent/agent123、user/user123）");
    }

    /**
     * 按 username 幂等创建种子账号：已存在则直接返回，不存在才插入，保证重复启动不产生重复账号。
     * <p>
     * 关键规则：口令经 PasswordEncoder 加密后落库，绝不存明文；status=1 表示启用；
     * role 取 ADMIN / AGENT / USER 之一，与全局三级角色体系一致。
     * 返回的 User（含自增主键）供后续建立工单提交人、处理人及知识库 owner 等关联。
     * @param username 登录名，查重唯一键
     * @param password 演示用明文口令，入库前会被加密
     * @param nickname 展示名
     * @param role 角色：ADMIN / AGENT / USER
     * @return 已存在或新建的账号实体
     */
    private User ensureUser(String username, String password, String nickname, String role) {
        User existing = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        // 幂等：同名账号已存在直接复用，避免重复插入
        if (existing != null) {
            return existing;
        }
        User user = new User();
        user.setUsername(username);
        // 明文口令只在内存出现一次，落库的是加密串
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setRole(role);
        user.setStatus(1);
        userMapper.insert(user);
        return user;
    }

    /**
     * 初始化 3 个示例知识库并提交 6 篇内置 Markdown 文档进入向量化链路。
     * <p>
     * 幂等：只要库中已存在任一文档（hasAnyDocument）就直接返回，避免重复建库、重复灌文档与重复向量化。
     * 3 个知识库分别覆盖 IT 故障与账号支持、人力与行政制度、产品使用手册，owner 为 admin，
     * 可见性 PUBLIC、状态 ACTIVE（对应 RAG 检索按「可见知识库 / 有效状态」过滤的口径）。
     * <p>
     * 6 篇文档从 classpath 的 seed 目录读取为字节，交给 DocumentService.uploadSeedDocument
     * 以 md 类型提交；真正的解析、页码、切片、Embedding 由服务异步执行，本方法只负责提交。
     * 边界：单篇文档读取或提交失败仅记 warn 并继续下一篇，不中断整体初始化。
     * @param admin 知识库归属者（系统管理员账号）
     */
    private void seedKnowledge(User admin) {
        // 幂等总闸：已有任何文档即认为知识库已初始化，直接跳过
        if (documentService.hasAnyDocument()) {
            return;
        }
        // 3 个示例库按业务域划分；createKb 按名称查重，重复启动不会新建
        KnowledgeBase itKb = createKb(admin.getId(), "IT 故障与账号支持",
                "邮箱、VPN、电脑、打印机、账号权限等 IT 服务资料");
        KnowledgeBase hrKb = createKb(admin.getId(), "人力与行政制度",
                "请假考勤、费用报销、差旅等内部制度");
        KnowledgeBase productKb = createKb(admin.getId(), "产品使用手册",
                "智能终端产品的激活、安装、升级与售后资料");

        // 6 篇内置 Markdown 与所属知识库的映射（classpath 资源路径 → 展示名）
        List<SeedDoc> docs = new ArrayList<>();
        docs.add(new SeedDoc(itKb, "seed/email-guide.md", "企业邮箱使用与故障处理手册.md"));
        docs.add(new SeedDoc(itKb, "seed/network-vpn-guide.md", "办公网络与 VPN 使用指南.md"));
        docs.add(new SeedDoc(itKb, "seed/it-faq.md", "IT 常见故障处理手册.md"));
        docs.add(new SeedDoc(hrKb, "seed/leave-policy.md", "员工请假与考勤管理制度.md"));
        docs.add(new SeedDoc(hrKb, "seed/expense-policy.md", "费用报销与差旅管理制度.md"));
        docs.add(new SeedDoc(productKb, "seed/product-manual.md", "智能终端产品使用手册.md"));

        for (SeedDoc doc : docs) {
            try (InputStream in = new ClassPathResource(doc.resourcePath()).getInputStream()) {
                byte[] bytes = in.readAllBytes();
                // 逐篇读取为字节并提交异步向量化（解析 → 切片 → Embedding → 落库），此处不等待完成
                documentService.uploadSeedDocument(doc.knowledgeBase(), doc.displayName(), bytes, "md");
            } catch (Exception e) {
                // 失败降级：单篇读取/提交失败只告警，不影响其余文档与后续流程
                log.warn("加载示例文档失败 {}: {}", doc.resourcePath(), e.getMessage());
            }
        }
        // 日志区分「知识库数」与「提交文档数」，便于确认向量化任务规模
        log.info("已创建 {} 个知识库并提交 {} 篇示例文档进行向量化", 3, docs.size());
    }

    /**
     * 按名称幂等创建知识库：同名已存在则直接返回，不存在才插入。
     * <p>
     * 关键规则：固定设为 PUBLIC 可见、ACTIVE 状态，owner 指向传入账号；
     * 这两个字段决定该库能否被 RAG 检索命中（可见知识库 / 有效状态过滤）。
     * @param ownerId 归属者账号主键
     * @param name 知识库名称，查重唯一键
     * @param description 知识库描述
     * @return 已存在或新建的知识库实体（含主键，供文档归属）
     */
    private KnowledgeBase createKb(Long ownerId, String name, String description) {
        KnowledgeBase existing = knowledgeBaseMapper.selectOne(
                Wrappers.<KnowledgeBase>lambdaQuery().eq(KnowledgeBase::getName, name));
        // 幂等：同名知识库已存在直接复用
        if (existing != null) {
            return existing;
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setOwnerId(ownerId);
        // PUBLIC + ACTIVE：保证示例库对检索链路可见且处于有效状态
        kb.setVisibility("PUBLIC");
        kb.setStatus("ACTIVE");
        knowledgeBaseMapper.insert(kb);
        return kb;
    }

    /**
     * 初始化 4 张覆盖典型状态与分类的演示工单，并逐张写入完整流转事件时间线。
     * <p>
     * 幂等：工单表已有任何记录（selectCount > 0）就直接返回，重复启动不会追加。
     * 状态机体现：t1 ASSIGNED（已分配待处理）、t2 IN_PROGRESS（处理中）、t3 RESOLVED（已解决，并回填
     * resolvedTime 供看板统计「今日解决」）、t4 NEW 且未分配，覆盖六态状态机的典型在途与终结状态。
     * <p>
     * 每张工单的事件按 CREATED → ASSIGNED → STARTED → RESOLVED 依次记录 oldStatus → newStatus，
     * 保证工单详情时间线可回放、看板趋势有数据。
     * @param agent 客服账号，作为已分配工单的处理人
     * @param user 员工账号，作为提交人
     * @param user2 客户账号，作为提交人
     */
    private void seedTickets(User agent, User user, User user2) {
        // 幂等：工单表非空即认为已初始化，直接跳过
        if (ticketMapper.selectCount(null) > 0) {
            return;
        }
        // t1：账号类高优先级工单，停在 ASSIGNED（已分配待处理）
        Ticket t1 = demoTicket("TDEMO0001", user.getId(), "公司邮箱登录失败，提示账号认证异常",
                "今天早上登录公司邮箱时，输入正确密码后提示「账号认证异常」，已经尝试重启电脑和更换浏览器，仍然无法登录。手机端也提示密码错误。",
                "ACCOUNT", "HIGH", "ASSIGNED", agent.getId());
        addEvent(t1.getId(), user.getId(), "CREATED", null, "NEW", "提交工单");
        addEvent(t1.getId(), agent.getId(), "ASSIGNED", "NEW", "ASSIGNED", "分配处理人：客服小林");

        // t2：技术类中优先级工单，推进到 IN_PROGRESS，含 STARTED 事件
        Ticket t2 = demoTicket("TDEMO0002", user.getId(), "VPN 连接频繁断开，影响远程办公",
                "在家办公连接公司 VPN，大约每 10 分钟就会自动断开，重新连接后又能使用。已经重启过路由器，网络其他网站访问正常。",
                "TECHNICAL", "MEDIUM", "IN_PROGRESS", agent.getId());
        addEvent(t2.getId(), user.getId(), "CREATED", null, "NEW", "提交工单");
        addEvent(t2.getId(), agent.getId(), "ASSIGNED", "NEW", "ASSIGNED", "分配处理人：客服小林");
        addEvent(t2.getId(), agent.getId(), "STARTED", "ASSIGNED", "IN_PROGRESS", "开始排查网络与 VPN 日志");

        // t3：售后类工单，停在 RESOLVED，含完整 CREATED→ASSIGNED→STARTED→RESOLVED 时间线
        Ticket t3 = demoTicket("TDEMO0003", user2.getId(), "产品激活码提示已被使用，申请售后核实",
                "新购买的 X2 设备在激活时提示激活码已被使用，包装完整，之前没有激活过。希望核实激活码状态并重新激活。",
                "AFTER_SALES", "MEDIUM", "RESOLVED", agent.getId());
        // 回填 3 小时前的解决时间，使看板「今日解决数」有演示数据
        t3.setResolvedTime(LocalDateTime.now().minusHours(3));
        ticketMapper.updateById(t3);
        addEvent(t3.getId(), user2.getId(), "CREATED", null, "NEW", "提交工单");
        addEvent(t3.getId(), agent.getId(), "ASSIGNED", "NEW", "ASSIGNED", "分配处理人：客服小林");
        addEvent(t3.getId(), agent.getId(), "STARTED", "ASSIGNED", "IN_PROGRESS", "开始核实激活码记录");
        addEvent(t3.getId(), agent.getId(), "RESOLVED", "IN_PROGRESS", "RESOLVED",
                "已核实激活码被旧设备占用，已释放并协助客户重新激活，电话确认可正常使用。");

        // t4：NEW 且 assigneeId 为 null，用于演示待分配池
        Ticket t4 = demoTicket("TDEMO0004", user2.getId(), "办公室打印机无法连接，驱动安装失败",
                "三楼东侧打印机无法连接，驱动安装时提示「无法找到设备」。周围同事可以正常打印。",
                "TECHNICAL", "MEDIUM", "NEW", null);
        addEvent(t4.getId(), user2.getId(), "CREATED", null, "NEW", "提交工单");
    }

    /**
     * 按给定字段插入一张演示工单并返回带自增主键的实体。
     * <p>
     * 关键规则：version 初始化为 0，作为工单六态状态机 + version 乐观锁并发控制的起点；
     * ticketNo 为演示固定编号，便于人工识别。
     * @param no 工单编号
     * @param userId 提交人账号主键
     * @param title 标题
     * @param description 问题描述
     * @param category 分类
     * @param priority 优先级
     * @param status 初始状态
     * @param assigneeId 处理人账号主键，可为 null（未分配）
     * @return 插入后的工单实体（含主键，供后续写事件关联）
     */
    private Ticket demoTicket(String no, Long userId, String title, String description,
                              String category, String priority, String status, Long assigneeId) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo(no);
        ticket.setUserId(userId);
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setCategory(category);
        ticket.setPriority(priority);
        ticket.setStatus(status);
        ticket.setAssigneeId(assigneeId);
        // 乐观锁版本号从 0 起，后续状态流转由 version 防止并发覆盖
        ticket.setVersion(0);
        ticketMapper.insert(ticket);
        return ticket;
    }

    /**
     * 写入一条工单流转事件，记录操作人、事件类型、状态迁移前后值与说明。
     * <p>
     * 关键规则：oldStatus → newStatus 显式记录状态机跃迁（如 CREATED 时 oldStatus 为 null），
     * 供工单详情时间线回放与看板趋势统计使用；content 为面向人工的事件描述。
     * @param ticketId 关联工单主键
     * @param operatorId 操作人账号主键
     * @param eventType 事件类型：CREATED / ASSIGNED / STARTED / RESOLVED 等
     * @param oldStatus 迁移前状态，创建事件为 null
     * @param newStatus 迁移后状态
     * @param content 事件说明
     */
    private void addEvent(Long ticketId, Long operatorId, String eventType, String oldStatus,
                          String newStatus, String content) {
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setOperatorId(operatorId);
        event.setEventType(eventType);
        event.setOldStatus(oldStatus);
        event.setNewStatus(newStatus);
        event.setContent(content);
        ticketEventMapper.insert(event);
    }

    /**
     * 示例文档的不可变描述记录：所属知识库、classpath 资源路径与展示名。
     * <p>
     * 采用 Java 17 record 是因为它只承载三元组数据、无需可变状态，自动提供构造器、
     * 访问器与 equals/hashCode；仅在 seedKnowledge 内部组装 6 篇文档映射时使用。
     * @param knowledgeBase 目标知识库实体
     * @param resourcePath classpath 下的 Markdown 资源路径
     * @param displayName 文档在系统中展示的名称
     */
    private record SeedDoc(KnowledgeBase knowledgeBase, String resourcePath, String displayName) {
    }
}
