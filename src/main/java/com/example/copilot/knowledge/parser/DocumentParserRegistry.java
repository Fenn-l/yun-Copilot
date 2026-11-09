package com.example.copilot.knowledge.parser;

import com.example.copilot.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档解析器注册中心，负责「扩展名归一化」与「按扩展名路由到具体解析器」。
 *
 * <p>在 RAG 链路中的位置：异步解析任务拿到文件名与字节后，先 normalizeExtension
 * 得到小写扩展名，再由 parse 从 Spring 注入的全部 DocumentParser Bean 中选出
 * supports 命中的实现执行解析，是解析层的统一入口。
 *
 * <p>关键设计：所有实现（PDF / Word / 纯文本）以 List 形式由构造器注入，
 * 路由靠遍历 supports 而非硬编码分支，新增格式只需加一个 Bean（开闭原则）；
 * 扩展名统一小写、不含点号，避免大小写与 .PDF 之类导致路由失败。
 */
@Component
public class DocumentParserRegistry {

    /** Spring 容器注入的全部 DocumentParser 实现（PDFBox / POI / 纯文本），路由时按顺序匹配。 */
    private final List<DocumentParser> parsers;

    /**
     * 构造注册中心。
     * <p>规则：由 Spring 按类型收集所有 DocumentParser Bean 注入 List；
     * 单构造器隐式注入，无需额外注解。边界：列表为空时所有路由都失败并报「不支持类型」。
     * @param parsers 容器中的全部解析器实现
     */
    public DocumentParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 从文件名提取小写扩展名（不含点号）。
     * <p>规则：取最后一个 '.' 之后的内容并转小写；fileName 为 null、无点号、
     * 点号在末尾（如 "readme."）均视为无法识别并抛 badRequest，
     * 从入口拦截非法文件，避免后续按空扩展名路由。
     * @throws BusinessException 文件名为空或缺少有效扩展名
     */
    public String normalizeExtension(String fileName) {
        if (fileName == null) {
            throw BusinessException.badRequest("文件名不能为空");
        }
        // 取最后一个点号，兼容 a.b.pdf 这类多段文件名。
        int idx = fileName.lastIndexOf('.');
        // 无扩展名或以点号结尾都判为非法，直接拒绝。
        if (idx < 0 || idx == fileName.length() - 1) {
            throw BusinessException.badRequest("无法识别文件类型，请上传 PDF / Word / TXT / Markdown 文件");
        }
        // 统一小写，使后续 supports 的精确匹配不受大小写影响。
        return fileName.substring(idx + 1).toLowerCase();
    }

    /**
     * 判断是否存在支持该扩展名的解析器。
     * <p>规则：遍历所有实现，任一 supports 命中即返回 true；供上传阶段提前校验，
     * 避免不支持的格式进入异步解析队列后再失败。
     */
    public boolean isSupported(String extension) {
        return parsers.stream().anyMatch(parser -> parser.supports(extension));
    }

    /**
     * 按扩展名路由到具体解析器并执行解析。
     * <p>规则：stream 过滤 supports、findFirst 取第一个命中的实现；
     * 无命中时 orElseThrow 抛 badRequest「暂不支持的文件类型」。
     * 边界：要求 extension 已归一化，否则可能匹配不到；解析异常向上抛出，
     * 由异步任务置文档 FAILED 并写 errorMessage。
     */
    public ParsedDocument parse(byte[] data, String extension) throws Exception {
        // 按扩展名路由：遍历全部实现，取第一个声明支持的解析器。
        DocumentParser parser = parsers.stream()
                // 过滤条件即各实现的 supports，新增格式无需改动此处。
                .filter(p -> p.supports(extension))
                // 取首个命中；实现间扩展名互不重叠，顺序不影响结果。
                .findFirst()
                // 无解析器支持则拒绝，避免未知格式进入后续切片流程。
                .orElseThrow(() -> BusinessException.badRequest("暂不支持的文件类型: " + extension));
        return parser.parse(data, extension);
    }
}
