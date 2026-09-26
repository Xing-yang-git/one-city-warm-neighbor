package com.platform.ai.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 检索质量评测工具（度量脚本，非回归测试）。
 *
 * <p>目的：为知识库检索链路给出可量化的准确率与耗时基线，用于调整
 * {@code ai.agent.knowledge-threshold} 等检索参数时对比效果。评测集由人工构造——
 * 每条查询对应知识库中确实含有答案的条目 id；因切片会按长度切分同一份文档，
 * 一条查询的答案可能落在多个切片上，故期望值为「可接受 id 集合」而非单一 id。
 *
 * <p>指标口径：
 * <ul>
 *   <li>Hit@1 / Hit@3：返回结果第 1 位 / 前 3 位中命中任一可接受 id 即算命中</li>
 *   <li>MRR：首个命中结果的排名倒数，衡量排序质量（未命中记 0）</li>
 *   <li>Precision@3：前 3 位中命中条数占比，衡量噪声水平</li>
 * </ul>
 *
 * <p><b>不在 {@code mvn test} 中自动执行</b>——类名不符合 surefire 默认扫描规则
 * （{@code *Test} / {@code Test*} / {@code *Tests} / {@code *TestCase}），
 * 理由是每次运行会真实调用 30 次 embedding 与 30 次重排（约 50 秒且消耗 API 额度），
 * 不适合作为每次构建的固定开销。需要时手动执行：
 * <pre>mvn -o test -Dtest=RetrievalEval</pre>
 *
 * <p>需要真实环境：PostgreSQL + pgvector（知识条目已向量化）、智谱 embedding 密钥、
 * 本地重排服务（127.0.0.1:8001）。三者任一不可用会触发降级，结果不代表完整链路能力。
 * 另注意上游调用存在抖动，同一配置多次运行结果可能不同（实测波动约 3~5 个百分点），
 * 对比参数时需以相同环境条件下的多次运行为准。
 */
@SpringBootTest
class RetrievalEval {

    /** 知识库所属租户（翠湖花园） */
    private static final Long TENANT_ID = 1L;

    /** Precision@K 的 K 值，与 {@code ai.doc.rerank-top-m}（重排后注入条数）保持一致 */
    private static final int PRECISION_K = 3;

    /** 评测用例：查询文本 → 可接受的知识条目 id 集合 */
    private record Case(String query, Set<Long> expected) {
        static Case of(String query, Long... ids) {
            return new Case(query, new LinkedHashSet<>(Arrays.asList(ids)));
        }
    }

    private static final List<Case> CASES = List.of(
            // —— 物业应急与安全（service）——
            Case.of("燃气泄漏或者燃气抢修打哪个电话", 107L, 123L, 178L),
            Case.of("电梯困人了应该怎么处理", 109L, 110L),
            Case.of("台风暴雨天气的应急措施", 110L),
            Case.of("水管爆裂漏水了怎么办", 108L),
            Case.of("发生火灾时怎么处置", 109L),
            Case.of("AED 除颤器放在小区什么位置", 177L),
            // —— 小区规章制度（rules）——
            Case.of("遛狗有什么规定", 135L, 136L),
            Case.of("楼道里堆放杂物怎么处理", 139L),
            Case.of("邻居装修太吵了怎么办", 133L, 139L, 140L),
            Case.of("门禁卡丢了怎么补办", 131L, 132L),
            Case.of("生活垃圾什么时间可以投放", 141L, 173L),
            Case.of("小区快递柜可以免费存放多久", 143L, 172L),
            Case.of("临时停车怎么收费", 137L, 138L),
            Case.of("怎么向物业投诉", 145L, 146L, 147L),
            // —— 办事指南（guide）——
            Case.of("居住证怎么办理需要什么材料", 112L, 113L),
            Case.of("犬证怎么办理", 120L, 121L),
            Case.of("装修押金要交多少钱", 116L, 133L),
            Case.of("装修施工时间有什么限制", 116L, 133L),
            Case.of("水电燃气怎么过户", 122L, 123L),
            Case.of("搬家出入证怎么办", 117L, 118L),
            Case.of("户口迁入需要什么材料", 113L, 114L, 115L),
            // —— 设施与周边（service）——
            Case.of("小区充电桩充电多少钱一度", 137L, 138L, 172L),
            Case.of("儿童游乐区几点开放", 172L, 176L),
            Case.of("小区附近有超市和菜市场吗", 174L),
            Case.of("最近的社区卫生服务中心在哪里", 174L, 178L),
            // —— 平台功能（help，系统内置权威依据）——
            Case.of("怎么发布闲置物品", 7L),
            Case.of("借东西的流程是什么", 8L),
            Case.of("互助评价规则是什么", 9L),
            Case.of("发布违规内容会怎么处理", 10L),
            Case.of("实名认证怎么做", 6L)
    );

    @Autowired
    private KnowledgeRetrievalService retrievalService;

    @Test
    @DisplayName("知识库检索评测：输出 Hit@1 / Hit@3 / MRR / Precision@3 与耗时")
    void evaluateRetrieval() {
        int hit1 = 0;
        int hit3 = 0;
        double mrrSum = 0;
        double precisionSum = 0;
        long totalNanos = 0;
        int emptyCount = 0;

        StringBuilder detail = new StringBuilder("\n=== 逐条结果 ===\n");

        for (Case c : CASES) {
            long start = System.nanoTime();
            List<KnowledgeHit> hits = retrievalService.searchForAgent(TENANT_ID, c.query());
            long elapsedNanos = System.nanoTime() - start;
            long costMs = elapsedNanos / 1_000_000;
            totalNanos += elapsedNanos;

            if (hits.isEmpty()) {
                emptyCount++;
            }

            int firstRank = -1;
            int matched = 0;
            for (int i = 0; i < hits.size(); i++) {
                if (c.expected().contains(hits.get(i).id())) {
                    matched++;
                    if (firstRank < 0) {
                        firstRank = i + 1;
                    }
                }
            }

            if (firstRank == 1) {
                hit1++;
            }
            if (firstRank > 0) {
                hit3++;
                mrrSum += 1.0 / firstRank;
            }
            // 分母固定取 K 而非实际返回条数：返回不足 K 条时若按实际条数算，
            // 「召回不足」会被算成高精度（返回 1 条且命中 = 100%），掩盖检索缺陷
            precisionSum += (double) matched / PRECISION_K;

            detail.append(String.format("  [%s] %-28s %5dms  期望=%s 实得=%s%n",
                    firstRank > 0 ? "命中" : "未中", c.query(), costMs, c.expected(),
                    hits.stream().map(KnowledgeHit::id).toList()));
        }

        int n = CASES.size();
        System.out.println(detail);
        System.out.println("=== 汇总（" + n + " 条查询，知识库 47 条目）===");
        System.out.printf("Hit@1          : %d/%d = %.1f%%%n", hit1, n, 100.0 * hit1 / n);
        System.out.printf("Hit@3          : %d/%d = %.1f%%%n", hit3, n, 100.0 * hit3 / n);
        System.out.printf("MRR            : %.3f%n", mrrSum / n);
        System.out.printf("Precision@3    : %.1f%%%n", 100.0 * precisionSum / n);
        System.out.printf("平均检索耗时   : %.0f ms/次%n", totalNanos / 1_000_000.0 / n);
        System.out.printf("零召回         : %d 次（含上游调用降级；二者当前无法从返回值区分）%n", emptyCount);
    }
}
