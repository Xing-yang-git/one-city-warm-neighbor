package com.platform.repository;

import com.platform.model.entity.AgentMemorySegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 会话长期记忆压缩段数据访问层 — 记忆检索（原生 SQL 向量检索，强制带 user_id）、补压、联动删除。
 *
 * <p><b>越权防护铁律</b>：所有按用户维度查询的方法签名强制携带 userId，检索 SQL 强制
 * {@code user_id = :userId}；联动删除前调用方必须先确认会话归属该用户（见 ArchiveService.softDelete）。</p>
 */
public interface AgentMemorySegmentRepository extends JpaRepository<AgentMemorySegment, Long> {

    /**
     * 记忆检索 — 按用户过滤的 pgvector 余弦距离检索（top-N，距离升序）。
     *
     * <p>阈值过滤在服务层按距离判断（与知识库检索同款做法），此处仅做用户隔离 + 粗排取 top-N。</p>
     *
     * @param userId      住户用户 ID（强制过滤，越权防护）
     * @param queryVector 查询向量字面量（pgvector '[0.1,0.2,...]'，1024 维）
     * @param top         召回条数上限
     * @return 命中行列表（压缩段 id + 余弦距离，按距离升序）
     */
    @Query(value = "SELECT s.id AS id, CAST(s.embedding AS vector(1024)) <=> CAST(:queryVector AS vector(1024)) AS distance " +
            "FROM agent_memory_segments s " +
            "WHERE s.user_id = :userId AND s.embedding IS NOT NULL " +
            "ORDER BY distance ASC LIMIT :top", nativeQuery = true)
    List<MemorySimilarityHit> findIdsBySimilarity(@Param("userId") Long userId,
                                                  @Param("queryVector") String queryVector,
                                                  @Param("top") int top);

    /**
     * 记忆检索命中行 — 原生 pgvector 查询的类型化投影（压缩段 id + 余弦距离）。
     *
     * <p>刻意声明为 interface 而非 record/class：原生查询不支持 class-based 投影，Spring Data 会拿
     * {@code TupleBackedMap} 去找 Converter 并抛 {@code ConverterNotFoundException}；
     * interface 投影由 Spring Data 直接代理映射，是原生查询下唯一可用的类型化返回方式。</p>
     *
     * <p>查询列别名必须与 getter 名严格对应（{@code id} / {@code distance}）——
     * 别名不匹配时 getter 静默返回 null，不报错。</p>
     */
    interface MemorySimilarityHit {

        /**
         * 命中的压缩段 ID。
         *
         * @return 压缩段主键，对应 agent_memory_segments.id
         */
        Long getId();

        /**
         * 与查询向量的余弦距离（pgvector {@code <=>} 运算结果）。
         *
         * @return 距离值，越小越相似
         */
        double getDistance();
    }

    /**
     * 按会话级 conversation_id 查询全部压缩段（联动删除用，硬删）。
     *
     * <p>调用方必须先确认该会话归属该用户（ArchiveService.softDelete 已按 userId 过滤后传入）。</p>
     *
     * @param conversationIds 会话级 id 集合
     * @return 命中压缩段列表
     */
    List<AgentMemorySegment> findByConversationIdIn(Collection<Long> conversationIds);

    /**
     * 按用户 + 状态查询压缩段（RETRY 补压用）。
     *
     * @param userId 住户用户 ID
     * @param status 压缩段状态（如 {@code MemorySegmentStatus.RETRY}）
     * @return 命中压缩段列表
     */
    List<AgentMemorySegment> findByUserIdAndStatus(Long userId, String status);

    /**
     * 按用户 + 会话级 conversation_id 查询压缩段（计算会话内已用序号用）。
     *
     * @param userId         住户用户 ID
     * @param conversationId 会话级 id
     * @return 命中压缩段列表
     */
    List<AgentMemorySegment> findByUserIdAndConversationId(Long userId, Long conversationId);

    /**
     * 统计某用户的压缩段数量（记忆检索的「无段跳过」预检：无历史段时直接跳过 embedding 与向量检索）。
     *
     * @param userId 住户用户 ID
     * @return 该用户压缩段总数
     */
    long countByUserId(Long userId);
}
