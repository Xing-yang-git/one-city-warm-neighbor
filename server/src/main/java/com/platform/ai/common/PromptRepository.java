package com.platform.ai.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词仓库 — 启动时把 {@code resources/prompts/} 下的提示词文件全部加载进内存。
 *
 * <p>小邻各模块（对话 System Prompt、记忆压缩、拦截文案等）统一从这里读取提示词，
 * 改文案只动资源文件、不改代码；加载放在构造阶段而非首次访问，是为了 fail-fast：
 * 任一提示词文件缺失或编码异常都在启动时立刻暴露，而不是运行到该业务时才炸。</p>
 *
 * <p>加载完成后运行时 {@link #get(String)} 只读内存 Map，无任何 IO。</p>
 */
@Component
public class PromptRepository {

    private static final Logger log = LoggerFactory.getLogger(PromptRepository.class);

    /** 提示词资源根目录（classpath 相对路径） */
    private static final String PROMPT_ROOT = "prompts/";

    /** 对话 System Prompt（动态替换 {小区名}/{历史记忆}/{平台功能说明} 占位符） */
    public static final String KEY_AGENT_SYSTEM = "agent.system";

    /** 工具清单与使用说明（properties 文案集，key = 工具名） */
    public static final String KEY_AGENT_TOOLS = "agent.tools";

    /** 固定应答文案集（问候语、工具兜底回复等，properties 格式） */
    public static final String KEY_AGENT_REPLIES = "agent.replies";

    /** 提示词注入特征提示语（properties 格式，不拦截仅附加进模型输入） */
    public static final String KEY_AGENT_INJECTION = "agent.injection";

    /** 记忆压缩提示词（含 {@code {messages}} 占位符，把会话窗口压成标题 + 摘要） */
    public static final String KEY_MEMORY_COMPRESS = "memory.compress";

    /** 前置拦截文案集（空消息/重复/超长/敏感词等本地应答，properties 格式） */
    public static final String KEY_BLOCK_REPLIES = "block.replies";

    /**
     * 提示词 key → 资源文件相对路径（key = 子目录/文件名去掉扩展名，点号连接，如 {@code memory.compress}）。
     *
     * <p>集中登记避免散落硬编码：新增提示词文件只需在此加一行；key 用上方 {@code KEY_*}
     * 常量引用，调用方也引用同一常量，拼写错误在编译期即暴露。</p>
     */
    private static final Map<String, String> PROMPT_FILE_PATHS = Map.of(
            KEY_AGENT_SYSTEM, "agent/system.md",
            KEY_AGENT_TOOLS, "agent/tools.md",
            KEY_AGENT_REPLIES, "agent/replies.md",
            KEY_AGENT_INJECTION, "agent/injection.md",
            KEY_MEMORY_COMPRESS, "memory/compress.md",
            KEY_BLOCK_REPLIES, "block/replies.md"
    );

    /** 内存中的提示词模板，key 与 {@link #PROMPT_FILE_PATHS} 一致；final + 构造期填充，保证只读语义 */
    private final Map<String, String> prompts = new ConcurrentHashMap<>();

    /**
     * 构造期加载全部提示词文件。
     *
     * <p>任一文件缺失或读取失败直接抛异常，Spring 启动即失败，杜绝运行期缺提示词。</p>
     */
    public PromptRepository() {
        for (Map.Entry<String, String> entry : PROMPT_FILE_PATHS.entrySet()) {
            prompts.put(entry.getKey(), loadPrompt(entry.getValue()));
        }
        log.info("提示词仓库加载完成，共 {} 个提示词文件", prompts.size());
    }

    /**
     * 读取单个提示词文件全文（UTF-8 解码）。
     *
     * @param relativePath 相对 {@code prompts/} 根目录的路径（如 {@code memory/compress.md}）
     * @return 文件原始文本
     * @throws IllegalStateException 文件缺失或读取失败时抛出（fail-fast，携带可定位的文件路径）
     */
    private String loadPrompt(String relativePath) {
        String fullPath = PROMPT_ROOT + relativePath;
        ClassPathResource resource = new ClassPathResource(fullPath);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词文件缺失: " + fullPath);
        }
        StringBuilder content = new StringBuilder();
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                content.append(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException("提示词文件读取失败: " + fullPath, e);
        }
        return content.toString();
    }

    /**
     * 获取提示词模板文本（首尾空白已去除）。
     *
     * @param key 提示词 key（如 {@code agent.system}、{@code memory.compress}）
     * @return 模板文本，调用方按需用占位符替换（如 {@code {小区名}}）
     * @throws IllegalStateException key 不存在（含调用方拼写错误）时抛出，便于尽早发现引用失误
     */
    public String get(String key) {
        String content = prompts.get(key);
        if (content == null) {
            throw new IllegalStateException("提示词不存在: " + key + "，请检查 PromptRepository 登记的 key");
        }
        return content.trim();
    }

    /**
     * 把提示词内容按 properties 格式（每行 {@code key=value}）解析返回，用于拦截本地应答等文案集。
     *
     * <p>注意：不能直接用 {@code Properties.load(InputStream)} —— 它按 ISO-8859-1 解码，
     * 中文文案会全部乱码；这里从内存文本经 {@link StringReader} 解析，文本在启动加载时
     * 已按 UTF-8 解码，中文原样保留。</p>
     *
     * @param key 提示词 key（如 {@code block.replies}）
     * @return 解析后的键值对；文件内 {@code #} 开头的注释行自动被忽略
     * @throws IllegalStateException key 不存在或内容无法解析为 properties 时抛出
     */
    public Properties getProps(String key) {
        String content = get(key);
        Properties props = new Properties();
        try (Reader reader = new StringReader(content)) {
            props.load(reader);
        } catch (IOException e) {
            // StringReader 不会抛 IO 异常，此处兜底理论上不可达，保留以符合 properties.load 的签名
            throw new IllegalStateException("提示词解析为 properties 失败: " + key, e);
        }
        return props;
    }
}
