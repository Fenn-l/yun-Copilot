package com.example.copilot.knowledge.storage;

import com.example.copilot.common.config.AppProperties;
import com.example.copilot.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 基于本地磁盘的文件存储实现，是 FileStorage 的默认落地方案。
 *
 * <p>在 RAG 链路中的位置：上传文件按知识库分目录落盘，返回的相对路径写入
 * kb_document.file_url；异步解析与删除均通过相对路径回到本类定位真实文件。
 *
 * <p>关键设计与边界：
 * <ul>
 *   <li>按知识库分目录：目录名 kb-{knowledgeBaseId}，便于隔离、统计与整体清理。</li>
 *   <li>随机文件名：UUID 去横线 + 原扩展名，既避免同名覆盖、防止被猜测下载，
 *       又不保留客户端原始名，配合 StringUtils.cleanPath 阻断 ../ 路径穿越。</li>
 *   <li>路径安全：所有读/删都先经 resolve 归一化，并用 startsWith(baseDir) 判定
 *       结果仍在根目录内，越界直接拒绝，杜绝绝对路径或 ../ 逃逸。</li>
 *   <li>读写删除边界：read 一次性读全部字节（受 50MB 上传上限约束）；
 *       delete 用 deleteIfExists 且失败仅告警，属于尽力而为清理，不阻塞主流程。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalFileStorage implements FileStorage {

    /** 应用配置，提供存储根目录 localDir；由 @RequiredArgsConstructor 构造注入。 */
    private final AppProperties properties;

    /** 归一化后的绝对路径存储根目录，init 阶段初始化，所有相对路径都相对它解析。 */
    private Path baseDir;

    /**
     * 初始化存储根目录（Spring Bean 创建后由 @PostConstruct 触发）。
     * <p>规则：把配置的 localDir 转为绝对路径并 normalize 去掉冗余的 . / ..，
     * 再递归创建目录；toAbsolutePath().normalize() 是后续 startsWith 安全判定的前提。
     * @throws IOException 目录创建失败时抛出，交由容器处理，避免带着无效根目录启动
     */
    @PostConstruct
    public void init() throws IOException {
        baseDir = Paths.get(properties.getStorage().getLocalDir()).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);
        log.info("本地文件存储目录: {}", baseDir);
    }

    /**
     * 保存上传的 MultipartFile，委托 storeBytes 完成落盘。
     * <p>规则：用 StringUtils.cleanPath 清洗原始文件名（仅用于取扩展名），
     * 原文件名为 null 时用 "document" 兜底；读取字节的 IOException 统一转业务异常。
     * 边界：绝不把客户端文件名直接用作落盘路径，防路径穿越。
     */
    @Override
    public String store(MultipartFile file, Long knowledgeBaseId) {
        String original = StringUtils.cleanPath(file.getOriginalFilename() == null
                ? "document" : file.getOriginalFilename());
        try {
            return storeBytes(file.getBytes(), original, knowledgeBaseId);
        } catch (IOException e) {
            throw new BusinessException("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 以随机文件名把字节写入「按知识库分目录」的位置，返回相对路径。
     * <p>规则：UUID 去横线 + extension() 提取的小写扩展名拼成落盘名，
     * 目录为 baseDir/kb-{knowledgeBaseId} 并按需创建；返回 "kb-{id}/{随机名}"
     * 供 read/delete 回传。边界：同名不会覆盖，原始文件名不参与路径，IOException 转业务异常。
     */
    @Override
    public String storeBytes(byte[] bytes, String fileName, Long knowledgeBaseId) {
        try {
            String ext = extension(fileName);
            // 随机文件名，避免同名覆盖与被猜测；仅保留扩展名供解析器路由。
            String storedName = UUID.randomUUID().toString().replace("-", "") + ext;
            // 按知识库分目录，隔离不同知识库的文件并便于整体清理。
            Path dir = baseDir.resolve("kb-" + knowledgeBaseId);
            Files.createDirectories(dir);
            // 写入字节；storedName 为纯随机名，不含路径分隔符，不会逃逸出 dir。
            Files.write(dir.resolve(storedName), bytes);
            return "kb-" + knowledgeBaseId + "/" + storedName;
        } catch (IOException e) {
            throw new BusinessException("文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 读取相对路径对应文件的全部字节，供解析器使用。
     * <p>边界：先 resolve 校验未逃逸根目录；不存在抛 notFound；整体读入内存，
     * 因此上传侧有 50MB 上限；IOException 统一转业务异常。
     */
    @Override
    public byte[] read(String storedPath) {
        try {
            // 先归一化并校验仍在根目录内，拦截 ../ 路径穿越。
            Path file = resolve(storedPath);
            if (!Files.exists(file)) {
                throw BusinessException.notFound("文件不存在: " + storedPath);
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BusinessException("文件读取失败: " + e.getMessage());
        }
    }

    /**
     * 删除相对路径对应文件。
     * <p>边界：同样先 resolve 校验路径；deleteIfExists 使「已删除」幂等不报错；
     * 删除失败仅记录告警不抛出，避免残留文件阻塞文档删除主流程。
     */
    @Override
    public void delete(String storedPath) {
        try {
            Files.deleteIfExists(resolve(storedPath));
        } catch (IOException e) {
            log.warn("删除文件失败 path={} msg={}", storedPath, e.getMessage());
        }
    }

    /**
     * 把调用方传入的相对路径解析为根目录内的绝对路径，并做路径穿越校验。
     * <p>规则：baseDir.resolve(storedPath).normalize() 消除 . / .. 后，
     * 用 startsWith(baseDir) 判定结果仍位于根目录内；越界（绝对路径或 ../ 逃逸）
     * 直接抛 badRequest。这是读、删共用的唯一安全入口。
     * @param storedPath 相对存储根的路径
     * @return 归一化后且确认位于根目录内的绝对路径
     * @throws BusinessException 路径非法时抛业务异常
     */
    private Path resolve(String storedPath) {
        Path path = baseDir.resolve(storedPath).normalize();
        // 归一化后仍须落在根目录内，否则视为路径穿越攻击。
        if (!path.startsWith(baseDir)) {
            throw BusinessException.badRequest("非法文件路径");
        }
        return path;
    }

    /**
     * 从文件名提取小写扩展名（含点号），无扩展名时返回空串。
     * <p>边界：只取最后一个 '.' 之后的内容并转小写，避免大小写影响解析器路由；
     * 返回值仅用于拼随机文件名，不含路径，不参与安全判定。
     */
    private String extension(String fileName) {
        // 取最后一个点号，兼容 a.b.pdf 这类多段文件名。
        int idx = fileName.lastIndexOf('.');
        return idx >= 0 ? fileName.substring(idx).toLowerCase() : "";
    }
}
