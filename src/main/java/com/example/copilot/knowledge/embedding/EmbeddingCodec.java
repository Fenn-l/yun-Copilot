package com.example.copilot.knowledge.embedding;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Embedding 向量编解码器，负责 float[] 与数据库 BLOB（byte[]）之间的双向转换。
 *
 * <p>在 RAG 链路中的位置：分批调用 Embedding 模型得到 float[] 向量后，用 toBytes 序列化
 * 写入 kb_chunk.embedding；检索时用 fromBytes 还原为 float[] 再计算余弦相似度。
 *
 * <p>关键设计：
 * <ul>
 *   <li>float32：Embedding 模型输出为 32 位浮点，精度足够且每维固定 4 字节
 *       （Float.BYTES），便于按「维度 * 4」推算 BLOB 长度、做维度校验，避免 double 的空间浪费。</li>
 *   <li>字节序：两端统一显式指定 LITTLE_ENDIAN（小端，低字节在前），不依赖平台默认
 *       （ByteBuffer 默认 BIG_ENDIAN），保证跨机器写入与读取的向量字节完全一致；
 *       一旦序列化与反序列化字节序不一致，还原出的浮点数值将被彻底破坏。</li>
 *   <li>对称性：toBytes 按元素逐个 putFloat，fromBytes 按相同字节序逐个 getFloat，
 *       长度换算均为 字节数 / Float.BYTES，保证 float[] 到 byte[] 再到 float[] 可逆。</li>
 * </ul>
 */
@Component
public class EmbeddingCodec {

    /**
     * 将 float32 向量序列化为 BLOB 字节数组。
     * <p>规则：按小端逐维写入，缓冲区大小 = vector.length * Float.BYTES；
     * 入参为 null 时返回 null（与 fromBytes 的兜底对称），不抛异常。
     * @param vector 模型输出的 float32 向量，其长度即 embedding_dim
     * @return 长度为 维度 * 4 的字节数组，或 null
     */
    public byte[] toBytes(float[] vector) {
        if (vector == null) {
            return null;
        }
        // 显式小端：与 fromBytes 保持一致，避免平台默认大端把数值解释错误。
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            // 每维固定写 4 字节（float32），顺序与数组下标一致。
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * 将 BLOB 字节数组反序列化回 float32 向量。
     * <p>规则：按与 toBytes 相同的小端字节序逐维读取，维度 = bytes.length / Float.BYTES；
     * 入参为 null 或空数组时返回长度为 0 的向量。要求字节数为 4 的整数倍（由写入端保证），
     * 否则末尾不足一维的字节被忽略；检索前应结合 embedding_dim 校验维度，防止不同模型向量混用。
     * @param bytes kb_chunk.embedding 中的 BLOB 字节
     * @return 还原出的 float32 向量
     */
    public float[] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new float[0];
        }
        // 反序列化必须与序列化使用同一字节序，保证 float[] 到 byte[] 再到 float[] 可逆。
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        // 维度 = 字节数 / 4，与 toBytes 的换算规则对称。
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            // 连续读取 4 字节还原为一个 float，读取位置随 getFloat 自动推进。
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
