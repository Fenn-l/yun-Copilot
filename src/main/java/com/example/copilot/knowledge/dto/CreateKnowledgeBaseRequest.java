package com.example.copilot.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建知识库接口的请求体 DTO，使用 JSR-303 注解做入参校验，对应写入 kb_base 表。
 *
 * <p>边界与约束：校验失败由 Spring 统一返回参数错误；ownerId 不出现在请求体中，
 * 而是由登录态在 Service 层注入，防止调用方伪造创建人越权创建 PRIVATE 库；
 * visibility 默认值取 PRIVATE 体现「最小权限」原则，其合法枚举值
 * （PUBLIC / INTERNAL / PRIVATE）由 Service 层结合角色做二次校验。
 */
@Data
public class CreateKnowledgeBaseRequest {

    /** 知识库名称，必填：@NotBlank 拒绝空白串，@Size 限制最长 128 字符。 */
    @NotBlank(message = "知识库名称不能为空")
    @Size(max = 128, message = "名称过长")
    private String name;

    /** 知识库描述，可选，@Size 限制最长 500 字符。 */
    @Size(max = 500, message = "描述过长")
    private String description;

    /** 可见范围，默认 PRIVATE（创建人与管理员可见）；可选 PUBLIC / INTERNAL / PRIVATE。 */
    private String visibility = "PRIVATE";
}
