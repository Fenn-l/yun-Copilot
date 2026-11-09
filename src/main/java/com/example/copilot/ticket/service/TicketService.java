package com.example.copilot.ticket.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.copilot.ai.dto.HandoffRequest;
import com.example.copilot.common.exception.BusinessException;
import com.example.copilot.common.result.PageResult;
import com.example.copilot.common.security.CurrentUser;
import com.example.copilot.common.security.UserPrincipal;
import com.example.copilot.conversation.dto.MessageVO;
import com.example.copilot.conversation.entity.Conversation;
import com.example.copilot.conversation.service.ConversationService;
import com.example.copilot.system.entity.User;
import com.example.copilot.system.mapper.UserMapper;
import com.example.copilot.system.service.UserService;
import com.example.copilot.ticket.dto.CreateTicketRequest;
import com.example.copilot.ticket.dto.TicketDetailVO;
import com.example.copilot.ticket.dto.TicketEventVO;
import com.example.copilot.ticket.dto.TicketVO;
import com.example.copilot.ticket.entity.Ticket;
import com.example.copilot.ticket.entity.TicketEvent;
import com.example.copilot.ticket.mapper.TicketEventMapper;
import com.example.copilot.ticket.mapper.TicketMapper;
import com.example.copilot.ticket.state.TicketStateMachine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工单域业务服务，是 ticket 包乃至全项目的核心，串联「建单 → 分派 → 处理 → 解决/关闭 → 重开」
 * 的完整生命周期，并落地状态机、乐观锁、数据级权限与事件审计四类关键设计。
 *
 * <p>本类承担的职责清单：
 * <ul>
 *   <li>建单：用户主动提交（create）与 AI 会话转人工自动建单（createFromConversation），
 *       后者会把会话最近 8 条消息拼成描述摘要（最长 3000 字符）。</li>
 *   <li>查询：分页列表 page（按角色/归属做数据级过滤）、详情 detail（工单主体 + 事件时间线）。</li>
 *   <li>状态流转：assign / start / waitUser / resolve / close / reopen，全部先经
 *       TicketStateMachine.assertTransition 校验，再走 TicketMapper#updateState 的乐观锁 CAS。</li>
 *   <li>回复 reply：普通用户与客服共用；客服在 NEW 且未分配时回复会自动认领并置 IN_PROGRESS，
 *       在 WAITING_USER 状态下回复自动回到 IN_PROGRESS。</li>
 *   <li>属性调整 updateAttributes：仅客服可改分类/优先级，同样带 version 条件做乐观锁。</li>
 *   <li>事件审计：每次建单、流转、回复、调整都通过 addEvent 追加 cs_ticket_event 流水；
 *       所有写方法标注 @Transactional，保证「状态更新 + 事件写入」同一事务原子提交。</li>
 *   <li>兜底与展示：分类/优先级按关键词推断、工单号生成、实体到 VO 的转换与展示名回查。</li>
 * </ul>
 *
 * <p>全局设计：认证由 JWT 无状态过滤器完成，角色（USER/AGENT/ADMIN）与数据级归属校验
 * 集中在 Service 层（requireVisible / requireHandler 及各写方法内的 isAgent/isAdmin 判断），
 * Controller 不做方法级鉴权、不直接改状态。
 * 并发控制：状态/属性更新 SQL 的 WHERE 同时带 id + version + 原状态，
 * 影响行数 rows == 0 即抛 409 冲突「工单已被其他人更新，请刷新后重试」，更新成功 version + 1。
 */
@Service
@RequiredArgsConstructor
public class TicketService {

    /** 合法分类枚举集合：TECHNICAL/ACCOUNT/AFTER_SALES/PRODUCT/OTHER，用于入参归一化与属性调整校验。 */
    public static final Set<String> CATEGORIES = Set.of("TECHNICAL", "ACCOUNT", "AFTER_SALES", "PRODUCT", "OTHER");
    /** 合法优先级枚举集合：LOW/MEDIUM/HIGH/URGENT；命中紧急类关键词时兜底升为 HIGH。 */
    public static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "URGENT");

    /** 工单号时间戳格式 yyyyMMddHHmmss，配合 4 位随机数生成业务编号。 */
    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 工单主表 Mapper：常规 CRUD、分页，以及带乐观锁的状态流转更新 updateState。 */
    private final TicketMapper ticketMapper;
    /** 事件流水 Mapper：追加审计事件、按工单查询时间线。 */
    private final TicketEventMapper ticketEventMapper;
    /** 用户表 Mapper：回查创建人/处理人/操作人的展示名与角色。 */
    private final UserMapper userMapper;
    /** 用户服务：分配工单时校验目标处理人存在且角色为 AGENT/ADMIN。 */
    private final UserService userService;
    /** 会话服务：校验关联会话可访问、读取消息用于转人工摘要。 */
    private final ConversationService conversationService;

    /**
     * 用户主动提交工单（对应 POST /api/tickets）。
     *
     * <p>触发条件：任意已认证用户调用建单端点。权限要求：无角色限制，但若携带 conversationId，
     * 普通用户只能关联自己的会话（客服不限），否则抛 403。
     * <p>状态前置：无（新建）。状态变更：固定初始化为 NEW（禁止前端传 status，防绕过状态机），version 置 0。
     * <p>写事件：CREATED，oldStatus=null、newStatus=NEW，内容「提交工单」。
     * <p>并发处理：@Transactional 保证「insert 工单 + 写 CREATED 事件」原子提交；
     * 分类/优先级经 normalizeCategory / normalizePriority 归一化并按关键词兜底，标题/描述 trim 后落库。
     */
    @Transactional
    public TicketVO create(CreateTicketRequest request) {
        UserPrincipal principal = CurrentUser.get();
        // 分类/优先级先归一化，非法或缺失值再由「标题+描述」关键词推断兜底
        String category = normalizeCategory(request.getCategory(), request.getTitle() + " " + request.getDescription());
        String priority = normalizePriority(request.getPriority(), request.getDescription());
        // 关联会话时校验归属：普通用户不得把工单挂到别人的会话上
        if (request.getConversationId() != null) {
            Conversation conversation = conversationService.requireAccessible(request.getConversationId());
            if (!principal.isAgent() && !Objects.equals(conversation.getUserId(), principal.getId())) {
                throw BusinessException.forbidden("无权关联该会话");
            }
        }
        Ticket ticket = new Ticket();
        ticket.setTicketNo(nextTicketNo());
        ticket.setUserId(principal.getId());
        ticket.setConversationId(request.getConversationId());
        ticket.setTitle(request.getTitle().trim());
        ticket.setDescription(request.getDescription().trim());
        ticket.setCategory(category);
        ticket.setPriority(priority);
        // 状态一律由服务端初始化为 NEW，前端无法指定，避免绕过六态状态机
        ticket.setStatus(TicketStateMachine.NEW);
        // 乐观锁版本号从 0 起，后续每次成功更新由 updateState 自增
        ticket.setVersion(0);
        ticketMapper.insert(ticket);
        // 建单即写 CREATED 事件，作为时间线起点（oldStatus 为 null 表示此前无状态）
        addEvent(ticket.getId(), principal.getId(), "CREATED", null, TicketStateMachine.NEW, "提交工单");
        return toVO(ticketMapper.selectById(ticket.getId()));
    }

    /**
     * AI 会话「转人工」自动建单（配合 ai/copilot 域 HandoffRequest，在知识不足或 AI 故障时降级触发）。
     *
     * <p>触发条件：会话发起转人工。权限要求：普通用户只能转自己的会话（客服不限），
     * 先经 conversationService.requireAccessible 保证会话可访问，再校验归属，否则抛 403。
     * <p>状态前置：无。状态变更：固定初始化 NEW、version=0；userId 取会话创建人而非当前操作人。
     * <p>写事件：CREATED，内容「由 AI 会话转人工创建」。
     * <p>边界：标题优先取请求值，否则取会话标题，再否则用默认「AI 会话转人工」，超 200 字符截断；
     * 描述优先取请求值，为空时用会话最近 8 条消息拼成摘要（最长 3000 字符，见 buildConversationSummary）。
     * <p>并发处理：@Transactional 保证 insert 与 CREATED 事件写入原子提交。
     */
    @Transactional
    public TicketVO createFromConversation(HandoffRequest request) {
        UserPrincipal principal = CurrentUser.get();
        Conversation conversation = conversationService.requireAccessible(request.getConversationId());
        // 普通用户只能把自己发起的会话转人工，客服不受此限
        if (!principal.isAgent() && !Objects.equals(conversation.getUserId(), principal.getId())) {
            throw BusinessException.forbidden("无权操作该会话");
        }
        // 标题取值优先级：请求指定 > 会话标题 > 默认文案「AI 会话转人工」
        String title = StringUtils.hasText(request.getTitle()) ? request.getTitle().trim()
                : (StringUtils.hasText(conversation.getTitle()) ? conversation.getTitle() : "AI 会话转人工");
        // 标题最长 200 字符，超出直接截断，与 CreateTicketRequest 的 @Size(max=200) 对齐
        if (title.length() > 200) {
            title = title.substring(0, 200);
        }
        String description = request.getDescription();
        // 未提供描述时，用会话最近消息拼成摘要，保证转人工工单自带上下文
        if (!StringUtils.hasText(description)) {
            description = buildConversationSummary(conversation.getId());
        }
        String category = normalizeCategory(request.getCategory(), title + " " + description);
        String priority = normalizePriority(request.getPriority(), description);

        Ticket ticket = new Ticket();
        ticket.setTicketNo(nextTicketNo());
        // 归属会话创建人：即便由客服代为转人工，工单仍属于发起会话的用户
        ticket.setUserId(conversation.getUserId());
        ticket.setConversationId(conversation.getId());
        ticket.setTitle(title);
        ticket.setDescription(description);
        ticket.setCategory(category);
        ticket.setPriority(priority);
        ticket.setStatus(TicketStateMachine.NEW);
        ticket.setVersion(0);
        ticketMapper.insert(ticket);
        // 写 CREATED 事件并标注来源，便于时间线区分手工建单与会话转人工
        addEvent(ticket.getId(), principal.getId(), "CREATED", null, TicketStateMachine.NEW,
                "由 AI 会话转人工创建");
        return toVO(ticketMapper.selectById(ticket.getId()));
    }

    /**
     * 分页查询工单列表（对应 GET /api/tickets/page）。
     *
     * <p>触发条件：列表/看板查询。权限要求（数据级，在 Service 层以 WHERE 条件强制过滤，是最关键的分级）：
     * 普通用户强制只看 userId 等于自己的工单；客服默认可看全部，mine=true 时只看分配给自己的；
     * ADMIN 属客服体系同样默认看全部。
     * <p>状态前置 / 状态变更 / 写事件：均为无（只读端点）。
     * <p>过滤：状态、处理人、分类、优先级精确匹配，关键词对标题与工单号做 OR LIKE；
     * 按 updateTime 倒序，PageResult.from 统一把实体转 TicketVO。
     * <p>并发处理：只读，无需加锁或版本校验。
     */
    public PageResult<TicketVO> page(long page, long size, String status, Long assigneeId,
                                     String category, String priority, String keyword, Boolean mine) {
        UserPrincipal principal = CurrentUser.get();
        var wrapper = Wrappers.<Ticket>lambdaQuery().orderByDesc(Ticket::getUpdateTime);
        // 数据级权限第一分支：普通用户无论如何只能看到自己创建的工单
        if (!principal.isAgent()) {
            wrapper.eq(Ticket::getUserId, principal.getId());
        // 客服在 mine=true 时收窄为「我的待办」，否则不加归属条件、可看全部
        } else if (Boolean.TRUE.equals(mine)) {
            wrapper.eq(Ticket::getAssigneeId, principal.getId());
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Ticket::getStatus, status);
        }
        if (assigneeId != null) {
            wrapper.eq(Ticket::getAssigneeId, assigneeId);
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(Ticket::getCategory, category);
        }
        if (StringUtils.hasText(priority)) {
            wrapper.eq(Ticket::getPriority, priority);
        }
        // 关键词用 and(...) 包住 OR，避免与其他过滤条件串联时被 OR 破坏优先级
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(Ticket::getTitle, keyword).or().like(Ticket::getTicketNo, keyword));
        }
        Page<Ticket> result = ticketMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.from(result, this::toVO);
    }

    /**
     * 查询工单详情与事件时间线（对应 GET /api/tickets/{id}）。
     *
     * <p>权限要求：先经 requireVisible（普通用户仅能看自己的，客服/ADMIN 不限）。
     * <p>状态前置 / 状态变更 / 写事件：无（只读端点）。
     * <p>按 ticketId 取该工单全部 cs_ticket_event，按事件主键升序组成时间线，与工单主体一起
     * 封装成 TicketDetailVO；事件经 toEventVO 补齐操作人展示名与角色，实现可追溯审计。
     * <p>并发处理：只读，无需加锁。
     */
    public TicketDetailVO detail(Long id) {
        Ticket ticket = requireVisible(id);
        // 时间线按事件主键升序，等价于按发生先后排序
        List<TicketEvent> events = ticketEventMapper.selectList(Wrappers.<TicketEvent>lambdaQuery()
                .eq(TicketEvent::getTicketId, id)
                .orderByAsc(TicketEvent::getId));
        return new TicketDetailVO(toVO(ticket), events.stream().map(this::toEventVO).toList());
    }

    /**
     * 按 ID 读取工单并执行数据级可见性校验，是几乎所有读写方法的公共前置（被多数 public 方法调用）。
     *
     * <p>权限要求：工单不存在抛 404「工单不存在」；普通用户仅当 userId 等于自身才可访问，
     * 否则抛 403「无权查看该工单」；客服/ADMIN 放行。
     * <p>边界：返回的是当前数据库快照（含 version），调用方据此做乐观锁 CAS，
     * 避免基于过期数据发起状态更新；本方法本身为 public 但只读、不写事件。
     */
    public Ticket requireVisible(Long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw BusinessException.notFound("工单不存在");
        }
        UserPrincipal principal = CurrentUser.get();
        // 数据级归属校验：普通用户不能查看/操作他人工单，客服与管理员不受限
        if (!principal.isAgent() && !Objects.equals(ticket.getUserId(), principal.getId())) {
            throw BusinessException.forbidden("无权查看该工单");
        }
        return ticket;
    }

    /**
     * 分配工单给指定处理人（对应 POST /api/tickets/{id}/assign）。
     *
     * <p>触发条件：客服/管理员为工单指定处理人。权限要求：仅 isAgent（客服/ADMIN），否则 403；
     * 目标处理人必须存在且角色为 AGENT/ADMIN，否则 400。
     * <p>状态前置：NEW 或 ASSIGNED（其余状态 409）；再经 assertTransition(from, ASSIGNED) 校验合法转移。
     * <p>状态变更：→ ASSIGNED，同时写入 assigneeId；resolvedTime/closedTime 保持原值。
     * <p>写事件：ASSIGNED，内容「分配处理人：展示名」。
     * <p>并发处理：@Transactional + updateState 的 WHERE「id + version + 原状态」乐观锁 CAS，
     * rows == 0 抛 409 冲突（工单已被其他人更新）；成功 version + 1。
     */
    @Transactional
    public TicketVO assign(Long id, Long assigneeId) {
        UserPrincipal principal = CurrentUser.get();
        // 仅客服/管理员可分配，普通用户直接拒绝
        if (!principal.isAgent()) {
            throw BusinessException.forbidden("只有客服或管理员可以分配工单");
        }
        Ticket ticket = requireVisible(id);
        User assignee = userService.requireById(assigneeId);
        // 处理人角色限制：工单只能交给客服或管理员，不能分配给普通用户
        if (!"AGENT".equals(assignee.getRole()) && !"ADMIN".equals(assignee.getRole())) {
            throw BusinessException.badRequest("只能分配给客服或管理员");
        }
        // 状态前置：只有 NEW / ASSIGNED 可被（重新）分配，处理中及之后不可回退分配
        if (!TicketStateMachine.NEW.equals(ticket.getStatus())
                && !TicketStateMachine.ASSIGNED.equals(ticket.getStatus())) {
            throw BusinessException.conflict("当前状态不可分配: " + ticket.getStatus());
        }
        // 状态机第一道闸门：校验 from → ASSIGNED 是否为表内合法转移
        TicketStateMachine.assertTransition(ticket.getStatus(), TicketStateMachine.ASSIGNED);
        // 乐观锁 CAS 落库：WHERE 同时带 id + version + oldStatus，任一不匹配则影响行数为 0
        int rows = ticketMapper.updateState(id, ticket.getVersion(), ticket.getStatus(),
                TicketStateMachine.ASSIGNED, assigneeId, ticket.getResolvedTime(), ticket.getClosedTime());
        // rows==0 说明期间被他人改动（版本或状态已变），抛 409 让前端刷新重试，绝不静默失败
        if (rows == 0) {
            throw conflict();
        }
        // 分配成功写 ASSIGNED 事件，时间线记录「分配给了谁」
        addEvent(id, principal.getId(), "ASSIGNED", ticket.getStatus(), TicketStateMachine.ASSIGNED,
                "分配处理人：" + displayName(assignee));
        return toVO(ticketMapper.selectById(id));
    }

    /**
     * 开始处理工单（对应 POST /api/tickets/{id}/start）。
     *
     * <p>触发条件：客服接单并进入处理。权限要求：仅客服（ADMIN 例外放行归属校验）；
     * 非 ADMIN 时只能处理分配给自己的工单，否则 403。
     * <p>状态前置：ASSIGNED；若当前为 NEW，则先「自动认领」置 ASSIGNED（写 ASSIGNED 事件）再继续。
     * <p>状态变更：→ IN_PROGRESS（经 transition → assertTransition → updateState）。
     * <p>写事件：NEW 时先写 ASSIGNED「客服接单」，最终写 STARTED「开始处理工单」。
     * <p>并发处理：@Transactional 保证两步更新与事件原子提交；每步 updateState 均走乐观锁 CAS，
     * rows == 0 抛 409；自动认领后重新 selectById 取新 version，避免连续更新误用同一旧版本。
     */
    @Transactional
    public TicketVO start(Long id) {
        UserPrincipal principal = CurrentUser.get();
        // 开始处理是客服专属动作，普通用户无权触发
        if (!principal.isAgent()) {
            throw BusinessException.forbidden("只有客服可以开始处理工单");
        }
        Ticket ticket = requireVisible(id);
        // 自动认领：工单还在 NEW 时，由当前客服接单，先转为 ASSIGNED 并把自己设为处理人
        if (TicketStateMachine.NEW.equals(ticket.getStatus())) {
            // 同样走乐观锁 CAS，防止多人同时抢单
            int rows = ticketMapper.updateState(id, ticket.getVersion(), ticket.getStatus(),
                    TicketStateMachine.ASSIGNED, principal.getId(),
                    ticket.getResolvedTime(), ticket.getClosedTime());
            if (rows == 0) {
                throw conflict();
            }
            // 认领事件 oldStatus 固定 NEW → ASSIGNED，内容标注接单客服昵称
            addEvent(id, principal.getId(), "ASSIGNED", TicketStateMachine.NEW,
                    TicketStateMachine.ASSIGNED, "客服接单：" + principal.getNickname());
            // 重新读取最新快照（version 已 +1），供后续 IN_PROGRESS 转移使用正确的版本号
            ticket = ticketMapper.selectById(id);
        }
        // 二次状态前置：经过上面的认领后，此刻必须是 ASSIGNED 才能开始处理
        if (!TicketStateMachine.ASSIGNED.equals(ticket.getStatus())) {
            throw BusinessException.conflict("只有已分配的工单可以开始处理，当前状态: " + ticket.getStatus());
        }
        // 归属校验：非管理员只能开始处理分配给自己的工单
        if (!principal.isAdmin() && !Objects.equals(ticket.getAssigneeId(), principal.getId())) {
            throw BusinessException.forbidden("只能处理分配给自己的工单");
        }
        // 统一经 transition 完成 ASSIGNED → IN_PROGRESS：状态机校验 + 乐观锁 CAS + STARTED 事件
        transition(ticket, TicketStateMachine.IN_PROGRESS, ticket.getAssigneeId(),
                ticket.getResolvedTime(), ticket.getClosedTime(), "STARTED", "开始处理工单");
        return toVO(ticketMapper.selectById(id));
    }

    /**
     * 回复工单（对应 POST /api/tickets/{id}/reply），普通用户与客服共用的核心交互方法。
     *
     * <p>触发条件：在工单下追加一条消息。权限要求：普通用户只能回复自己的工单（owner 判定），
     * 客服只能回复分配给自己的（ADMIN 例外），否则 403。
     * <p>状态前置：CLOSED 禁止回复，须先 reopen（409）；其余状态均可回复。
     * <p>自动认领与自动恢复（本方法最关键的两条规则）：
     * 客服在 NEW 且未分配时回复，经 claimAndStart 自动 ASSIGNED → IN_PROGRESS 并认领；
     * 其他「未分配但已有状态」的情况仅补写 assigneeId、不改状态；
     * 客服在 WAITING_USER 状态下回复，自动把状态改回 IN_PROGRESS。普通用户回复始终不改状态。
     * <p>写事件：固定 REPLY，oldStatus → newStatus 体现上述状态变化，内容为回复正文（trim）。
     * <p>并发处理：@Transactional；每次 updateState 带 version + oldStatus 乐观锁 CAS，
     * rows == 0 抛 409；状态不变时不调用 updateState，仅写 REPLY 事件，避免无谓的版本自增。
     */
    @Transactional
    public TicketVO reply(Long id, String content) {
        UserPrincipal principal = CurrentUser.get();
        Ticket ticket = requireVisible(id);
        // 已关闭工单不可直接回复，必须先 reopen，防止在终态上继续产生交互
        if (TicketStateMachine.CLOSED.equals(ticket.getStatus())) {
            throw BusinessException.conflict("工单已关闭，请先重新打开后再回复");
        }
        boolean owner = Objects.equals(ticket.getUserId(), principal.getId());
        // 客服侧：优先处理「自动认领 / 补写处理人 / 归属校验」
        if (principal.isAgent()) {
            // 自动认领场景：工单仍是 NEW 且无人处理，客服一回复即认领并直接进入 IN_PROGRESS
            if (TicketStateMachine.NEW.equals(ticket.getStatus()) && ticket.getAssigneeId() == null) {
                ticket = claimAndStart(ticket, principal);
            // 有状态但未分配：只把当前客服补为处理人，状态保持不变（oldStatus==newStatus）
            } else if (ticket.getAssigneeId() == null) {
                int rows = ticketMapper.updateState(id, ticket.getVersion(), ticket.getStatus(),
                        ticket.getStatus(), principal.getId(), ticket.getResolvedTime(), ticket.getClosedTime());
                if (rows == 0) {
                    throw conflict();
                }
                // oldStatus 与 newStatus 相同，表示这是一次「仅认领、不改状态」的事件
                addEvent(id, principal.getId(), "ASSIGNED", ticket.getStatus(), ticket.getStatus(),
                        "客服接单：" + principal.getNickname());
                ticket = ticketMapper.selectById(id);
            // 已分配给他人且非管理员：禁止越权回复
            } else if (!principal.isAdmin() && !Objects.equals(ticket.getAssigneeId(), principal.getId())) {
                throw BusinessException.forbidden("只能回复分配给自己的工单");
            }
        // 普通用户侧：必须是工单创建人本人，否则拒绝
        } else if (!owner) {
            throw BusinessException.forbidden("只能回复自己的工单");
        }
        String oldStatus = ticket.getStatus();
        String newStatus = oldStatus;
        // 客服在「等待用户回复」状态下再次发言，视为恢复处理，自动回到 IN_PROGRESS
        if (principal.isAgent() && TicketStateMachine.WAITING_USER.equals(oldStatus)) {
            newStatus = TicketStateMachine.IN_PROGRESS;
        }
        // 仅当状态确有变化时才走乐观锁 CAS 更新；不变则跳过，避免版本号无意义地自增
        if (!newStatus.equals(oldStatus)) {
            int rows = ticketMapper.updateState(id, ticket.getVersion(), oldStatus, newStatus,
                    ticket.getAssigneeId(), ticket.getResolvedTime(), ticket.getClosedTime());
            // rows==0 表示并发下状态/版本已被他人改动，抛 409 提示刷新重试
            if (rows == 0) {
                throw conflict();
            }
        }
        // 无论状态是否变化，都追加 REPLY 事件，保证每条回复都进入审计时间线
        addEvent(id, principal.getId(), "REPLY", oldStatus, newStatus, content.trim());
        return toVO(ticketMapper.selectById(id));
    }

    /**
     * 标记「等待用户补充信息」（对应 POST /api/tickets/{id}/wait-user）。
     *
     * <p>触发条件：客服已回复、需用户补充材料。权限要求：requireHandler（仅客服，ADMIN 例外）。
     * <p>状态前置：由 assertTransition 校验（IN_PROGRESS → WAITING_USER 为主链路），表外流转抛 409。
     * 状态变更：→ WAITING_USER。
     * <p>写事件：WAITING_USER，content 为空时用默认「等待用户补充信息」。
     * <p>并发处理：@Transactional + transition 内乐观锁 CAS，rows == 0 抛 409；
     * 未分配时把当前客服补为处理人，resolvedTime/closedTime 保持原值。
     */
    @Transactional
    public TicketVO waitUser(Long id, String content) {
        UserPrincipal principal = CurrentUser.get();
        Ticket ticket = requireVisible(id);
        // 等待用户是客服动作：校验角色与「只能处理分配给自己的单」
        requireHandler(ticket, principal);
        // 处理人兜底：未分配时以当前客服作为处理人，避免工单无主
        Long assignee = ticket.getAssigneeId() == null ? principal.getId() : ticket.getAssigneeId();
        // 统一走 transition：状态机校验 + 乐观锁 CAS + WAITING_USER 事件
        transition(ticket, TicketStateMachine.WAITING_USER, assignee,
                ticket.getResolvedTime(), ticket.getClosedTime(), "WAITING_USER",
                StringUtils.hasText(content) ? content.trim() : "等待用户补充信息");
        return toVO(ticketMapper.selectById(id));
    }

    /**
     * 标记工单「已解决」（对应 POST /api/tickets/{id}/resolve）。
     *
     * <p>触发条件：客服给出解决方案。权限要求：requireHandler（仅客服，ADMIN 例外）。
     * <p>状态前置：由 assertTransition 校验（IN_PROGRESS / WAITING_USER → RESOLVED）。状态变更：→ RESOLVED。
     * <p>写事件：RESOLVED，content 为空用默认「问题已解决」。
     * <p>时间字段：transition 传入 LocalDateTime.now() 作为 resolvedTime 写入本次解决时间；
     * closedTime 保持原值（尚未关闭）。
     * <p>并发处理：@Transactional + 乐观锁 CAS，rows == 0 抛 409；未分配时补当前客服为处理人。
     */
    @Transactional
    public TicketVO resolve(Long id, String content) {
        UserPrincipal principal = CurrentUser.get();
        Ticket ticket = requireVisible(id);
        requireHandler(ticket, principal);
        Long assignee = ticket.getAssigneeId() == null ? principal.getId() : ticket.getAssigneeId();
        // 第三个时间参数 resolvedTime = now()，第四个 closedTime 沿用原值；配合写 RESOLVED 事件
        transition(ticket, TicketStateMachine.RESOLVED, assignee,
                LocalDateTime.now(), ticket.getClosedTime(), "RESOLVED",
                StringUtils.hasText(content) ? content.trim() : "问题已解决");
        return toVO(ticketMapper.selectById(id));
    }

    /**
     * 确认关闭工单（对应 POST /api/tickets/{id}/close）。
     *
     * <p>触发条件：创建人认可解决方案，或处理人代为关闭。权限要求：创建人（owner）优先放行；
     * 非创建人则须经 requireHandler（客服/ADMIN 且为分配给自己）。
     * <p>状态前置：由 assertTransition 校验（RESOLVED → CLOSED 为主链路）。状态变更：→ CLOSED。
     * <p>写事件：CLOSED，content 为空用默认「确认关闭工单」。
     * <p>时间字段：transition 传入 LocalDateTime.now() 作为 closedTime 写入；resolvedTime 保持原值。
     * <p>并发处理：@Transactional + 乐观锁 CAS，rows == 0 抛 409。
     */
    @Transactional
    public TicketVO close(Long id, String content) {
        UserPrincipal principal = CurrentUser.get();
        Ticket ticket = requireVisible(id);
        boolean owner = Objects.equals(ticket.getUserId(), principal.getId());
        // 创建人可直接确认关闭；非创建人则必须满足 requireHandler 的处理人校验
        if (!owner) {
            requireHandler(ticket, principal);
        }
        // 第四个时间参数 closedTime = now()，resolvedTime 沿用原值；处理人保持现状
        transition(ticket, TicketStateMachine.CLOSED, ticket.getAssigneeId(),
                ticket.getResolvedTime(), LocalDateTime.now(), "CLOSED",
                StringUtils.hasText(content) ? content.trim() : "确认关闭工单");
        return toVO(ticketMapper.selectById(id));
    }

    /**
     * 重新打开工单（对应 POST /api/tickets/{id}/reopen）。
     *
     * <p>触发条件：创建人不认可解决/关闭结果，或客服需继续处理。权限要求：创建人（owner）优先放行；
     * 非创建人则须经 requireHandler。
     * <p>状态前置：由 assertTransition 校验（RESOLVED / CLOSED → IN_PROGRESS）。状态变更：→ IN_PROGRESS。
     * <p>写事件：REOPENED，content 为空用默认「重新打开工单」。
     * <p>时间字段：resolvedTime 与 closedTime 均传 null 清空，表示回到未解决、未关闭状态；
     * 未分配且操作人是客服时把其设为处理人。
     * <p>并发处理：@Transactional + 乐观锁 CAS，rows == 0 抛 409。
     */
    @Transactional
    public TicketVO reopen(Long id, String content) {
        UserPrincipal principal = CurrentUser.get();
        Ticket ticket = requireVisible(id);
        boolean owner = Objects.equals(ticket.getUserId(), principal.getId());
        // 创建人可重开；否则要求是合法处理人
        if (!owner) {
            requireHandler(ticket, principal);
        }
        Long assignee = ticket.getAssigneeId();
        // 重开时若工单原本无处理人且操作人是客服，则顺势由其接手
        if (assignee == null && principal.isAgent()) {
            assignee = principal.getId();
        }
        // 两个时间参数均传 null：清空 resolvedTime 与 closedTime，回到处理中的初始计时状态
        transition(ticket, TicketStateMachine.IN_PROGRESS, assignee, null, null, "REOPENED",
                StringUtils.hasText(content) ? content.trim() : "重新打开工单");
        return toVO(ticketMapper.selectById(id));
    }

    /**
     * 调整工单分类与优先级（对应 POST /api/tickets/{id}/attributes）。
     *
     * <p>触发条件：客服修正工单归类或紧急程度。权限要求：仅客服（isAgent），且 requireHandler
     * 校验「只能改分配给自己的单」（ADMIN 例外）。
     * <p>状态前置：非 CLOSED（已关闭须先 reopen，否则 409）；状态变更：无（状态保持不变）。
     * <p>写事件：UPDATED，oldStatus 与 newStatus 相同，内容记录新分类与新优先级。
     * <p>校验：分类/优先级转大写后必须属于 CATEGORIES / PRIORITIES，否则 400；
     * null 入参被折叠成空串，必然在枚举校验中被拒绝。
     * <p>并发处理：@Transactional；用 lambdaUpdate 显式带 WHERE id + version 做乐观锁
     * （此处不改状态，故 WHERE 不带 status），并 setSql("version = version + 1") 自增版本；
     * rows == 0 抛 409，与状态流转共用同一冲突语义。
     */
    @Transactional
    public TicketVO updateAttributes(Long id, String category, String priority) {
        UserPrincipal principal = CurrentUser.get();
        // 权限：仅客服可调整分类与优先级
        if (!principal.isAgent()) {
            throw BusinessException.forbidden("只有客服可以调整工单分类与优先级");
        }
        Ticket ticket = requireVisible(id);
        // 数据级：只能调整分配给自己的工单（ADMIN 例外）
        requireHandler(ticket, principal);
        // 已关闭工单不可调整，须先 reopen 回到 IN_PROGRESS
        if (TicketStateMachine.CLOSED.equals(ticket.getStatus())) {
            throw BusinessException.conflict("已关闭工单不能调整分类，请先重新打开");
        }
        // 入参转大写；null 被折叠为空串，必然在下方枚举校验中被拒绝
        String newCategory = category == null ? "" : category.trim().toUpperCase();
        String newPriority = priority == null ? "" : priority.trim().toUpperCase();
        // 分类合法性校验：必须属于 CATEGORIES 五个枚举之一
        if (!CATEGORIES.contains(newCategory)) {
            throw BusinessException.badRequest("非法工单分类");
        }
        // 优先级合法性校验：必须属于 PRIORITIES 四个枚举之一
        if (!PRIORITIES.contains(newPriority)) {
            throw BusinessException.badRequest("非法优先级");
        }
        // 乐观锁更新：WHERE 带 id + version（不改状态故不带 status），setSql 令 version + 1
        int rows = ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
                .eq(Ticket::getId, id)
                .eq(Ticket::getVersion, ticket.getVersion())
                .set(Ticket::getCategory, newCategory)
                .set(Ticket::getPriority, newPriority)
                .setSql("version = version + 1"));
        // rows==0 说明版本已被他人改动，抛 409 冲突，防止并发覆盖属性
        if (rows == 0) {
            throw conflict();
        }
        // 写 UPDATED 事件；状态未变故 oldStatus 与 newStatus 相同
        addEvent(id, principal.getId(), "UPDATED", ticket.getStatus(), ticket.getStatus(),
                "调整分类为 " + newCategory + "，优先级为 " + newPriority);
        return toVO(ticketMapper.selectById(id));
    }

    /**
     * 客服「自动认领并开始处理」的内部两步操作，仅供 reply 在工单为 NEW 且未分配时调用。
     *
     * <p>做什么：先以乐观锁 CAS 把 NEW → ASSIGNED 并认领（写 ASSIGNED 事件），
     * 再重新读取最新工单，调用 transition 把 ASSIGNED → IN_PROGRESS 并写 STARTED 事件。
     * <p>关键规则：两步必须连续成功；中途重新 selectById 是为了拿到 version+1 后的最新快照，
     * 否则第二次 CAS 会沿用旧版本而必然冲突。
     * <p>边界/异常：任一 updateState 影响行数为 0 即抛 409 冲突；返回两次流转后的最新工单。
     */
    private Ticket claimAndStart(Ticket ticket, UserPrincipal principal) {
        // 第一步：NEW → ASSIGNED 并把自己设为处理人，乐观锁 CAS 防抢单冲突
        int rows = ticketMapper.updateState(ticket.getId(), ticket.getVersion(), ticket.getStatus(),
                TicketStateMachine.ASSIGNED, principal.getId(),
                ticket.getResolvedTime(), ticket.getClosedTime());
        if (rows == 0) {
            throw conflict();
        }
        // 写认领事件，oldStatus 为 NEW，标注接单客服昵称
        addEvent(ticket.getId(), principal.getId(), "ASSIGNED", TicketStateMachine.NEW,
                TicketStateMachine.ASSIGNED, "客服接单：" + principal.getNickname());
        // 重新读取：拿到 version 自增后的工单，供第二步转移使用正确版本号
        Ticket fresh = ticketMapper.selectById(ticket.getId());
        // 第二步：ASSIGNED → IN_PROGRESS，写 STARTED 事件
        transition(fresh, TicketStateMachine.IN_PROGRESS, principal.getId(),
                fresh.getResolvedTime(), fresh.getClosedTime(), "STARTED", "开始处理工单");
        return ticketMapper.selectById(ticket.getId());
    }

    /**
     * 通用状态流转引擎，被 start / waitUser / resolve / close / reopen / claimAndStart 复用，
     * 是全包「禁止前端直接改状态 + 乐观锁 + 事件审计」的集中落地点。
     *
     * <p>做什么：把工单从当前状态迁移到 target，同步落库处理人、解决/关闭时间与审计事件。
     * <p>关键规则（三件事的顺序即本包并发控制核心）：
     * ① assertTransition 校验 from → target 是否为 ALLOWED 表内合法转移，非法抛 409；
     * ② updateState 以 WHERE「id + version + oldStatus」乐观锁 CAS 落库，成功 version + 1，
     *    并写入 assigneeId / resolvedTime / closedTime（传 null 即清空对应时间，用于 reopen）；
     * ③ 以当前登录人 ID（CurrentUser.id()）写事件，eventType 与 content 由调用方指定。
     * <p>边界/异常：rows == 0 表示期间被他人更新，抛 409「工单已被其他人更新，请刷新后重试」；
     * 由外层 @Transactional 保证「状态更新 + 事件写入」同事务原子提交或回滚。
     */
    private void transition(Ticket ticket, String target, Long assigneeId, LocalDateTime resolvedTime,
                            LocalDateTime closedTime, String eventType, String content) {
        // 第一道闸门：查状态转移表，禁止任何表外流转（含前端伪造 target 的情况）
        TicketStateMachine.assertTransition(ticket.getStatus(), target);
        // 乐观锁 CAS：id + version + 原状态三重条件，resolvedTime/closedTime 为 null 时清空时间字段
        int rows = ticketMapper.updateState(ticket.getId(), ticket.getVersion(), ticket.getStatus(),
                target, assigneeId, resolvedTime, closedTime);
        // 影响行数为 0 = 并发冲突，交给调用方事务回滚，不吞掉异常
        if (rows == 0) {
            throw conflict();
        }
        // 构造并写入审计事件：记录操作人、事件类型、旧/新状态与内容
        addEvent(ticket.getId(), CurrentUser.id(), eventType, ticket.getStatus(), target, content);
    }

    /**
     * 「只能由合法处理人操作」的公共数据级权限校验，被 waitUser/resolve/close/reopen/updateAttributes 调用。
     *
     * <p>做什么：ADMIN 直接放行（三级角色中的最高权限例外）；非客服（普通用户）一律 403
     * 「只有客服可以执行该操作」；客服在工单已分配给自己以外的人时 403「只能处理分配给自己的工单」。
     * <p>关键规则：工单未分配（assigneeId == null）时客服可通过（相当于允许自行接手），
     * 与「自动认领」逻辑一致；ADMIN 不受归属限制。
     * <p>边界：只做校验、不写事件、不改状态，异常由调用方事务接管。
     */
    private void requireHandler(Ticket ticket, UserPrincipal principal) {
        // ADMIN 例外：不受角色与归属限制，直接放行
        if (principal.isAdmin()) {
            return;
        }
        // 普通用户不能执行客服类状态动作
        if (!principal.isAgent()) {
            throw BusinessException.forbidden("只有客服可以执行该操作");
        }
        // 已分配给他人时禁止越权处理；未分配则放行以便客服认领
        if (ticket.getAssigneeId() != null && !Objects.equals(ticket.getAssigneeId(), principal.getId())) {
            throw BusinessException.forbidden("只能处理分配给自己的工单");
        }
    }

    /**
     * 构造统一的乐观锁冲突异常（409）。
     *
     * <p>做什么：所有 updateState / 属性更新出现 rows == 0 时调用，文案固定为
     * 「工单已被其他人更新，请刷新后重试」，提示前端刷新取最新 version 后再提交。
     * <p>关键规则：集中一处定义，保证各流转端点冲突语义与提示一致，避免并发覆盖被静默忽略。
     */
    private BusinessException conflict() {
        // 统一 409 文案：让调用方感知是并发冲突而非业务校验失败
        return BusinessException.conflict("工单已被其他人更新，请刷新后重试");
    }

    /**
     * 追加一条工单审计事件到 cs_ticket_event，是所有状态流转与交互的时间线写入口。
     *
     * <p>做什么：把 ticketId / operatorId / eventType / oldStatus / newStatus / content 组装成
     * TicketEvent 并 insert；createTime 由自动填充写入。
     * <p>关键规则：事件只追加不修改；oldStatus 为 null 表示建单前无状态，
     * oldStatus == newStatus 表示状态未变（回复、属性调整）；operatorId 取当前操作人。
     * <p>边界：由外层 @Transactional 与状态更新同事务，任一失败一并回滚，保证状态与时间线一致。
     */
    private void addEvent(Long ticketId, Long operatorId, String eventType, String oldStatus,
                          String newStatus, String content) {
        // 构造事件对象：逐项记录归属工单、操作人、事件类型、旧/新状态与内容
        TicketEvent event = new TicketEvent();
        event.setTicketId(ticketId);
        event.setOperatorId(operatorId);
        event.setEventType(eventType);
        event.setOldStatus(oldStatus);
        event.setNewStatus(newStatus);
        event.setContent(content);
        // 只追加插入，作为 detail 接口时间线的一条记录
        ticketEventMapper.insert(event);
    }

    /**
     * 为「转人工」建单生成会话摘要，作为工单描述的兜底内容。
     *
     * <p>做什么：取会话最近最多 40 条消息中的最后 8 条，按「用户：/AI：」逐条拼接成文本。
     * <p>关键规则：每条消息内容超过 300 字符截断并加「...」；整段摘要超过 3000 字符时
     * 取末尾 3000 字符（保留最近、最相关的上下文）；会话无有效消息时返回提示文案。
     * <p>边界：content 为 null 视作空串，避免空指针；仅供 createFromConversation 在未提供描述时调用。
     */
    private String buildConversationSummary(Long conversationId) {
        // 读取最近最多 40 条消息作为摘要候选集
        List<MessageVO> messages = conversationService.messages(conversationId, 40);
        StringBuilder builder = new StringBuilder();
        // 只取最后 8 条：控制摘要长度并聚焦最近对话，不足 8 条时从第 0 条起
        int start = Math.max(0, messages.size() - 8);
        for (int i = start; i < messages.size(); i++) {
            MessageVO message = messages.get(i);
            // 角色映射：USER 显示「用户」，其余（ASSISTANT 等）统一显示「AI」
            String role = "USER".equals(message.getRole()) ? "用户" : "AI";
            // 空指针兜底：无内容按空串处理，并去除首尾空白
            String content = message.getContent() == null ? "" : message.getContent().trim();
            // 单条消息超过 300 字符则截断并加省略号，防止个别长消息撑爆摘要
            if (content.length() > 300) {
                content = content.substring(0, 300) + "...";
            }
            builder.append(role).append("：").append(content).append('\n');
        }
        String text = builder.toString().trim();
        // 会话无有效消息时给出兜底描述，引导客服查看关联会话
        if (text.isBlank()) {
            return "用户通过 AI 会话发起转人工请求，请查看关联会话了解详情。";
        }
        // 整段超过 3000 字符时截取末尾 3000，保留最近上下文（与描述上限一致）
        return text.length() > 3000 ? text.substring(text.length() - 3000) : text;
    }

    /**
     * 分类归一化：入参合法则原样（转大写）采用，否则按文本关键词推断兜底。
     *
     * <p>做什么：category 非空且属于 CATEGORIES 时返回其大写形式，否则交给 guessCategory。
     * <p>关键规则：这是「业务兜底」——用户/转人工请求未填或填了非法分类时，仍能把工单归到合理类别。
     * <p>边界：text 可为 null（guessCategory 内部已处理，返回 OTHER）。
     */
    private String normalizeCategory(String category, String text) {
        // 合法枚举值直接采用（统一转大写），不改动调用方意图
        if (StringUtils.hasText(category) && CATEGORIES.contains(category.toUpperCase())) {
            return category.toUpperCase();
        }
        // 缺失或非法分类：按标题+描述关键词推断
        return guessCategory(text);
    }

    /**
     * 优先级归一化：入参合法则原样（转大写）采用，否则按紧急关键词兜底。
     *
     * <p>做什么：priority 非空且属于 PRIORITIES 时返回大写形式；否则若文本命中
     * 「紧急/宕机/无法使用」任一关键词则升为 HIGH，其余默认 MEDIUM。
     * <p>关键规则：关键词命中即自动提级，保证故障类诉求不被当作普通工单；
     * 默认 MEDIUM 与 CreateTicketRequest 的默认值一致。
     * <p>边界：text 为 null 时不做关键词判定，直接返回 MEDIUM。
     */
    private String normalizePriority(String priority, String text) {
        // 合法枚举值直接采用（统一转大写）
        if (StringUtils.hasText(priority) && PRIORITIES.contains(priority.toUpperCase())) {
            return priority.toUpperCase();
        }
        // 兜底规则：命中「紧急/宕机/无法使用」任一词即自动升为 HIGH
        if (text != null && (text.contains("紧急") || text.contains("宕机") || text.contains("无法使用"))) {
            return "HIGH";
        }
        // 其余情况归为中等优先级
        return "MEDIUM";
    }

    /**
     * 按「标题+描述」文本关键词猜测分类（业务兜底规则）。
     *
     * <p>做什么：按优先级顺序匹配关键词组，命中即返回对应分类：
     * 账号类（邮箱/账号/密码/登录/权限）→ ACCOUNT；技术类（网络/VPN/电脑/蓝屏/打印机/系统）
     * → TECHNICAL；售后类（退款/售后/发票/订单）→ AFTER_SALES；产品类（产品/安装/使用/功能）
     * → PRODUCT；全部不命中 → OTHER。
     * <p>关键规则：顺序敏感，靠前类别优先（账号类在最前），一条文本只归一类；
     * 这是基于关键词的轻量规则、非机器学习分类，命中第一组即返回。
     * <p>边界：text 为 null 返回 OTHER。
     */
    private String guessCategory(String text) {
        if (text == null) {
            return "OTHER";
        }
        // 账号类关键词：邮箱/账号/密码/登录/权限 → ACCOUNT（优先级最高）
        if (text.contains("邮箱") || text.contains("账号") || text.contains("密码")
                || text.contains("登录") || text.contains("权限")) {
            return "ACCOUNT";
        }
        // 技术类关键词：网络/VPN/电脑/蓝屏/打印机/系统 → TECHNICAL
        if (text.contains("网络") || text.contains("VPN") || text.contains("电脑") || text.contains("蓝屏")
                || text.contains("打印机") || text.contains("系统")) {
            return "TECHNICAL";
        }
        // 售后类关键词：退款/售后/发票/订单 → AFTER_SALES
        if (text.contains("退款") || text.contains("售后") || text.contains("发票") || text.contains("订单")) {
            return "AFTER_SALES";
        }
        // 产品类关键词：产品/安装/使用/功能 → PRODUCT
        if (text.contains("产品") || text.contains("安装") || text.contains("使用") || text.contains("功能")) {
            return "PRODUCT";
        }
        // 全部未命中，兜底为 OTHER
        return "OTHER";
    }

    /**
     * 生成工单业务编号（工单号）。
     *
     * <p>做什么：格式 "T" + yyyyMMddHHmmss 时间戳 + 4 位随机数（0000-9999，%04d 补零）。
     * <p>关键规则：时间戳前缀便于按号粗略排序与人工识别，4 位随机数降低同一秒内并发生成碰撞概率；
     * 使用 ThreadLocalRandom 避免多线程竞争；该编号仅供展示，数据库主键仍用自增 id。
     * <p>边界：不保证全局绝对唯一（同秒且随机值相同理论上可能碰撞），本实现未加唯一约束，由业务容忍。
     */
    private String nextTicketNo() {
        // 时间戳到秒 + 4 位随机数，组成形如 T202611061230450123 的业务编号
        return "T" + LocalDateTime.now().format(NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    /**
     * 计算用户展示名，供 VO、事件内容与分配说明使用。
     *
     * <p>做什么：user 为 null 返回「未知用户」；有昵称优先返回昵称，否则回退用户名。
     * <p>关键规则：统一展示口径，避免各处对空昵称处理不一致；关联用户已被删除时也能安全显示。
     */
    private String displayName(User user) {
        // 空指针兜底：用户不存在（如已被删除）时给出可读占位
        if (user == null) {
            return "未知用户";
        }
        // 昵称优先、用户名兜底，保证始终有非空展示名
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }

    /**
     * 把 Ticket 实体转换为对外 VO（public，供 page / detail / 各写方法返回时复用）。
     *
     * <p>做什么：逐字段映射，并回查用户表补出 userName / assigneeName 展示名。
     * <p>关键规则：ticket 为 null 直接返回 null；未分配（assigneeId == null）时不查处理人、
     * assigneeName 置 null；version 一并带出，便于前端感知乐观锁版本。
     * <p>边界：仅做读取与组装，不改状态、不写事件。
     */
    public TicketVO toVO(Ticket ticket) {
        // 空值安全：上游查不到工单时返回 null，避免 NPE
        if (ticket == null) {
            return null;
        }
        // 回查创建人与处理人的展示名，避免前端按 ID 二次查询
        User user = userMapper.selectById(ticket.getUserId());
        User assignee = ticket.getAssigneeId() == null ? null : userMapper.selectById(ticket.getAssigneeId());
        // 逐字段构建 TicketVO；assigneeName 在未分配时为 null
        return TicketVO.builder()
                .id(ticket.getId())
                .ticketNo(ticket.getTicketNo())
                .userId(ticket.getUserId())
                .userName(displayName(user))
                .conversationId(ticket.getConversationId())
                .assigneeId(ticket.getAssigneeId())
                .assigneeName(assignee == null ? null : displayName(assignee))
                .title(ticket.getTitle())
                .description(ticket.getDescription())
                .category(ticket.getCategory())
                .priority(ticket.getPriority())
                .status(ticket.getStatus())
                .version(ticket.getVersion())
                .resolvedTime(ticket.getResolvedTime())
                .closedTime(ticket.getClosedTime())
                .createTime(ticket.getCreateTime())
                .updateTime(ticket.getUpdateTime())
                .build();
    }

    /**
     * 把 TicketEvent 实体转换为时间线条目 VO（detail 接口使用）。
     *
     * <p>做什么：逐字段映射，并按 operatorId 回查用户表补出 operatorName 与 operatorRole，
     * 使前端能展示「谁、以什么身份」做了该操作。
     * <p>关键规则：操作人不存在时 operatorName 由 displayName 兜底为「未知用户」、
     * operatorRole 置 null；事件内容与旧/新状态原样带出。
     * <p>边界：仅读取组装，不改数据。
     */
    private TicketEventVO toEventVO(TicketEvent event) {
        // 回查操作人，用于时间线展示昵称与角色
        User operator = userMapper.selectById(event.getOperatorId());
        // 逐字段构建事件 VO；操作人缺失时角色置 null，昵称走 displayName 兜底
        return TicketEventVO.builder()
                .id(event.getId())
                .ticketId(event.getTicketId())
                .operatorId(event.getOperatorId())
                .operatorName(displayName(operator))
                .operatorRole(operator == null ? null : operator.getRole())
                .eventType(event.getEventType())
                .oldStatus(event.getOldStatus())
                .newStatus(event.getNewStatus())
                .content(event.getContent())
                .createTime(event.getCreateTime())
                .build();
    }
}
