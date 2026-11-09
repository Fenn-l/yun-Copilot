package com.example.copilot.ticket.state;

import com.example.copilot.common.exception.BusinessException;

import java.util.Map;
import java.util.Set;

/**
 * 工单状态机：所有状态流转必须经过校验，禁止前端直接改状态。
 *
 * <p>本类定义工单六态及其合法转移表 ALLOWED，是 ticket 包业务规则与并发控制的基石。
 * 之所以禁止前端直接改状态：状态牵涉处理人、解决/关闭时间、事件流水与权限边界，若允许前端
 * 随意提交 status，即可绕过「只有客服能处理」「只处理分配给自己的单」等校验，也会造成状态与
 * 时间线不一致。因此 Controller 不接收 status，Service 的所有流转都先经 assertTransition 校验，
 * 再走 TicketMapper#updateState 的乐观锁 CAS 落库。
 *
 * <p>设计为 final 类 + 私有构造的纯静态工具，不可被继承或实例化，状态常量与转移表全局唯一。
 *
 * <p>六态主链路：NEW → ASSIGNED → IN_PROGRESS → {WAITING_USER, RESOLVED} → CLOSED；
 * RESOLVED / CLOSED 均可重开（reopen）回到 IN_PROGRESS，形成售后闭环。
 * canTransit 对 from == to 返回 true 是刻意的幂等设计：普通回复、属性调整并不改变状态，
 * 若视为非法会误伤这些合法操作。
 */
public final class TicketStateMachine {

    /** 新建：工单刚创建（或由 AI 会话转人工生成），尚未分配给任何客服。 */
    public static final String NEW = "NEW";
    /** 已分配：已指定处理人，等待其开始处理；客服回复 NEW 且未分配的单也会先进入此态。 */
    public static final String ASSIGNED = "ASSIGNED";
    /** 处理中：客服正在处理，可转为等待用户或直接解决；也是 RESOLVED/CLOSED 重开后的落点。 */
    public static final String IN_PROGRESS = "IN_PROGRESS";
    /** 待用户回复：客服已追问、等待用户补充信息；再次回复会回到 IN_PROGRESS。 */
    public static final String WAITING_USER = "WAITING_USER";
    /** 已解决：客服给出解决方案，等待创建人确认关闭；不认可可重开。 */
    public static final String RESOLVED = "RESOLVED";
    /** 已关闭：创建人（或处理人）确认结束，为终态之一；仍可重开回到 IN_PROGRESS。 */
    public static final String CLOSED = "CLOSED";

    /** 全部合法状态集合，供展示与参考；不可变 Set.of 保证内容运行期不被篡改。 */
    public static final Set<String> ALL_STATUS = Set.of(NEW, ASSIGNED, IN_PROGRESS,
            WAITING_USER, RESOLVED, CLOSED);

    /**
     * 合法状态转移表：key 为原状态，value 为该状态下允许直接到达的目标状态集合。
     * Service 每次 assign / start / transition 前都会经 assertTransition 查表，表外流转一律抛 409。
     * 逐条含义：
     * <ul>
     *   <li>NEW → ASSIGNED：新建工单只能先被分配（或客服接单），不能越级处理或关闭。</li>
     *   <li>ASSIGNED → IN_PROGRESS：已分配的工单由处理人开始处理。</li>
     *   <li>IN_PROGRESS → WAITING_USER / RESOLVED：处理中可等待用户补充，或直接给出解决方案。</li>
     *   <li>WAITING_USER → IN_PROGRESS / RESOLVED：用户回复后恢复处理，或客服直接解决。</li>
     *   <li>RESOLVED → CLOSED / IN_PROGRESS：创建人确认关闭，或不认可方案重开回处理中。</li>
     *   <li>CLOSED → IN_PROGRESS：已关闭工单可被重开，形成售后闭环。</li>
     * </ul>
     * 使用不可变 Map.of / Set.of 定义，运行期不可修改，避免状态规则被污染。
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            // NEW 只能被分配/接单，不能越级处理或关闭
            NEW, Set.of(ASSIGNED),
            // ASSIGNED 只能开始处理
            ASSIGNED, Set.of(IN_PROGRESS),
            // IN_PROGRESS 可等待用户补充或直接解决
            IN_PROGRESS, Set.of(WAITING_USER, RESOLVED),
            // WAITING_USER 可恢复处理或直接解决
            WAITING_USER, Set.of(IN_PROGRESS, RESOLVED),
            // RESOLVED 可确认关闭，或重开回到处理中
            RESOLVED, Set.of(CLOSED, IN_PROGRESS),
            // CLOSED 可重开回到处理中
            CLOSED, Set.of(IN_PROGRESS)
    );

    /** 私有构造：本类为纯静态工具类，禁止实例化。 */
    private TicketStateMachine() {
    }

    /**
     * 判断 from → to 是否为合法转移。
     * 边界：任一状态为 null 直接判非法；from 与 to 相同视为合法（幂等，放行回复/属性调整等
     * 不改状态的操作）；其余查 ALLOWED 表，原状态无条目或目标不在集合内均返回 false。
     */
    public static boolean canTransit(String from, String to) {
        // 状态缺失视为非法流转，避免 null 被误判为合法
        if (from == null || to == null) {
            return false;
        }
        // 同状态视为合法：普通回复、属性调整不改变状态，需幂等放行
        if (from.equals(to)) {
            return true;
        }
        // 查转移表：未登记的原状态（如脏数据）一律拒绝
        Set<String> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }

    /**
     * 断言转移合法，不合法则抛 409 冲突（「非法状态流转: from -> to」）。
     * TicketService 所有状态变更在落库前先调用本方法，是「禁止前端直接改状态」在服务端的第一道闸门；
     * 与 updateState 的乐观锁 CAS 共同保证状态正确、可追溯。
     */
    public static void assertTransition(String from, String to) {
        // 校验失败立即中断，错误信息带上原状态与目标状态，便于定位越权流转
        if (!canTransit(from, to)) {
            throw BusinessException.conflict("非法状态流转: " + from + " -> " + to);
        }
    }
}
