package com.example.copilot.knowledge.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件存储抽象，定义上传文件在本地的写入、读取与删除边界。
 *
 * <p>在 RAG 链路中的位置：上传 PDF/DOC/DOCX/TXT/MD（≤50MB）后先经本接口落盘，
 * 再把返回的相对路径写入 kb_document.file_url；异步解析任务用 read 取回字节流交给
 * DocumentParser，文档删除时用 delete 清理。存储与业务记录解耦，便于日后替换为对象存储。
 *
 * <p>约定：store/storeBytes 返回「相对存储根」的路径（如 kb-1/xxx.pdf），
 * read/delete 接收的正是该相对路径；实现方必须自行做路径穿越校验，
 * 禁止调用方传入绝对路径或 ../ 逃逸到存储根目录之外。
 */
public interface FileStorage {

    /**
     * 保存上传的 MultipartFile。
     * <p>规则：清洗原始文件名后委托 storeBytes，原文件名为空时用默认名兜底；
     * 读取字节失败（IOException）由实现方转为业务异常。边界：不信任客户端文件名，
     * 原始名仅用于取扩展名，不直接作为落盘名。
     * @param file 上传文件
     * @param knowledgeBaseId 所属知识库 ID，决定分目录
     * @return 相对存储根的文件路径，写入 kb_document.file_url
     */
    String store(MultipartFile file, Long knowledgeBaseId);

    /**
     * 保存原始字节，使用随机文件名避免覆盖与猜测。
     * <p>规则：按 knowledgeBaseId 分目录，文件名用 UUID 去除横线 + 原扩展名，
     * 不保留客户端原始名以防路径穿越与同名覆盖；返回相对路径供后续 read/delete 使用。
     * @param bytes 文件字节内容
     * @param fileName 仅用于提取扩展名（统一小写）
     * @param knowledgeBaseId 所属知识库 ID
     * @return 相对存储根的文件路径
     */
    String storeBytes(byte[] bytes, String fileName, Long knowledgeBaseId);

    /**
     * 按相对路径读回文件全部字节，供解析器使用。
     * <p>边界：必须先经路径安全校验确认未逃逸根目录；文件不存在时抛业务异常 notFound；
     * 整体一次性读入内存，故上传侧有 50MB 上限约束。
     * @param storedPath 相对存储根的路径
     * @return 文件全部字节
     */
    byte[] read(String storedPath);

    /**
     * 按相对路径删除已落盘文件。
     * <p>边界：同样先做路径安全校验；文件已不存在时幂等忽略，删除失败只记录告警不抛异常，
     * 避免残留文件阻塞文档删除主流程（删除属于尽力而为的清理）。
     * @param storedPath 相对存储根的路径
     */
    void delete(String storedPath);
}
