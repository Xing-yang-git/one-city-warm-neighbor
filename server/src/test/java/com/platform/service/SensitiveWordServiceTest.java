package com.platform.service;

import com.platform.common.BizException;
import com.platform.common.SensitiveWordStatus;
import com.platform.model.dto.SensitiveWordDTO;
import com.platform.model.entity.SensitiveWord;
import com.platform.repository.SensitiveWordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * SensitiveWordService 敏感词匹配服务单元测试。
 *
 * <p>覆盖：归一化匹配（全角转半角/大小写/繁转简/去空格标点 emoji 干扰）、拼音缩写映射
 * （仅对应敏感词启用时生效）、停用词不参与匹配、replace 掩码替换（干扰字符区间整体掩码）、
 * CRUD 后缓存刷新即时生效。</p>
 *
 * <p>缓存由 {@code @PostConstruct refreshCache()} 从仓库查询启用词加载，测试通过 Mock
 * {@link SensitiveWordRepository#findByStatusOrderByUpdatedAtDesc(SensitiveWordStatus)}
 * 模拟启动加载与增删改后的缓存刷新。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SensitiveWordService 敏感词匹配服务单元测试")
class SensitiveWordServiceTest {

    @Mock
    private SensitiveWordRepository repository;

    private SensitiveWordService service;

    @BeforeEach
    void setUp() {
        service = new SensitiveWordService(repository);
    }

    /** 构造启用词实体 */
    private SensitiveWord enabledWord(String word) {
        return SensitiveWord.builder().word(word).status(SensitiveWordStatus.ENABLED).build();
    }

    /** 加载指定启用词到缓存（模拟 @PostConstruct 启动加载） */
    private void loadEnabled(String... words) {
        List<SensitiveWord> list = Arrays.stream(words)
                .map(this::enabledWord)
                .collect(Collectors.toList());
        when(repository.findByStatusOrderByUpdatedAtDesc(SensitiveWordStatus.ENABLED)).thenReturn(list);
        service.refreshCache();
    }

    // ==================== 归一化匹配 ====================

    @Test
    @DisplayName("归一化 - 全角/大写/空格干扰的英文敏感词均命中")
    void should_match_when_fullWidthUpperInterference() {
        loadEnabled("fuck");

        assertThat(service.contains("ｆｕｃｋ")).isTrue();
        assertThat(service.contains("FUCK")).isTrue();
        assertThat(service.contains("f u c k")).isTrue();
    }

    @Test
    @DisplayName("归一化 - 繁体中文经繁转简后命中")
    void should_match_when_traditionalChinese() {
        loadEnabled("脏话");

        assertThat(service.contains("髒話")).isTrue();
    }

    @Test
    @DisplayName("归一化 - 中文敏感词带空格干扰仍命中")
    void should_match_when_chineseInterference() {
        loadEnabled("傻逼");

        assertThat(service.contains("傻 逼")).isTrue();
    }

    @Test
    @DisplayName("匹配 - 空文本或 null 恒返回 false")
    void should_returnFalse_when_blankOrNull() {
        assertThat(service.contains(null)).isFalse();
        assertThat(service.contains("")).isFalse();
        assertThat(service.contains("   ")).isFalse();
    }

    // ==================== 缩写映射 ====================

    @Test
    @DisplayName("缩写 - 对应词启用时拼音/变体缩写命中")
    void should_matchAbbreviations_when_wordEnabled() {
        loadEnabled("傻逼", "妈的", "你妈的", "操你妈", "我去你妈的", "草", "fuck");

        assertThat(service.contains("sb")).isTrue();
        assertThat(service.contains("md")).isTrue();
        assertThat(service.contains("nmd")).isTrue();
        assertThat(service.contains("cnm")).isTrue();
        assertThat(service.contains("wqnmlgb")).isTrue();
        assertThat(service.contains("草泥马")).isTrue();
        assertThat(service.contains("艹")).isTrue();
        assertThat(service.contains("fck")).isTrue();
        assertThat(service.contains("fk")).isTrue();
    }

    @Test
    @DisplayName("缩写 - 词库未收录对应词时缩写不生效")
    void should_notMatchAbbreviation_when_wordAbsent() {
        // 仅启用「傻逼」，「妈的」「fuck」未收录 → md/fck 缩写不激活
        loadEnabled("傻逼");

        assertThat(service.contains("sb")).isTrue();
        assertThat(service.contains("md")).isFalse();
        assertThat(service.contains("fck")).isFalse();
        assertThat(service.contains("fk")).isFalse();
    }

    @Test
    @DisplayName("缩写 - 长单词中的缩写子串不误拦截（usb 里的 sb、cmd 里的 md）")
    void should_notMatchAbbreviation_when_partOfWord() {
        loadEnabled("傻逼", "妈的");

        assertThat(service.contains("usb")).isFalse();
        // 用户误报原样复现：usb 含 sb 子串，但非独立脏话缩写
        assertThat(service.contains("物品借出，雷蛇鼠标，正常使用的痕迹，usb＋蓝牙的连接方式，有三个侧键")).isFalse();
        assertThat(service.contains("cmd")).isFalse();
    }

    @Test
    @DisplayName("缩写 - 独立成词的缩写仍命中（真sb / 你md / s b）")
    void should_matchAbbreviation_when_standalone() {
        loadEnabled("傻逼", "妈的");

        assertThat(service.contains("真sb")).isTrue();
        assertThat(service.contains("你md")).isTrue();
        assertThat(service.contains("s b")).isTrue();
    }

    // ==================== 停用词不生效 ====================

    @Test
    @DisplayName("停用 - 词改为 DISABLED 后不再参与匹配（缓存刷新即时生效）")
    void should_stopMatching_when_wordDisabled() {
        SensitiveWord word = SensitiveWord.builder().id(1L).word("傻逼").status(SensitiveWordStatus.ENABLED).build();
        when(repository.findByStatusOrderByUpdatedAtDesc(SensitiveWordStatus.ENABLED))
                .thenReturn(List.of(word), List.of());
        service.refreshCache();
        assertThat(service.contains("sb")).isTrue();

        // 改为停用：更新实体状态并刷新缓存（启用词查询不再返回该词）
        SensitiveWord disabled = SensitiveWord.builder().id(1L).word("傻逼").status(SensitiveWordStatus.DISABLED).build();
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(disabled));
        when(repository.save(any(SensitiveWord.class))).thenAnswer(inv -> inv.getArgument(0));
        service.update(1L, SensitiveWordDTO.builder().word("傻逼").status(SensitiveWordStatus.DISABLED).build());

        assertThat(service.contains("sb")).isFalse();
    }

    // ==================== replace 掩码替换 ====================

    @Test
    @DisplayName("替换 - 命中词整体替换为 ***")
    void should_replace_when_hit() {
        loadEnabled("傻逼");

        assertThat(service.replace("你傻逼吗")).isEqualTo("你***吗");
    }

    @Test
    @DisplayName("替换 - 词间干扰字符随区间整体掩码")
    void should_replaceWholeSpan_when_interference() {
        loadEnabled("傻逼", "fuck");

        assertThat(service.replace("你傻 逼吗")).isEqualTo("你***吗");
        assertThat(service.replace("真f u c k了")).isEqualTo("真***了");
    }

    @Test
    @DisplayName("替换 - 缩写同样被掩码")
    void should_replaceAbbreviation_when_hit() {
        loadEnabled("傻逼");

        assertThat(service.replace("sb你个sb")).isEqualTo("***你个***");
    }

    @Test
    @DisplayName("替换 - 长单词中的缩写子串不掩码，独立缩写仍掩码")
    void should_replace_onlyStandaloneAbbreviation() {
        loadEnabled("傻逼");

        // usb 含 sb 子串但非独立成词 → 原样保留，不掩码
        assertThat(service.replace("usb连接口")).isEqualTo("usb连接口");
        assertThat(service.replace("sb你个sb")).isEqualTo("***你个***");
    }

    @Test
    @DisplayName("替换 - 无命中时原样返回")
    void should_returnOriginal_when_noHit() {
        loadEnabled("傻逼");

        assertThat(service.replace("今天天气不错")).isEqualTo("今天天气不错");
        assertThat(service.replace(null)).isNull();
    }

    // ==================== CRUD 与缓存刷新 ====================

    @Test
    @DisplayName("缓存 - 新增词后缓存刷新，contains 立即生效")
    void should_refreshCache_when_create() {
        List<SensitiveWord> enabledWords = new ArrayList<>(List.of(enabledWord("傻逼")));
        when(repository.findByStatusOrderByUpdatedAtDesc(SensitiveWordStatus.ENABLED))
                .thenAnswer(inv -> new ArrayList<>(enabledWords));
        service.refreshCache();
        assertThat(service.contains("fuck")).isFalse();

        // 新增词并入启用词列表，create 内部触发 refreshCache
        enabledWords.add(enabledWord("fuck"));
        when(repository.existsByWord("fuck")).thenReturn(false);
        when(repository.save(any(SensitiveWord.class))).thenAnswer(inv -> {
            SensitiveWord w = inv.getArgument(0);
            w.setId(2L);
            return w;
        });
        service.create(SensitiveWordDTO.builder().word("fuck").build());

        assertThat(service.contains("fuck")).isTrue();
    }

    @Test
    @DisplayName("删除 - 删除后缓存刷新，词不再命中")
    void should_refreshCache_when_delete() {
        SensitiveWord word = SensitiveWord.builder().id(1L).word("傻逼").status(SensitiveWordStatus.ENABLED).build();
        when(repository.findByStatusOrderByUpdatedAtDesc(SensitiveWordStatus.ENABLED))
                .thenReturn(List.of(word), List.of());
        service.refreshCache();
        assertThat(service.contains("sb")).isTrue();

        when(repository.findById(1L)).thenReturn(java.util.Optional.of(word));
        service.delete(1L);

        assertThat(service.contains("sb")).isFalse();
    }

    @Test
    @DisplayName("新增 - 词已存在抛业务异常")
    void should_throw_when_createDuplicate() {
        when(repository.existsByWord("傻逼")).thenReturn(true);

        assertThatThrownBy(() -> service.create(SensitiveWordDTO.builder().word("傻逼").build()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("敏感词已存在");
    }

    @Test
    @DisplayName("新增 - 空词抛业务异常")
    void should_throw_when_wordBlank() {
        assertThatThrownBy(() -> service.create(SensitiveWordDTO.builder().word("  ").build()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("敏感词不能为空");
    }

    @Test
    @DisplayName("列表 - 按 ENABLED 状态过滤返回全部启用词")
    void should_list_when_statusFiltered() {
        when(repository.findByStatusOrderByUpdatedAtDesc(SensitiveWordStatus.ENABLED)).thenReturn(List.of(enabledWord("傻逼")));

        List<SensitiveWordDTO> result = service.list("ENABLED");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getWord()).isEqualTo("傻逼");
    }

    @Test
    @DisplayName("列表 - 未知状态过滤抛业务异常")
    void should_throw_when_unknownStatus() {
        assertThatThrownBy(() -> service.list("UNKNOWN"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未知状态");
    }
}
