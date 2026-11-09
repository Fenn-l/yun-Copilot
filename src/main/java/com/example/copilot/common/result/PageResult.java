package com.example.copilot.common.result;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.function.Function;

/**
 * 统一分页返回结构，屏蔽 MyBatis-Plus {@code IPage} 类型，避免持久层对象泄漏到 API 层。
 *
 * <p>面试要点：Controller 对外返回 {@code PageResult<VO>} 而非 {@code IPage<Entity>}，
 * 统一了 list / total / page / size 四个字段名；配合带 mapper 的 from 重载，
 * 在分页转换时顺带把 Entity 映射成 VO，防止实体字段（如密码、内部状态）直接外泄。
 * <p>字段语义：list 为当前页数据，total 为符合条件的总条数，page 为当前页码（从 1 起），
 * size 为每页条数；total 由分页插件的 count 查询得出，与 list 的元素个数不必相等。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 当前页记录列表。 */
    private List<T> list;
    /** 满足条件的总记录数，来自分页插件的 count 查询。 */
    private long total;
    /** 当前页码，MyBatis-Plus 分页从 1 开始。 */
    private long page;
    /** 每页条数，受 MybatisPlusConfig 中 maxLimit=200 约束。 */
    private long size;

    /**
     * 原样转换：把 IPage 的 records / total / current / size 直接搬入分页结果。
     * <p>适用于 page 内已是 VO 或无需字段映射的场景；纯值拷贝，无副作用、不触发额外查询。
     */
    public static <T> PageResult<T> from(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /**
     * 带映射的转换：先用 mapper 把当前页每条 S 转成 T（Entity 到 VO 的典型入口），再装入结果。
     * <p>关键规则：只对当前页 records 做映射，不触碰 count 逻辑，故 total / page / size 原样取自 IPage；
     * {@code stream().map(mapper).toList()} 返回不可变列表，调用方不应再修改其内容。
     * <p>边界：mapper 需自行处理 null 元素；该方法是分页字段裁剪与敏感字段脱敏的统一落点。
     */
    public static <S, T> PageResult<T> from(IPage<S> page, Function<S, T> mapper) {
        return new PageResult<>(page.getRecords().stream().map(mapper).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }
}
