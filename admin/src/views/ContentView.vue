<template>
  <AppLayout title="内容管理" :main-class="LIST_PAGE_MAIN_CLASS">
    <div class="unified-panel">
      <!-- 标签页 -->
      <div class="tab">
        <div class="segment-row">
          <div
            v-for="t in contentTabs"
            :key="t.key"
            class="segment-btn"
            :class="{ active: activeTab === t.key }"
            @click="switchTab(t.key)"
          >
            {{ t.label }}
            <span
              v-if="t.key === 'moderation' && moderationTabCount > 0"
              class="tab-badge"
              >{{ moderationTabCount }}</span
            >
            <template v-if="t.key === 'pending'">
              <el-tooltip content="住户之间的申请审核" placement="top">
                <el-icon style="margin-left: 2px; cursor: help"
                  ><QuestionFilled
                /></el-icon>
              </el-tooltip>
            </template>
          </div>
        </div>
      </div>

      <!-- 筛选 -->
      <div class="filter-row">
        <!-- 审核 tab 专属筛选 -->
        <template v-if="activeTab === 'moderation'">
          <el-select
            class="filter-select"
            v-model="moderationFilter.status"
            placeholder="审核状态"
            style="width: 140px"
            @change="applyModerationFilters"
          >
            <el-option label="全部" value="" />
            <el-option label="待人工复核" value="yellow" />
            <el-option label="审核驳回" value="red" />
          </el-select>
          <el-select
            class="filter-select"
            v-model="moderationFilter.moderatedBy"
            placeholder="审核员"
            style="width: 110px"
            @change="applyModerationFilters"
          >
            <el-option label="全部" value="" />
            <el-option label="AI" value="ai" />
            <el-option label="管理员" value="admin" />
          </el-select>
          <el-select
            class="filter-select"
            v-model="moderationFilter.type"
            placeholder="类型"
            style="width: 120px"
            @change="applyModerationFilters"
          >
            <el-option label="全部" value="" />
            <el-option label="物品互借" value="idle" />
            <el-option label="技能互助" value="help" />
          </el-select>
          <el-select
            class="filter-select"
            v-model="moderationFilter.building"
            placeholder="楼栋"
            style="width: 110px"
            @change="
              moderationFilter.unit = '';
              applyModerationFilters();
            "
          >
            <el-option label="全部" value="" />
            <el-option
              v-for="b in communityStore.buildingOptions"
              :key="b.id"
              :label="b.buildingNo + '栋'"
              :value="b.buildingNo"
            />
          </el-select>
          <el-select
            class="filter-select"
            v-model="moderationFilter.unit"
            placeholder="单元"
            style="width: 100px"
            :disabled="!moderationFilter.building"
            @change="applyModerationFilters"
          >
            <el-option label="全部" value="" />
            <el-option
              v-for="u in moderationUnitOptions"
              :key="u.id"
              :label="u.unitNo + '单元'"
              :value="u.unitNo"
            />
          </el-select>
          <el-input
            class="search-box"
            v-model="moderationFilter.search"
            placeholder="搜索内容标题..."
            style="width: 150px"
            clearable
            @keydown.enter="onModerationSearchEnter"
            @clear="applyModerationFilters"
            @input="onModerationSearchInput"
          />
          <button
            type="button"
            class="btn-kong"
            style="margin-left: 8px"
            :disabled="!moderationSelected.length"
            @click="openModerationDetail()"
          >
            详细
          </button>
          <button
            type="button"
            class="btn-kong"
            style="margin-left: 8px"
            @click="openPublish"
          >
            代发
          </button>
          <span style="flex: 1"></span>
          <span class="text-sm text-secondary"
            >共 {{ moderationTotal }} 条</span
          >
        </template>
        <!-- 常规 tab 筛选 -->
        <template v-else>
          <el-select
            class="filter-select"
            v-model="filterType"
            placeholder="类型"
            style="width: 120px"
            @change="applyFilters"
          >
            <el-option label="全部" value="" />
            <el-option label="物品互借" value="idle" />
            <el-option label="技能互助" value="help" />
          </el-select>
          <el-select
            class="filter-select"
            v-model="filterBuilding"
            placeholder="楼栋"
            style="width: 110px"
            @change="
              filterUnit = '';
              applyFilters();
            "
          >
            <el-option label="全部" value="" />
            <el-option
              v-for="b in communityStore.buildingOptions"
              :key="b.id"
              :label="b.buildingNo + '栋'"
              :value="b.buildingNo"
            />
          </el-select>
          <el-select
            class="filter-select"
            v-model="filterUnit"
            placeholder="单元"
            style="width: 100px"
            :disabled="!filterBuilding"
            @change="applyFilters"
          >
            <el-option label="全部" value="" />
            <el-option
              v-for="u in filterUnitOptions"
              :key="u.id"
              :label="u.unitNo + '单元'"
              :value="u.unitNo"
            />
          </el-select>
          <el-select
            v-if="activeTab === 'offline'"
            class="filter-select"
            v-model="filterModeratedBy"
            placeholder="审核员"
            style="width: 110px"
            @change="applyFilters"
          >
            <el-option label="全部" value="" />
            <el-option label="AI" value="ai" />
            <el-option label="管理员" value="admin" />
          </el-select>
          <el-input
            class="search-box"
            v-model="search"
            placeholder="搜索内容标题..."
            style="width: 150px"
            clearable
            @keydown.enter="onSearchEnter"
            @clear="applyFilters"
            @input="onSearchInput"
          />
          <button
            type="button"
            class="btn-kong"
            style="margin-left: 8px"
            :disabled="!selectedRows.length"
            @click="openDetailFromSelection"
          >
            详细
          </button>
          <el-button type="primary" size="default" @click="openPublish"
            >物业代发</el-button
          >
          <span style="flex: 1"></span>
          <span class="text-sm text-secondary">共 {{ totalCount }} 条</span>
        </template>
      </div>

      <!-- 面板主体（填充面板高度，内部滚动）-->
      <div class="uf-body">
        <!-- 加载中 -->
        <div v-if="loading" class="panel-empty">加载中...</div>

        <!-- 审核 tab 表格 -->
        <div
          class="panel"
          v-else-if="activeTab === 'moderation' && moderationList.length"
        >
          <el-table
            :data="moderationList"
            height="100%"
            style="width: 100%"
            :row-class-name="getModerationRowClass"
            @selection-change="onModerationSelect"
            @row-dblclick="viewModerationDetail"
          >
            <el-table-column type="selection" width="50" />
            <el-table-column label="发布住户" min-width="70">
              <template #default="{ row }">
                {{ row.publisherRoom }}
              </template>
            </el-table-column>
            <el-table-column label="标题" min-width="120">
              <template #default="{ row }">
                <a
                  class="moderation-title-link"
                  @click="viewModerationDetail(row)"
                  >{{ row.title }}</a
                >
              </template>
            </el-table-column>
            <el-table-column label="类型" width="120" align="center">
              <template #default="{ row }">
                <el-tag>{{ postTypeLabel(row.postType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="AI原因" min-width="160">
              <template #default="{ row }">
                {{ row.delistReason || "—" }}
              </template>
            </el-table-column>
            <el-table-column label="审核员" width="100" align="center">
              <template #default="{ row }">
                {{ row.reviewedByName || "AI" }}
              </template>
            </el-table-column>
            <el-table-column
              prop="updatedAt"
              label="审核时间"
              width="160"
              align="center"
            >
              <template #default="{ row }">
                {{ formatTime(row.updatedAt) }}
              </template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 常规 tab 表格 -->
        <div
          class="panel"
          v-else-if="activeTab !== 'moderation' && tableData.length"
        >
          <el-table
            ref="contentTableRef"
            :data="tableData"
            height="100%"
            style="width: 100%"
            @selection-change="handleSelectionChange"
          >
            <el-table-column type="selection" width="30" />
            <el-table-column label="类型" align="center" min-width="30">
              <template #default="{ row }">
                <span
                  :class="[
                    'tag',
                    row.type === CONTENT_TYPE.IDLE ? 'tag-blue' : 'tag-orange',
                  ]"
                >
                  {{ row.type === CONTENT_TYPE.IDLE ? "互借" : "互助" }}
                </span>
              </template>
            </el-table-column>

            <!-- 待审批 tab 专属列：标题 + 审批人 + 申请人 -->
            <template v-if="activeTab === 'pending'">
              <el-table-column label="标题" min-width="120">
                <template #default="{ row }">
                  <span class="title-link" @click="openDetailById(row)">{{
                    row.title
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="审批住户" min-width="100">
                <template #default="{ row }">{{
                  row.approverName || "—"
                }}</template>
              </el-table-column>
              <el-table-column label="申请住户" min-width="100">
                <template #default="{ row }">{{
                  row.applicantName || row.publisherName || "—"
                }}</template>
              </el-table-column>
            </template>

            <!-- 违规下架 tab 专属列 -->
            <template v-else-if="activeTab === 'offline'">
              <el-table-column label="发布者" min-width="100">
                <template #default="{ row }">
                  {{ row.publisherRoom
                  }}<template v-if="row.isProxy"> - 物业代发</template>
                </template>
              </el-table-column>
              <el-table-column label="标题" min-width="100">
                <template #default="{ row }">
                  <span class="title-link" @click="openDetailById(row)">{{
                    row.title
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="下架时间" min-width="80">
                <template #default="{ row }">{{
                  formatTime(row.updatedAt)
                }}</template>
              </el-table-column>
              <el-table-column label="原因" min-width="130">
                <template #default="{ row }">{{
                  row.delistReason || "—"
                }}</template>
              </el-table-column>
              <el-table-column label="审核员" min-width="80" align="center">
                <template #default="{ row }">{{
                  row.reviewedByName || "AI"
                }}</template>
              </el-table-column>
            </template>

            <!-- 默认 tab（在线中 / 进行中 / 已完成） -->
            <template v-else>
              <el-table-column label="发布者" min-width="100">
                <template #default="{ row }">
                  {{ row.publisherRoom
                  }}<template v-if="row.isProxy"> - 物业代发</template>
                </template>
              </el-table-column>
              <el-table-column label="标题" min-width="100">
                <template #default="{ row }">
                  <span class="title-link" @click="openDetailById(row)">{{
                    row.title
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="发布时间" min-width="100">
                <template #default="{ row }">{{
                  formatTime(row.createdAt)
                }}</template>
              </el-table-column>
              <el-table-column label="状态" align="center" min-width="56">
                <template #default="{ row }">
                  <span :class="statusTag(row.displayStatus)">{{
                    statusLabel(row.displayStatus)
                  }}</span>
                </template>
              </el-table-column>
            </template>
          </el-table>
        </div>
        <!-- 无数据 -->
        <div
          v-else-if="
            (activeTab === 'moderation' && !moderationList.length) ||
            (activeTab !== 'moderation' && !tableData.length)
          "
          class="panel-empty"
        >
          暂无内容
        </div>
      </div>
    </div>

    <!-- 详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      title="发布内容详情"
      width="560px"
      top="10vh"
    >
      <div v-if="detailLoading" style="text-align: center; padding: 20px">
        加载中...
      </div>
      <div v-else-if="detailItem">
        <!-- 待审批 tab：精简详情（标题 / 借出时长或时间段 / 发布住户 / 申请住户） -->
        <template v-if="activeTab === 'pending'">
          <div class="detail-row">
            <span class="dl">标题</span
            ><span class="dv">{{ detailItem.title }}</span>
          </div>
          <template v-if="detailItem.type === CONTENT_TYPE.IDLE">
            <div class="detail-row">
              <span class="dl">借出时长</span
              ><span class="dv">{{
                detailItem.maxDuration != null
                  ? detailItem.maxDuration +
                    (detailItem.durationUnit === "hour" ? "小时" : "天")
                  : "—"
              }}</span>
            </div>
          </template>
          <template v-if="detailItem.type === CONTENT_TYPE.HELP">
            <div class="detail-row">
              <span class="dl">预计开始</span
              ><span class="dv">{{
                formatTime(detailItem.timeStart) || "—"
              }}</span>
            </div>
            <div class="detail-row">
              <span class="dl">预计结束</span
              ><span class="dv">{{
                formatTime(detailItem.timeEnd) || "—"
              }}</span>
            </div>
          </template>
          <div class="detail-row">
            <span class="dl">发布住户</span
            ><span class="dv">{{
              detailItem.approverName || detailItem.publisherRoom || "—"
            }}</span>
          </div>
          <div class="detail-row">
            <span class="dl">申请住户</span
            ><span class="dv">{{
              detailItem.applicantName || detailItem.publisherName || "—"
            }}</span>
          </div>
          <div class="dv-divider"></div>
          <p class="text-sm text-secondary" style="line-height: 1.6">
            此申请由住户发起。物业管理员可对其进行巡查，如发现违规内容可执行下架操作。下架后系统将通知发布者。
          </p>
        </template>
        <!-- 其他 tab：原有完整详情 -->
        <template v-else>
          <div class="detail-row">
            <span class="dl">标题</span
            ><span class="dv">{{ detailItem.title }}</span>
          </div>
          <div class="detail-row">
            <span class="dl">发布者</span
            ><span class="dv">{{ detailItem.publisherRoom }}</span>
          </div>
          <div class="detail-row">
            <span class="dl">小区</span><span class="dv">翠湖花园</span>
          </div>
          <div class="detail-row">
            <span class="dl">类型</span
            ><span class="dv">
              <span
                :class="[
                  'tag',
                  detailItem.type === CONTENT_TYPE.IDLE ? 'tag-blue' : 'tag-orange',
                ]"
              >
                {{ detailItem.type === CONTENT_TYPE.IDLE ? "互借" : "互助" }}
              </span>
            </span>
          </div>
          <!-- 详情描述：C端选填，未获取到时显示「无」 -->
          <div class="detail-row" style="align-items: flex-start">
            <span class="dl">详情描述</span>
            <span class="dv" style="white-space: pre-wrap">{{
              detailItem.description || "无"
            }}</span>
          </div>
          <!-- 互助：预计开始/预计结束（来自发布时填写的值） -->
          <template v-if="detailItem.type === CONTENT_TYPE.HELP">
            <div class="detail-row">
              <span class="dl">预计开始</span
              ><span class="dv">{{
                formatTime(detailItem.timeStart) || "--"
              }}</span>
            </div>
            <div class="detail-row">
              <span class="dl">预计结束</span
              ><span class="dv">{{
                formatTime(detailItem.timeEnd) || "--"
              }}</span>
            </div>
          </template>
          <!-- 互借：借用时长（来自发布时填写的最大借出/需要借入时长） -->
          <template
            v-if="detailItem.type === CONTENT_TYPE.IDLE && detailItem.maxDuration != null"
          >
            <div class="detail-row">
              <span class="dl">借用时长</span
              ><span class="dv">{{
                detailItem.maxDuration +
                (detailItem.durationUnit === "hour" ? "小时" : "天")
              }}</span>
            </div>
          </template>
          <!-- 图片：C端选填，未获取到时显示「无」 -->
          <div class="detail-row" style="align-items: flex-start">
            <span class="dl">图片</span>
            <span class="dv">
              <span
                v-if="validDetailImages.length"
                style="display: flex; gap: 8px; flex-wrap: wrap"
              >
                <el-image
                  v-for="(img, i) in validDetailImages"
                  :key="i"
                  :src="img"
                  :preview-src-list="validDetailImages"
                  :initial-index="i"
                  fit="cover"
                  preview-teleported
                  hide-on-click-modal
                  @error="onImageError(i)"
                  style="
                    width: 80px;
                    height: 80px;
                    border-radius: 8px;
                    cursor: pointer;
                  "
                />
              </span>
              <template v-else>无</template>
            </span>
          </div>

          <template v-if="detailItem.displayStatus === '已下架'">
            <div class="dv-divider"></div>
            <p class="text-sm text-secondary" style="margin-bottom: 8px">
              下架信息
            </p>
            <div class="detail-row">
              <span class="dl">下架原因</span
              ><span class="dv">{{ detailItem.delistReason || "—" }}</span>
            </div>
            <div class="detail-row">
              <span class="dl">下架时间</span
              ><span class="dv">{{
                formatTime(detailItem.updatedAt) || "—"
              }}</span>
            </div>
            <div class="detail-row">
              <span class="dl">审核管理员</span
              ><span class="dv">{{ detailItem.reviewedByName || "AI" }}</span>
            </div>
          </template>

          <div class="dv-divider"></div>
          <p class="text-sm text-secondary" style="line-height: 1.6">
            此内容由住户自主发布。物业管理员可对其进行巡查，如发现违规内容可执行下架操作。下架后系统将通知发布者。
          </p>
        </template>
      </div>
      <template #footer>
        <div style="display: flex; align-items: center; gap: 8px">
          <template v-if="detailIdList.length > 1">
            <el-button
              :icon="ArrowLeft"
              circle
              :disabled="detailIdPos === 0"
              @click="detailGo(-1)"
            />
            <span class="text-sm text-secondary"
              >{{ detailIdPos + 1 }} / {{ detailIdList.length }}</span
            >
            <el-button
              :icon="ArrowRight"
              circle
              :disabled="detailIdPos === detailIdList.length - 1"
              @click="detailGo(1)"
            />
          </template>
          <span style="flex: 1"></span>
          <el-button @click="detailVisible = false">关闭</el-button>
          <el-button
            v-if="detailItem && detailItem.displayStatus !== '已下架'"
            type="danger"
            @click="offlineFromDetail"
            >下架</el-button
          >
        </div>
      </template>
    </el-dialog>

    <!-- 审核详情弹窗 -->
    <el-dialog
      v-model="moderationDialogVisible"
      title="审核详情"
      width="560px"
      top="10vh"
    >
      <div
        v-if="moderationDetailLoading"
        style="text-align: center; padding: 20px"
      >
        加载中...
      </div>
      <div v-else-if="moderationDetailItem">
        <!-- 帖子详情区 -->
        <div class="detail-row">
          <span class="dl">标题</span
          ><span class="dv">{{ moderationDetailItem.title }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">描述</span
          ><span class="dv">{{
            moderationDetailItem.description || "无"
          }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">发布者</span
          ><span class="dv">{{ moderationDetailItem.publisherRoom }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">发布时间</span
          ><span class="dv">{{
            formatModerationTime(moderationDetailItem.createdAt)
          }}</span>
        </div>
        <!-- 图片 -->
        <div
          class="detail-row"
          style="align-items: flex-start"
          v-if="validModerationImages.length"
        >
          <span class="dl">图片</span>
          <span class="dv">
            <span style="display: flex; gap: 8px; flex-wrap: wrap">
              <el-image
                v-for="(img, i) in validModerationImages"
                :key="i"
                :src="img"
                :preview-src-list="validModerationImages"
                :initial-index="i"
                fit="cover"
                preview-teleported
                hide-on-click-modal
                style="
                  width: 80px;
                  height: 80px;
                  border-radius: 8px;
                  cursor: pointer;
                "
              />
            </span>
          </span>
        </div>

        <div class="dv-divider"></div>
        <!-- AI 审核结果区 -->
        <p class="text-sm text-secondary" style="margin-bottom: 8px">
          AI 审核结果
        </p>
        <div class="detail-row">
          <span class="dl">审核等级</span
          ><span class="dv">
            <el-tag>{{ moderationLevelLabel(moderationDetailItem.moderationStatus) }}</el-tag>
          </span>
        </div>
        <div class="detail-row">
          <span class="dl">审核原因</span
          ><span class="dv">{{
            moderationDetailItem.delistReason || "—"
          }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">审核员</span
          ><span class="dv">{{
            moderationDetailItem.reviewedByName || "AI"
          }}</span>
        </div>
        <div class="detail-row">
          <span class="dl">审核时间</span
          ><span class="dv">{{
            formatModerationTime(moderationDetailItem.updatedAt)
          }}</span>
        </div>
      </div>
      <template #footer>
        <div style="display: flex; align-items: center; gap: 8px">
          <template v-if="moderationIdList.length > 1">
            <el-button
              :icon="ArrowLeft"
              circle
              :disabled="moderationIdPos === 0"
              @click="prevModerationItem"
            />
            <span class="text-sm text-secondary"
              >{{ moderationIdPos + 1 }} / {{ moderationIdList.length }}</span
            >
            <el-button
              :icon="ArrowRight"
              circle
              :disabled="moderationIdPos >= moderationIdList.length - 1"
              @click="nextModerationItem"
            />
          </template>
          <span style="flex: 1"></span>
          <el-button @click="moderationDialogVisible = false">取消</el-button>
          <!-- green：只能下架 -->
          <template
            v-if="
              moderationDetailItem &&
              moderationDetailItem.moderationStatus === 'green'
            "
          >
            <el-button type="danger" @click="offlineFromModerationDetail"
              >下架</el-button
            >
          </template>
          <!-- reviewed + 在线中：只能下架 -->
          <template
            v-else-if="
              moderationDetailItem &&
              moderationDetailItem.moderationStatus === 'reviewed' &&
              moderationDetailItem.rawStatus !== 'offline'
            "
          >
            <el-button type="danger" @click="offlineFromModerationDetail"
              >下架</el-button
            >
          </template>
          <!-- yellow：通过 + 驳回 -->
          <template
            v-else-if="
              moderationDetailItem &&
              moderationDetailItem.moderationStatus === 'yellow'
            "
          >
            <el-button type="primary" @click="showApproveConfirm = true"
              >通过</el-button
            >
            <el-button type="danger" @click="rejectModeration">驳回</el-button>
          </template>
        </div>
      </template>
    </el-dialog>

    <!-- 下架/驳回弹窗 -->
    <el-dialog
      v-model="offlineVisible"
      :title="offlineIsModeration ? '驳回' : '确认下架'"
      width="480px"
    >
      <p style="text-align: center; margin-bottom: 12px">
        确认下架「<strong>{{ offlineTarget?.title }}</strong
        >」？
      </p>
      <div style="margin-bottom: 8px; font-weight: 600">下架原因：</div>
      <el-checkbox-group
        v-model="offlineReasons"
        style="display: flex; flex-direction: column; gap: 4px"
      >
        <el-checkbox
          v-for="reason in OFFLINE_REASON_OPTIONS"
          :key="reason"
          :value="reason"
          :label="reason"
        />
      </el-checkbox-group>
      <div class="mt-8">
        <el-input
          v-model="offlineCustomReason"
          placeholder="自定义原因..."
          style="width: 100%"
        />
      </div>
      <template #footer>
        <el-button @click="cancelOffline">取消</el-button>
        <el-button
          type="danger"
          :loading="offlineSubmitting"
          @click="doOffline"
          >{{ offlineIsModeration ? "确认驳回" : "确认下架" }}</el-button
        >
      </template>
    </el-dialog>

    <!-- 审核通过确认弹窗 -->
    <el-dialog v-model="showApproveConfirm" title="确认通过" width="420px">
      <p style="margin-bottom: 16px">
        确认通过「<strong>{{ moderationDetailItem?.title }}</strong
        >」的审核，该内容将公开展示？
      </p>
      <template #footer>
        <el-button @click="showApproveConfirm = false">取消</el-button>
        <el-button type="primary" @click="doApproveModeration">确认</el-button>
      </template>
    </el-dialog>

    <!-- 代发弹窗 -->
    <el-dialog
      v-model="publishVisible"
      title="物业代发"
      width="560px"
      top="5vh"
    >
      <div style="max-height: 70vh; overflow-y: auto; padding-right: 4px">
        <!-- 目标住户选择（共用） -->
        <div style="margin-bottom: 16px">
          <span class="field-label" style="display: block; margin-bottom: 6px">
            目标住户 <span style="color: var(--red)">*</span>
          </span>
          <el-input
            class="field-input"
            readonly
            :model-value="selectedResident"
            style="width: 100%; cursor: pointer"
            @click="openResidentSearch"
            placeholder="点击检索住户"
          />
        </div>

        <!-- 发布类型 tabs（3 个） -->
        <div class="segment-row" style="margin-bottom: 16px">
          <button
            class="segment-btn"
            :class="{ active: publishMode === 'lend' }"
            @click="switchPublishMode('lend')"
          >
            闲置借出
          </button>
          <button
            class="segment-btn"
            :class="{ active: publishMode === 'wanted' }"
            @click="switchPublishMode('wanted')"
          >
            需求借入
          </button>
          <button
            class="segment-btn"
            :class="{ active: publishMode === 'help' }"
            @click="switchPublishMode('help')"
          >
            技能求助
          </button>
        </div>

        <!-- ============ LEND 闲置借出 ============ -->
        <template v-if="publishMode === 'lend'">
          <div class="publish-field">
            <span class="field-label"
              >物品标题 <span style="color: var(--red)">*</span></span
            >
            <el-input
              v-model="publishForm.title"
              placeholder="例：博世冲击钻套装 GBH 2-20"
              style="width: 100%"
            />
          </div>

          <div class="publish-field">
            <span class="field-label"
              >物品类型 <span style="color: var(--red)">*</span></span
            >
            <div style="display: flex; flex-wrap: wrap; gap: 6px">
              <button
                v-for="cat in IDLE_CATEGORIES"
                :key="cat"
                class="btn-sm-toggle"
                :class="{ active: publishForm.category === cat }"
                @click="
                  publishForm.category = publishForm.category === cat ? '' : cat
                "
              >
                {{ cat }}
              </button>
            </div>
            <el-input
              v-if="publishForm.category === '其他'"
              v-model="publishForm.customCategory"
              placeholder="手动输入类型"
              style="width: 100%; margin-top: 8px"
            />
          </div>

          <div class="publish-field">
            <span class="field-label"
              >参考价格 <span style="color: var(--red)">*</span>（人民币）</span
            >
            <el-input
              v-model="publishForm.price"
              type="number"
              placeholder="用于损坏赔偿基准"
              style="width: 100%"
            />
          </div>

          <div class="publish-field">
            <span class="field-label">物品详细描述</span>
            <el-input
              class="field-input"
              type="textarea"
              :rows="3"
              v-model="publishForm.desc"
              placeholder="描述物品现状、使用痕迹、附件清单、借用注意事项等"
              style="width: 100%"
            />
          </div>

          <!-- 借出时长 -->
          <div class="publish-field">
            <span class="field-label"
              >借出时长 <span style="color: var(--red)">*</span></span
            >
            <div class="segment-row" style="margin-bottom: 8px">
              <button
                class="segment-btn"
                :class="{ active: publishForm.durationUnit === 'day' }"
                @click="publishForm.durationUnit = 'day'"
              >
                按天
              </button>
              <button
                class="segment-btn"
                :class="{ active: publishForm.durationUnit === 'hour' }"
                @click="publishForm.durationUnit = 'hour'"
              >
                按小时
              </button>
            </div>
            <el-select
              v-if="publishForm.durationUnit === 'day'"
              v-model="publishForm.durationValue"
              class="control-select"
              style="width: 100%"
            >
              <el-option
                v-for="d in DURATION_DAY_OPTIONS"
                :key="d"
                :label="`${d} 天`"
                :value="d"
              />
            </el-select>
            <el-select
              v-else
              v-model="publishForm.durationValue"
              class="control-select"
              style="width: 100%"
            >
              <el-option
                v-for="h in DURATION_HOUR_OPTIONS"
                :key="h"
                :label="`${h} 小时`"
                :value="h"
              />
            </el-select>
          </div>

          <!-- 借出形式 -->
          <div class="publish-field">
            <span class="field-label"
              >借出形式 <span style="color: var(--red)">*</span></span
            >
            <div class="segment-row">
              <button
                class="segment-btn"
                :class="{
                  active: publishForm.pickupMethod === PICKUP_METHOD.SELF_PICKUP,
                }"
                @click="publishForm.pickupMethod = PICKUP_METHOD.SELF_PICKUP"
              >
                需自提
              </button>
              <button
                class="segment-btn"
                :class="{ active: publishForm.pickupMethod === PICKUP_METHOD.BOTH }"
                @click="publishForm.pickupMethod = PICKUP_METHOD.BOTH"
              >
                自提 / 可送上门
              </button>
            </div>
          </div>

          <!-- 物品状况 -->
          <div class="publish-field">
            <span class="field-label"
              >物品状况 <span style="color: var(--red)">*</span></span
            >
            <div class="segment-row">
              <button
                class="segment-btn"
                :class="{ active: publishForm.condition === ITEM_CONDITION.LIKE_NEW }"
                @click="publishForm.condition = ITEM_CONDITION.LIKE_NEW"
              >
                几乎全新
              </button>
              <button
                class="segment-btn"
                :class="{ active: publishForm.condition === ITEM_CONDITION.NORMAL }"
                @click="publishForm.condition = ITEM_CONDITION.NORMAL"
              >
                正常使用痕迹
              </button>
              <button
                class="segment-btn"
                :class="{ active: publishForm.condition === ITEM_CONDITION.WORN }"
                @click="publishForm.condition = ITEM_CONDITION.WORN"
              >
                有明显磨损
              </button>
            </div>
          </div>

          <!-- 图片上传（选填） -->
          <div class="publish-field">
            <span class="field-label">物品图片（选填，最多 4 张）</span>
            <div
              style="
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
                margin-bottom: 8px;
              "
            >
              <div
                v-for="(img, i) in publishForm.images"
                :key="i"
                style="position: relative; width: 72px; height: 72px"
              >
                <img
                  :src="img"
                  style="
                    width: 72px;
                    height: 72px;
                    object-fit: cover;
                    border-radius: 6px;
                  "
                />
                <button
                  style="
                    position: absolute;
                    top: -6px;
                    right: -6px;
                    width: 20px;
                    height: 20px;
                    border: none;
                    border-radius: 50%;
                    background: var(--red);
                    color: #fff;
                    font-size: 12px;
                    cursor: pointer;
                    line-height: 1;
                  "
                  @click="removePublishImage(i)"
                >
                  ×
                </button>
              </div>
              <div
                v-if="publishForm.images.length < 4"
                style="
                  width: 72px;
                  height: 72px;
                  border: 1px dashed var(--border);
                  border-radius: 6px;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  cursor: pointer;
                "
                :class="{ 'is-uploading': publishUploading }"
                @click="handlePublishImageUpload"
              >
                <span
                  v-if="publishUploading"
                  style="font-size: 12px; color: var(--text-tertiary)"
                  >上传中</span
                >
                <span
                  v-else
                  style="font-size: 24px; color: var(--text-tertiary)"
                  >+</span
                >
              </div>
            </div>
          </div>
        </template>

        <!-- ============ WANTED 需求借入 ============ -->
        <template v-if="publishMode === 'wanted'">
          <div class="publish-field">
            <span class="field-label"
              >物品名称 <span style="color: var(--red)">*</span></span
            >
            <el-input
              v-model="publishForm.title"
              placeholder="例：博世冲击钻套装 GBH 2-20"
              style="width: 100%"
            />
          </div>

          <div class="publish-field">
            <span class="field-label"
              >物品类型 <span style="color: var(--red)">*</span></span
            >
            <div style="display: flex; flex-wrap: wrap; gap: 6px">
              <button
                v-for="cat in IDLE_CATEGORIES"
                :key="cat"
                class="btn-sm-toggle"
                :class="{ active: publishForm.category === cat }"
                @click="
                  publishForm.category = publishForm.category === cat ? '' : cat
                "
              >
                {{ cat }}
              </button>
            </div>
            <el-input
              v-if="publishForm.category === '其他'"
              v-model="publishForm.customCategory"
              placeholder="手动输入类型"
              style="width: 100%; margin-top: 8px"
            />
          </div>

          <div class="publish-field">
            <span class="field-label"
              >用途说明 <span style="color: var(--red)">*</span></span
            >
            <el-input
              class="field-input"
              type="textarea"
              :rows="3"
              v-model="publishForm.desc"
              placeholder="请描述您需要借用此物品的原因和用途，让出借人更好地了解您的需求"
              style="width: 100%"
            />
          </div>

          <!-- 期望借用时长 -->
          <div class="publish-field">
            <span class="field-label"
              >期望借用时长 <span style="color: var(--red)">*</span></span
            >
            <div class="segment-row" style="margin-bottom: 8px">
              <button
                class="segment-btn"
                :class="{ active: publishForm.durationUnit === 'day' }"
                @click="publishForm.durationUnit = 'day'"
              >
                按天
              </button>
              <button
                class="segment-btn"
                :class="{ active: publishForm.durationUnit === 'hour' }"
                @click="publishForm.durationUnit = 'hour'"
              >
                按小时
              </button>
            </div>
            <el-select
              v-if="publishForm.durationUnit === 'day'"
              v-model="publishForm.durationValue"
              class="control-select"
              style="width: 100%"
            >
              <el-option
                v-for="d in DURATION_DAY_OPTIONS"
                :key="d"
                :label="`${d} 天`"
                :value="d"
              />
            </el-select>
            <el-select
              v-else
              v-model="publishForm.durationValue"
              class="control-select"
              style="width: 100%"
            >
              <el-option
                v-for="h in DURATION_HOUR_OPTIONS"
                :key="h"
                :label="`${h} 小时`"
                :value="h"
              />
            </el-select>
          </div>

          <!-- 图片上传（选填） -->
          <div class="publish-field">
            <span class="field-label">物品图片（选填，最多 4 张）</span>
            <div
              style="
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
                margin-bottom: 8px;
              "
            >
              <div
                v-for="(img, i) in publishForm.images"
                :key="i"
                style="position: relative; width: 72px; height: 72px"
              >
                <img
                  :src="img"
                  style="
                    width: 72px;
                    height: 72px;
                    object-fit: cover;
                    border-radius: 6px;
                  "
                />
                <button
                  style="
                    position: absolute;
                    top: -6px;
                    right: -6px;
                    width: 20px;
                    height: 20px;
                    border: none;
                    border-radius: 50%;
                    background: var(--red);
                    color: #fff;
                    font-size: 12px;
                    cursor: pointer;
                    line-height: 1;
                  "
                  @click="removePublishImage(i)"
                >
                  ×
                </button>
              </div>
              <div
                v-if="publishForm.images.length < 4"
                style="
                  width: 72px;
                  height: 72px;
                  border: 1px dashed var(--border);
                  border-radius: 6px;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  cursor: pointer;
                "
                :class="{ 'is-uploading': publishUploading }"
                @click="handlePublishImageUpload"
              >
                <span
                  v-if="publishUploading"
                  style="font-size: 12px; color: var(--text-tertiary)"
                  >上传中</span
                >
                <span
                  v-else
                  style="font-size: 24px; color: var(--text-tertiary)"
                  >+</span
                >
              </div>
            </div>
          </div>
        </template>

        <!-- ============ HELP 技能求助 ============ -->
        <template v-if="publishMode === 'help'">
          <div class="publish-field">
            <span class="field-label"
              >求助标题 <span style="color: var(--red)">*</span></span
            >
            <el-input
              v-model="publishForm.title"
              placeholder="简要描述你需要的帮助"
              style="width: 100%"
            />
          </div>

          <div class="publish-field">
            <span class="field-label"
              >求助类型 <span style="color: var(--red)">*</span></span
            >
            <div style="display: flex; flex-wrap: wrap; gap: 6px">
              <button
                v-for="cat in HELP_CATEGORIES"
                :key="cat"
                class="btn-sm-toggle"
                :class="{ active: publishForm.category === cat }"
                @click="
                  publishForm.category = publishForm.category === cat ? '' : cat
                "
              >
                {{ cat }}
              </button>
            </div>
            <el-input
              v-if="publishForm.category === '其他'"
              v-model="publishForm.customCategory"
              placeholder="手动输入求助类型"
              style="width: 100%; margin-top: 8px"
            />
          </div>

          <div class="publish-field">
            <span class="field-label"
              >详细描述 <span style="color: var(--red)">*</span></span
            >
            <el-input
              class="field-input"
              type="textarea"
              :rows="3"
              v-model="publishForm.desc"
              placeholder="描述具体情况、需要什么帮助、大概需要多长时间等"
              style="width: 100%"
            />
          </div>

          <div class="publish-field">
            <span class="field-label"
              >紧急程度 <span style="color: var(--red)">*</span></span
            >
            <div class="segment-row">
              <button
                class="segment-btn"
                :class="{ active: publishForm.urgency === '一般' }"
                @click="publishForm.urgency = '一般'"
              >
                一般
              </button>
              <button
                class="segment-btn"
                :class="{ active: publishForm.urgency === '紧急' }"
                @click="publishForm.urgency = '紧急'"
              >
                紧急
              </button>
            </div>
          </div>

          <!-- 预计时间范围（选填，toggle 控制） -->
          <div class="publish-field">
            <div
              style="
                display: flex;
                align-items: center;
                justify-content: space-between;
              "
            >
              <span class="field-label" style="margin-bottom: 0"
                >预计时间范围（选填）</span
              >
              <el-switch v-model="publishForm.enableTimeRange" size="small" />
            </div>
            <template v-if="publishForm.enableTimeRange">
              <div style="display: flex; gap: 8px; margin-top: 8px">
                <el-date-picker
                  v-model="publishForm.startTime"
                  type="datetime"
                  placeholder="起始时间"
                  format="YYYY-MM-DD HH:mm"
                  value-format="YYYY-MM-DD HH:mm"
                  style="flex: 1"
                />
                <span style="align-self: center; color: var(--text-secondary)"
                  >—</span
                >
                <el-date-picker
                  v-model="publishForm.endTime"
                  type="datetime"
                  placeholder="终了时间"
                  format="YYYY-MM-DD HH:mm"
                  value-format="YYYY-MM-DD HH:mm"
                  style="flex: 1"
                />
              </div>
            </template>
            <p v-else class="text-xs text-tertiary" style="margin-top: 4px">
              打开开关可设置期望的帮助时间范围，不设置则表示时间灵活
            </p>
          </div>

          <!-- 图片上传（选填） -->
          <div class="publish-field">
            <span class="field-label">物品图片（选填，最多 4 张）</span>
            <div
              style="
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
                margin-bottom: 8px;
              "
            >
              <div
                v-for="(img, i) in publishForm.images"
                :key="i"
                style="position: relative; width: 72px; height: 72px"
              >
                <img
                  :src="img"
                  style="
                    width: 72px;
                    height: 72px;
                    object-fit: cover;
                    border-radius: 6px;
                  "
                />
                <button
                  style="
                    position: absolute;
                    top: -6px;
                    right: -6px;
                    width: 20px;
                    height: 20px;
                    border: none;
                    border-radius: 50%;
                    background: var(--red);
                    color: #fff;
                    font-size: 12px;
                    cursor: pointer;
                    line-height: 1;
                  "
                  @click="removePublishImage(i)"
                >
                  ×
                </button>
              </div>
              <div
                v-if="publishForm.images.length < 4"
                style="
                  width: 72px;
                  height: 72px;
                  border: 1px dashed var(--border);
                  border-radius: 6px;
                  display: flex;
                  align-items: center;
                  justify-content: center;
                  cursor: pointer;
                "
                :class="{ 'is-uploading': publishUploading }"
                @click="handlePublishImageUpload"
              >
                <span
                  v-if="publishUploading"
                  style="font-size: 12px; color: var(--text-tertiary)"
                  >上传中</span
                >
                <span
                  v-else
                  style="font-size: 24px; color: var(--text-tertiary)"
                  >+</span
                >
              </div>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <el-button @click="publishVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="publishSubmitting"
          @click="submitPublish"
          >确认发布</el-button
        >
      </template>
    </el-dialog>

    <!-- 住户检索弹窗 -->
    <el-dialog v-model="residentVisible" title="住户查找" width="620px">
      <!-- 筛选 -->
      <div
        style="display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap"
      >
        <el-select
          class="filter-select"
          v-model="residentFilterType"
          placeholder="类型"
          style="width: 90px"
          @change="loadAllResidents"
        >
          <el-option label="全部" value="" />
          <el-option label="业主" value="业主" />
          <el-option label="租客" value="租客" />
        </el-select>
        <el-select
          class="filter-select"
          v-model="residentFilterBuilding"
          placeholder="楼栋"
          style="width: 90px"
          @change="
            residentFilterUnit = '';
            loadAllResidents();
          "
        >
          <el-option label="全部" value="" />
          <el-option
            v-for="b in communityStore.buildingOptions"
            :key="b.id"
            :label="b.buildingNo + '栋'"
            :value="b.buildingNo"
          />
        </el-select>
        <el-select
          class="filter-select"
          v-model="residentFilterUnit"
          placeholder="单元"
          style="width: 100px"
          :disabled="!residentFilterBuilding"
          @change="loadAllResidents"
        >
          <el-option label="全部" value="" />
          <el-option
            v-for="u in residentUnitOptions"
            :key="u.id"
            :label="u.unitNo + '单元'"
            :value="u.unitNo"
          />
        </el-select>
        <el-input
          class="search-box"
          v-model="residentKeyword"
          placeholder="搜索姓名或房号..."
          style="width: 170px"
          clearable
          @keydown.enter="onResidentSearchEnter"
          @clear="loadAllResidents"
          @blur="loadAllResidents"
        />
      </div>
      <div
        style="max-height: 280px; overflow-y: auto"
        v-loading="loadingResidents"
      >
        <template v-if="residentList.length">
          <div v-for="r in residentList" :key="r.id">
            <div
              class="rs-item"
              :class="{ selected: tempSelected === r.id }"
              @click="pickResident(r)"
            >
              <span style="flex: 1; font-size: 14px"
                >{{ r.room }}({{ r.userType }}) -
                {{ displayName(r.name) }}</span
              >
              <svg
                v-if="tempSelected === r.id"
                width="18"
                height="18"
                viewBox="0 0 24 24"
                fill="none"
                stroke="var(--accent)"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              >
                <polyline points="20 6 9 17 4 12" />
              </svg>
            </div>
            <div class="rs-separator"></div>
          </div>
        </template>
        <div v-else class="panel-empty">未找到匹配住户</div>
      </div>
      <template #footer>
        <el-button @click="residentVisible = false">取消</el-button>
        <el-button type="primary" @click="selectResident">确认</el-button>
      </template>
    </el-dialog>
  </AppLayout>
</template>

<!--
  ContentView.vue — 内容管理（闲置物品 / 互助求助）

  功能：按状态标签页筛选（在线中/待审批/进行中/已完成/已下架/全部）、类型切换（闲置/互助）、关键词搜索、
        违规下架处理、内容详情查看。
  权限：需管理员 / 超级管理员登录。
-->
<script setup lang="ts">
import { ref, reactive, computed, onMounted, nextTick } from "vue";
import { ElMessage } from "element-plus";
import { ArrowLeft, ArrowRight, QuestionFilled } from "@element-plus/icons-vue";

import { useCommunityStore } from "@/stores/community";
import {
  getContentList,
  getContentDetail,
  offlineContent,
  publishIdle,
  publishHelp,
  searchResidents,
  getModerationList,
  getModerationCounts,
  approveContent,
  offlineModerationContent,
  type ContentItemDTO,
  type ContentListParams,
  type ModerationItemDTO,
  type ModerationListParams,
  type ModerationCounts,
} from "@/api/admin";
import { upload } from "@/utils/api";
import { POST_TYPE, CONTENT_TYPE, ITEM_CONDITION, PICKUP_METHOD } from "@/utils/constants";
import AppLayout from "@/layouts/AppLayout.vue";
import { LIST_PAGE_MAIN_CLASS } from "@/layouts/main-classes";
import type { AxiosResponse } from "axios";

// --- 本地类型 ---

/** 代发表单数据结构（闲置借出 / 需求借入 / 技能求助共用） */
interface PublishForm {
  title: string;
  desc: string;
  price: string;
  category: string;
  customCategory: string;
  /** 时长单位：day / hour */
  durationUnit: string;
  /** 时长数值：天 1-7，小时 1-23 */
  durationValue: number;
  urgency: string;
  startTime: string;
  endTime: string;
  /** 借出形式：self_pickup / both */
  pickupMethod: string;
  /** 物品状况：like-new / normal / worn */
  condition: string;
  /** 是否启用预计时间范围（技能求助 tab 的 toggle 开关） */
  enableTimeRange: boolean;
  /** 已上传的图片 URL 列表 */
  images: string[];
}

/** 内容列表行数据结构 */
interface ContentRow {
  id: number;
  type: "idle" | "help";
  title: string;
  status: string;
  publisherName: string;
  createdAt: string;
  images?: string[];
  [key: string]: any;
}

const communityStore = useCommunityStore();

/** 内容标签页配置 */
const contentTabs = [
  { key: "moderation", label: "审核发布" },
  { key: "show", label: "在线中" },
  { key: "pending", label: "待审批" },
  { key: "progress", label: "进行中" },
  { key: "done", label: "已完成" },
  { key: "offline", label: "违规下架" },
];

/** 标签页 key → 后端 API status 参数映射 */
const STATUS_PARAM_MAP: Record<string, string> = {
  show: "showing",
  pending: "pending",
  progress: "progressing",
  done: "completed",
  offline: "violation",
};

/** 当前激活的标签页 */
const activeTab = ref<string>("moderation");
/** 筛选条件：物品/技能类型 */
const filterType = ref("");
/** 筛选条件：楼栋（数值楼栋号；空串=全部） */
const filterBuilding = ref<number | "">("");
/** 筛选条件：单元（数值单元号；空串=全部） */
const filterUnit = ref<number | "">("");
/** 搜索关键词 */
const search = ref("");
/** 违规下架tab — 审核员筛选 */
const filterModeratedBy = ref("");
/** 表格加载状态 */
const loading = ref(false);
/** 表格数据 */
const tableData = ref<ContentRow[]>([]);
/** 总记录数 */
const totalCount = ref(0);
/** 一次性拉取全量数据的条数上限 */
/** 表格勾选的行 */
const selectedRows = ref<ContentRow[]>([]);
/** el-table 组件引用 */
const contentTableRef = ref<any>(null);

// --- 审核 tab 变量 ---
/** 审核 tab 筛选条件 */
const moderationFilter = reactive<{
  status: string;
  moderatedBy: string;
  type: string;
  building: number | "";
  unit: number | "";
  search: string;
}>({
  status: "yellow",
  moderatedBy: "",
  type: "",
  building: "",
  unit: "",
  search: "",
});
/** 审核列表数据 */
const moderationList = ref<ModerationItemDTO[]>([]);
/** 审核列表勾选行 */
const moderationSelected = ref<ModerationItemDTO[]>([]);
/** 审核列表总记录数 */
const moderationTotal = ref(0);
/** 审核 tab 计数 badge（待人工复核数量） */
const moderationTabCount = ref(0);
/** 审核详情弹窗可见性 */
const moderationDialogVisible = ref(false);
/** 审核详情弹窗 idList — 待审核项 ID 列表（统一架构：链接点击仅1条，详细按钮多条） */
const moderationIdList = ref<{ id: number; type: string }[]>([]);
/** 审核详情弹窗 idList 当前位置索引 */
const moderationIdPos = ref(0);
/** 审核详情加载中 */
const moderationDetailLoading = ref(false);
/** 审核详情项（通过 getContentDetail API 实时获取的 ContentItemDTO） */
const moderationDetailItem = ref<ContentItemDTO | null>(null);

// 根据所选楼栋计算单元筛选选项（主筛选）
const filterUnitOptions = computed(() => {
  if (!filterBuilding.value) return [];
  const buildingId = communityStore.getBuildingId(filterBuilding.value);
  return buildingId ? communityStore.getUnits(buildingId) : [];
});

// 审核 tab 的单元筛选选项
const moderationUnitOptions = computed(() => {
  if (!moderationFilter.building) return [];
  const buildingId = communityStore.getBuildingId(moderationFilter.building);
  return buildingId ? communityStore.getUnits(buildingId) : [];
});

// 住户检索弹窗的单元筛选选项
const residentUnitOptions = computed(() => {
  if (!residentFilterBuilding.value) return [];
  const buildingId = communityStore.getBuildingId(residentFilterBuilding.value);
  return buildingId ? communityStore.getUnits(buildingId) : [];
});

function handleSelectionChange(rows: ContentRow[]): void {
  selectedRows.value = rows;
}

onMounted(() => {
  communityStore.fetchCommunityData();
  fetchContent();
  loadModerationCounts();
  loadModerationList();
});

/**
 * 解包后端的 Result 包装器，提取 data.data 字段。
 * 兼容 Spring Boot 统一响应格式 { code, data, message }。
 * @returns 解包后的业务数据，不存在时返回 null
 */
function unwrap<T>(response: AxiosResponse): T | null {
  return response?.data?.data !== undefined ? (response.data.data as T) : null;
}

/** 根据当前筛选条件拉取内容列表 */
async function fetchContent(): Promise<void> {
  loading.value = true;
  try {
    const params: ContentListParams = {
      status: STATUS_PARAM_MAP[activeTab.value],
      type: (filterType.value || undefined) as "idle" | "help" | undefined,
      building_no: filterBuilding.value || undefined,
      unit_no: filterUnit.value || undefined,
      search: search.value || undefined,
      ...(activeTab.value === "offline" && filterModeratedBy.value
        ? { moderatedBy: filterModeratedBy.value }
        : {}),
    };
    const res = await getContentList(params);
    const pageData = unwrap<{ content: ContentRow[] }>(res);
    tableData.value = pageData?.content || [];
    totalCount.value = tableData.value.length;
  } catch {
    ElMessage.error("加载内容失败");
    tableData.value = [];
  } finally {
    loading.value = false;
  }
}

/** 切换标签页，重置筛选并重新加载 */
function switchTab(key: string): void {
  activeTab.value = key;
  selectedRows.value = [];
  moderationSelected.value = [];
  if (contentTableRef.value) {
    contentTableRef.value.clearSelection();
  }
  // 关闭所有弹窗
  detailVisible.value = false;
  moderationDialogVisible.value = false;
  offlineVisible.value = false;
  showApproveConfirm.value = false;
  if (key === "moderation") {
    loadModerationList();
  } else {
    fetchContent();
  }
  // 刷新审核计数 badge
  loadModerationCounts();
}

/** 搜索输入防抖计时器 */
let searchDebounceTimer: ReturnType<typeof setTimeout> | null = null;
let moderationSearchDebounceTimer: ReturnType<typeof setTimeout> | null = null;
/** 上次搜索的关键词（避免重复搜索） */
let lastSearchedKeyword = "";
let lastModerationSearchedKeyword = "";

/** 常规 tab 搜索输入防抖（500ms 后自动搜索，空白不搜） */
function onSearchInput(): void {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer);
  const kw = search.value.replace(/\s+/g, "");
  // 空白不变时跳过
  if (kw === lastSearchedKeyword) return;
  if (!kw && !lastSearchedKeyword) return;
  searchDebounceTimer = setTimeout(() => {
    const trimmed = search.value.replace(/\s+/g, "");
    if (trimmed === lastSearchedKeyword) return;
    lastSearchedKeyword = trimmed;
    applyFilters();
  }, 1000);
}

/** 常规 tab 搜索框回车：关键词未变化时跳过，避免空内容触发的无意义重载 */
function onSearchEnter(): void {
  const trimmed = search.value.replace(/\s+/g, "");
  if (trimmed === lastSearchedKeyword) return;
  lastSearchedKeyword = trimmed;
  fetchContent();
}

/** 审核 tab 搜索输入防抖（500ms 后自动搜索，空白不搜） */
function onModerationSearchInput(): void {
  if (moderationSearchDebounceTimer)
    clearTimeout(moderationSearchDebounceTimer);
  const kw = moderationFilter.search.replace(/\s+/g, "");
  if (kw === lastModerationSearchedKeyword) return;
  if (!kw && !lastModerationSearchedKeyword) return;
  moderationSearchDebounceTimer = setTimeout(() => {
    const trimmed = moderationFilter.search.replace(/\s+/g, "");
    if (trimmed === lastModerationSearchedKeyword) return;
    lastModerationSearchedKeyword = trimmed;
    applyModerationFilters();
  }, 1000);
}

/** 审核 tab 搜索框回车：关键词未变化时跳过，避免空内容触发的无意义重载 */
function onModerationSearchEnter(): void {
  const trimmed = moderationFilter.search.replace(/\s+/g, "");
  if (trimmed === lastModerationSearchedKeyword) return;
  lastModerationSearchedKeyword = trimmed;
  loadModerationList();
}

/** 应用筛选条件并重新加载 */
function applyFilters(): void {
  search.value = search.value.replace(/\s+/g, "");
  lastSearchedKeyword = search.value;
  fetchContent();
}

// ============================================================
// 审核 tab 相关方法
// ============================================================

/** 加载审核列表 */
async function loadModerationList(): Promise<void> {
  loading.value = true;
  try {
    const params: ModerationListParams = {
      status: "moderation",
    };
    if (moderationFilter.status)
      params.moderationStatus = moderationFilter.status;
    if (moderationFilter.moderatedBy)
      params.moderatedBy = moderationFilter.moderatedBy;
    if (moderationFilter.type) params.type = moderationFilter.type;
    if (moderationFilter.building) params.building_no = moderationFilter.building;
    if (moderationFilter.unit) params.unit_no = moderationFilter.unit;
    if (moderationFilter.search) params.search = moderationFilter.search;
    const res = await getModerationList(params);
    const pageData = unwrap<{ content: ModerationItemDTO[] }>(res);
    moderationList.value = pageData?.content || [];
    moderationTotal.value = moderationList.value.length;
  } catch {
    ElMessage.error("加载审核列表失败");
    moderationList.value = [];
  } finally {
    loading.value = false;
  }
}

/** 应用审核筛选条件并重新加载 */
function applyModerationFilters(): void {
  moderationFilter.search = moderationFilter.search.replace(/\s+/g, "");
  lastModerationSearchedKeyword = moderationFilter.search;
  loadModerationList();
}

/** 审核列表勾选变更 */
function onModerationSelect(rows: ModerationItemDTO[]): void {
  moderationSelected.value = rows;
}

/** 发布类型标签映射 */
function postTypeLabel(postType: string): string {
  const map: Record<string, string> = {
    LEND: "闲置借出",
    WANTED: "需求借入",
    HELP: "技能求助",
  };
  return map[postType] || postType;
}

/** 审核等级中文标签（green/reviewed→审核通过，yellow→待人工复核，其余→审核驳回） */
function moderationLevelLabel(status?: string): string {
  if (status === "green" || status === "reviewed") return "审核通过";
  if (status === "yellow") return "待人工复核";
  return "审核驳回";
}

/** 审核行背景色 */
function getModerationRowClass({ row }: { row: ModerationItemDTO }): string {
  return "";
}

/** 加载审核 tab badge 计数（待人工复核数量） */
async function loadModerationCounts(): Promise<void> {
  try {
    const res = await getModerationCounts();
    const data = unwrap<ModerationCounts>(res);
    moderationTabCount.value = data?.yellow ?? 0;
  } catch {
    moderationTabCount.value = 0;
  }
}

/** 审核详情弹窗 — 实时请求详情（共用 fetch 函数） */
async function fetchModerationDetail(id: number, type: string): Promise<void> {
  moderationDetailLoading.value = true;
  moderationDetailItem.value = null;
  try {
    const res = await getContentDetail(id, type as "idle" | "help");
    moderationDetailItem.value = unwrap<ContentItemDTO>(res);
  } catch {
    ElMessage.error("加载审核详情失败");
    // 自动跳过当前项
    moderationIdList.value.splice(moderationIdPos.value, 1);
    if (moderationIdList.value.length > 0) {
      moderationIdPos.value = Math.min(
        moderationIdPos.value,
        moderationIdList.value.length - 1,
      );
      fetchModerationDetail(
        moderationIdList.value[moderationIdPos.value].id,
        moderationIdList.value[moderationIdPos.value].type,
      );
    } else {
      moderationDialogVisible.value = false;
    }
  } finally {
    moderationDetailLoading.value = false;
  }
}

/** 从选中项打开审核详情弹窗 */
function openModerationDetail(): void {
  if (!moderationSelected.value.length) return;
  moderationIdList.value = moderationSelected.value.map((r) => ({
    id: r.id,
    type: r.type,
  }));
  moderationIdPos.value = 0;
  fetchModerationDetail(
    moderationIdList.value[0].id,
    moderationIdList.value[0].type,
  );
  moderationDialogVisible.value = true;
}

/** 点击标题链接查看审核详情 */
function viewModerationDetail(row: ModerationItemDTO): void {
  moderationIdList.value = [{ id: row.id, type: row.type }];
  moderationIdPos.value = 0;
  fetchModerationDetail(row.id, row.type);
  moderationDialogVisible.value = true;
}

/** 审核详情弹窗：上一项 */
function prevModerationItem(): void {
  if (moderationIdPos.value <= 0) return;
  moderationIdPos.value--;
  const item = moderationIdList.value[moderationIdPos.value];
  fetchModerationDetail(item.id, item.type);
}

/** 审核详情弹窗：下一项 */
function nextModerationItem(): void {
  if (moderationIdPos.value >= moderationIdList.value.length - 1) return;
  moderationIdPos.value++;
  const item = moderationIdList.value[moderationIdPos.value];
  fetchModerationDetail(item.id, item.type);
}

/** 审核通过确认弹窗开关 */
const showApproveConfirm = ref(false);

/** 审核通过 — 确认弹窗中点击确认 */
async function doApproveModeration(): Promise<void> {
  if (!moderationDetailItem.value) return;
  const item = moderationDetailItem.value;
  try {
    await approveContent(item.id, item.type, detailUpdatedAt.value);
    ElMessage.success("审核已通过");
    showApproveConfirm.value = false;
    // 刷新列表和计数
    await loadModerationList();
    loadModerationCounts();
    // 从 idList 移除已处理项，跳到下一条
    moderationIdList.value.splice(moderationIdPos.value, 1);
    if (moderationIdList.value.length > 0) {
      moderationIdPos.value = Math.min(
        moderationIdPos.value,
        moderationIdList.value.length - 1,
      );
      fetchModerationDetail(
        moderationIdList.value[moderationIdPos.value].id,
        moderationIdList.value[moderationIdPos.value].type,
      );
    } else {
      moderationDialogVisible.value = false;
    }
  } catch {
    showApproveConfirm.value = false;
    ElMessage.error("审核通过失败");
    // 冲突/异常：关闭弹窗，刷新列表
    moderationDialogVisible.value = false;
    loadModerationList();
    loadModerationCounts();
  }
}

/** 审核驳回：打开违规下架弹窗 */
function rejectModeration(): void {
  if (!moderationDetailItem.value) return;
  const item = moderationDetailItem.value;
  moderationDialogVisible.value = false;
  offlineFromDetailFlag.value = true;
  offlineIsModeration.value = true;
  offlineTarget.value = {
    id: item.id,
    type: item.type,
    title: item.title,
  } as ContentRow;
  offlineReasons.value = [];
  offlineCustomReason.value = "";
  offlineVisible.value = true;
}

/** 从审核详情下架（已通过但需违规下架的场景） */
function offlineFromModerationDetail(): void {
  if (!moderationDetailItem.value) return;
  const item = moderationDetailItem.value;
  moderationDialogVisible.value = false;
  offlineFromDetailFlag.value = true;
  offlineIsModeration.value = true;
  offlineTarget.value = {
    id: item.id,
    type: item.type,
    title: item.title,
  } as ContentRow;
  offlineReasons.value = [];
  offlineCustomReason.value = "";
  offlineVisible.value = true;
}

/** 审核详情中有效的图片 URL 列表 */
const validModerationImages = computed(() => {
  if (!moderationDetailItem.value?.images) return [];
  return (moderationDetailItem.value.images as string[]).filter(
    (url: string) => url && typeof url === "string" && url.trim().length > 0,
  );
});

/** 格式化审核详情中的发布时间 */
function formatModerationTime(ts?: string): string {
  if (!ts) return "";
  return formatTime(ts);
}

/** 将后端状态映射为标签 CSS 类名 */
function statusTag(status: string): string {
  const label = statusLabel(status);
  const map: Record<string, string> = {
    在线中: "tag tag-green",
    待审批: "tag tag-orange",
    进行中: "tag tag-blue",
    已完成: "tag tag-gray",
    已下架: "tag tag-red",
  };
  return map[label] || "tag tag-gray";
}

function statusLabel(status: string): string {
  return status === "展示中" ? "在线中" : status;
}

/** 格式化 ISO 时间为可读格式，undefined/null 返回空字符串 */
function formatTime(ts?: string): string {
  if (!ts) return "";
  const d = new Date(ts);
  const pad = (n: number): string => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 格式化发布者显示名，空名或纯英文名显示为"——" */
function displayName(name: string): string {
  if (!name) return "——";
  if (/^[\x00-\x7F\s]+$/.test(name)) return "——";
  return name;
}

// --- 详情弹窗 ---
const detailVisible = ref(false);
const detailLoading = ref(false);
/** 详情数据（来自 getContentDetail API 的原始 DTO） */
const detailItem = ref<ContentItemDTO | null>(null);

/** 详情中经过有效性过滤的图片 URL 列表 */
const validDetailImages = computed(() => {
  if (!detailItem.value?.images) return [];
  return (detailItem.value.images as string[]).filter(
    (url: string) => url && typeof url === "string" && url.trim().length > 0,
  );
});

/** 记录加载失败的图片索引，用于显示占位图 */
const failedImageIndexes = ref(new Set<number>());

/** 标记指定索引的图片加载失败 */
function onImageError(index: number): void {
  failedImageIndexes.value.add(index);
}

/** 详情弹窗 idList — 待查看项 ID 列表（统一架构：链接点击仅1条，详细按钮多条） */
const detailIdList = ref<{ id: number; type: string }[]>([]);
/** 详情弹窗 idList 当前位置索引 */
const detailIdPos = ref(0);
/** 详情弹窗 — 乐观锁：打开弹窗时记录的 updatedAt，用于操作时版本冲突检测 */
const detailUpdatedAt = ref<string | undefined>(undefined);

/** 请求序号（防竞态），每次 fetchDetail 调用递增 */
let detailRequestSeq = 0;

/** 详情弹窗 — 实时请求详情 */
async function fetchDetail(id: number, type: string): Promise<void> {
  const seq = ++detailRequestSeq;
  detailLoading.value = true;
  detailItem.value = null;
  failedImageIndexes.value = new Set();
  try {
    const res = await getContentDetail(id, type as "idle" | "help");
    // 竞态检查：只处理最新请求的响应
    if (seq !== detailRequestSeq) return;
    detailItem.value = unwrap<ContentItemDTO>(res);
    detailUpdatedAt.value = detailItem.value?.updatedAt;
  } catch {
    if (seq !== detailRequestSeq) return;
    ElMessage.error("加载详情失败");
    // 自动跳过当前项
    detailIdList.value.splice(detailIdPos.value, 1);
    if (detailIdList.value.length > 0) {
      detailIdPos.value = Math.min(
        detailIdPos.value,
        detailIdList.value.length - 1,
      );
      fetchDetail(
        detailIdList.value[detailIdPos.value].id,
        detailIdList.value[detailIdPos.value].type,
      );
    } else {
      detailVisible.value = false;
    }
  } finally {
    if (seq === detailRequestSeq) {
      detailLoading.value = false;
    }
  }
}

/** 点击标题链接打开详情弹窗 — idList 仅当前项 */
function openDetailById(row: ContentRow): void {
  detailIdList.value = [{ id: row.id, type: row.type }];
  detailIdPos.value = 0;
  fetchDetail(row.id, row.type);
  detailVisible.value = true;
}

/** 从勾选的记录中打开详情弹窗 — idList 为全部勾选项 */
function openDetailFromSelection(): void {
  if (!selectedRows.value.length) return;
  detailIdList.value = selectedRows.value.map((r) => ({
    id: r.id,
    type: r.type,
  }));
  detailIdPos.value = 0;
  fetchDetail(detailIdList.value[0].id, detailIdList.value[0].type);
  detailVisible.value = true;
}

/** 详情弹窗左右翻页 */
function detailGo(delta: number): void {
  const next = detailIdPos.value + delta;
  if (next < 0 || next >= detailIdList.value.length) return;
  detailIdPos.value = next;
  const item = detailIdList.value[next];
  fetchDetail(item.id, item.type);
}

// --- 下架弹窗 ---
const offlineVisible = ref(false);
/** 待下架的目标行 */
const offlineTarget = ref<ContentRow | null>(null);
/** 选中的下架原因（多选） */
const offlineReasons = ref<string[]>([]);
/** 自定义补充原因 */
const offlineCustomReason = ref("");
/** 下架提交进行中 */
const offlineSubmitting = ref(false);
/** 预设下架原因选项 */
const OFFLINE_REASON_OPTIONS = ["商业广告", "虚假信息", "违规物品", "骚扰内容"];
/** 下架弹窗是否从详情弹窗打开（取消时需回退到详情而非列表） */
const offlineFromDetailFlag = ref(false);
/** 下架操作是否来自审核 tab（使用不同的 API 和 body 格式） */
const offlineIsModeration = ref(false);

/** 从列表行打开下架弹窗 */
function openOffline(row: ContentRow): void {
  offlineTarget.value = row;
  offlineReasons.value = [];
  offlineCustomReason.value = "";
  offlineIsModeration.value = false;
  offlineVisible.value = true;
}

/** 从详情弹窗中触发下架 */
function offlineFromDetail(): void {
  if (!detailItem.value) return;
  detailVisible.value = false;
  offlineFromDetailFlag.value = true;
  openOffline(detailItem.value as unknown as ContentRow);
}

/** 取消下架：若从详情弹窗进入则回退到详情弹窗并刷新数据，否则回到列表 */
function cancelOffline(): void {
  offlineVisible.value = false;
  if (offlineFromDetailFlag.value) {
    offlineFromDetailFlag.value = false;
    const wasModeration = offlineIsModeration.value;
    offlineIsModeration.value = false;
    if (wasModeration) {
      // 回到审核弹窗前刷新当前项数据
      const cur = moderationIdList.value[moderationIdPos.value];
      if (cur) fetchModerationDetail(cur.id, cur.type);
      moderationDialogVisible.value = true;
    } else {
      // 回到详情弹窗前刷新当前项数据
      const cur = detailIdList.value[detailIdPos.value];
      if (cur) fetchDetail(cur.id, cur.type);
      detailVisible.value = true;
    }
  }
}

/** 提交下架请求 */
async function doOffline(): Promise<void> {
  if (!offlineTarget.value) return;
  // 必须至少选择一个下架原因或填写自定义原因
  if (!offlineReasons.value.length && !offlineCustomReason.value.trim()) {
    ElMessage.warning("请至少选择一个下架原因或填写自定义原因");
    return;
  }
  offlineSubmitting.value = true;
  try {
    const isModeration = offlineIsModeration.value;
    const body = {
      targetType: offlineTarget.value.type,
      reasons: [...offlineReasons.value],
      customReason: offlineCustomReason.value.trim() || undefined,
      updatedAt: detailUpdatedAt.value,
      ...(isModeration ? { fromModeration: true } : {}),
    };
    if (isModeration) {
      await offlineModerationContent(offlineTarget.value.id, body);
    } else {
      await offlineContent(offlineTarget.value.id, body);
    }
    offlineVisible.value = false;
    offlineFromDetailFlag.value = false;
    offlineIsModeration.value = false;
    ElMessage.success(
      `「${offlineTarget.value.title}」已下架，通知已发送给发布者`,
    );
    if (isModeration) {
      await loadModerationList();
      loadModerationCounts();
      // 从 idList 移除已驳回项，跳到下一条
      moderationIdList.value.splice(moderationIdPos.value, 1);
      if (moderationIdList.value.length > 0) {
        moderationIdPos.value = Math.min(
          moderationIdPos.value,
          moderationIdList.value.length - 1,
        );
        fetchModerationDetail(
          moderationIdList.value[moderationIdPos.value].id,
          moderationIdList.value[moderationIdPos.value].type,
        );
        // 延迟打开审核弹窗，避免与离线弹窗关闭动画重叠
        await nextTick();
        moderationDialogVisible.value = true;
      } else {
        moderationDialogVisible.value = false;
      }
    } else {
      // 常规 tab：从 idList 移除，跳到下一条
      detailIdList.value.splice(detailIdPos.value, 1);
      if (detailIdList.value.length > 0) {
        detailIdPos.value = Math.min(
          detailIdPos.value,
          detailIdList.value.length - 1,
        );
        fetchDetail(
          detailIdList.value[detailIdPos.value].id,
          detailIdList.value[detailIdPos.value].type,
        );
        // 延迟打开详情弹窗
        await nextTick();
        detailVisible.value = true;
      }
      fetchContent();
    }
  } catch {
    ElMessage.error("下架失败");
    // 冲突/异常：关闭弹窗，刷新列表
    const wasModeration = offlineIsModeration.value;
    offlineVisible.value = false;
    offlineFromDetailFlag.value = false;
    offlineIsModeration.value = false;
    if (wasModeration) {
      moderationDialogVisible.value = false;
      loadModerationList();
      loadModerationCounts();
    } else {
      detailVisible.value = false;
      fetchContent();
    }
  } finally {
    offlineSubmitting.value = false;
  }
}

// --- 代发弹窗 ---
/** 代发是否可见 */
const publishVisible = ref(false);
/** 代发模式：'lend'（闲置借出）| 'wanted'（需求借入）| 'help'（技能求助） */
const publishMode = ref<string>("lend");
/** 代发提交中 */
const publishSubmitting = ref(false);
/** 上传进行中 */
const publishUploading = ref(false);
/** 代发表单数据 */
const publishForm = reactive<PublishForm>({
  title: "",
  desc: "",
  price: "",
  category: "",
  customCategory: "",
  durationUnit: "day",
  durationValue: 7,
  urgency: "一般",
  startTime: "",
  endTime: "",
  pickupMethod: PICKUP_METHOD.SELF_PICKUP,
  condition: ITEM_CONDITION.NORMAL,
  enableTimeRange: false,
  images: [],
});
/** 选中的住户名称（展示用） */
const selectedResident = ref("");
/** 选中的住户 ID（提交时用） */
const selectedResidentId = ref<number | null>(null);

/** 空闲物品类型选项（pill 按钮） */
const IDLE_CATEGORIES = [
  "工具",
  "电子产品",
  "书籍",
  "家居",
  "运动",
  "玩具",
  "服饰",
  "其他",
];
/** 求助类型选项（pill 按钮） */
const HELP_CATEGORIES = [
  "维修",
  "陪护",
  "代取",
  "搬运",
  "辅导",
  "遛宠",
  "烹饪",
  "其他",
];

/** 时长选项（天） */
const DURATION_DAY_OPTIONS = [1, 2, 3, 4, 5, 6, 7];
/** 时长选项（小时） */
const DURATION_HOUR_OPTIONS = Array.from({ length: 23 }, (_, i) => i + 1);

/** 打开代发弹窗并重置表单 */
function openPublish(): void {
  publishMode.value = "lend";
  publishForm.title = "";
  publishForm.desc = "";
  publishForm.price = "";
  publishForm.category = "";
  publishForm.customCategory = "";
  publishForm.durationUnit = "day";
  publishForm.durationValue = 7;
  publishForm.urgency = "一般";
  publishForm.startTime = "";
  publishForm.endTime = "";
  publishForm.pickupMethod = PICKUP_METHOD.SELF_PICKUP;
  publishForm.condition = ITEM_CONDITION.NORMAL;
  publishForm.enableTimeRange = false;
  publishForm.images = [];
  selectedResident.value = "";
  selectedResidentId.value = null;
  publishVisible.value = true;
}

/** 切换代发模式时重置分类相关字段 */
function switchPublishMode(mode: string): void {
  publishMode.value = mode;
  publishForm.category = "";
  publishForm.customCategory = "";
  publishForm.title = "";
  publishForm.desc = "";
  publishForm.price = "";
  publishForm.images = [];
}

/** 选择图片上传 */
function handlePublishImageUpload(): void {
  const input = document.createElement("input");
  input.type = "file";
  input.accept = "image/*";
  input.multiple = true;
  input.onchange = async () => {
    const files = input.files;
    if (!files || files.length === 0) return;
    const remaining = 4 - publishForm.images.length;
    if (remaining <= 0) {
      ElMessage.warning("最多上传 4 张图片");
      return;
    }
    const toUpload = Array.from(files).slice(0, remaining);
    publishUploading.value = true;
    try {
      for (const file of toUpload) {
        const res = await upload("/api/common/upload", file);
        const url = res?.data?.data as unknown as string;
        if (url) {
          publishForm.images.push(url);
        }
      }
    } catch {
      ElMessage.error("图片上传失败");
    } finally {
      publishUploading.value = false;
    }
  };
  input.click();
}

/** 删除已上传的图片 */
function removePublishImage(index: number): void {
  publishForm.images.splice(index, 1);
}

/** 获取最终分类值（含自定义输入） */
function getFinalCategory(): string {
  if (publishForm.category === "其他" && publishForm.customCategory.trim()) {
    return publishForm.customCategory.trim();
  }
  return publishForm.category;
}

/** 组装图片 JSON 字符串 */
function getImagesJson(): string {
  return publishForm.images.length > 0
    ? JSON.stringify(publishForm.images)
    : "";
}

/** 提交代发请求 */
async function submitPublish(): Promise<void> {
  if (!selectedResidentId.value) {
    ElMessage.warning("请选择目标住户");
    return;
  }
  if (!publishForm.title.trim()) {
    ElMessage.warning(
      publishMode.value === "help" ? "请输入求助标题" : "请输入物品标题/名称",
    );
    return;
  }
  if (!publishForm.category) {
    ElMessage.warning(
      publishMode.value === "help" ? "请选择求助类型" : "请选择物品类型",
    );
    return;
  }
  if (publishForm.category === "其他" && !publishForm.customCategory.trim()) {
    ElMessage.warning("请手动输入类型");
    return;
  }
  // 闲置借出额外校验
  if (publishMode.value === "lend") {
    if (!publishForm.price || !publishForm.price.trim()) {
      ElMessage.warning("请填写参考价格");
      return;
    }
    if (!publishForm.condition) {
      ElMessage.warning("请选择物品状况");
      return;
    }
  }
  // 需求借入额外校验
  if (publishMode.value === "wanted") {
    if (!publishForm.desc.trim()) {
      ElMessage.warning("请填写用途说明");
      return;
    }
  }
  // 技能求助额外校验
  if (publishMode.value === "help") {
    if (!publishForm.desc.trim()) {
      ElMessage.warning("请填写详细描述");
      return;
    }
  }

  publishSubmitting.value = true;
  try {
    if (publishMode.value === "lend" || publishMode.value === "wanted") {
      await publishIdle({
        userId: selectedResidentId.value,
        isProxy: true,
        postType:
          publishMode.value === "lend" ? POST_TYPE.LEND : POST_TYPE.WANTED,
        title: publishForm.title.trim(),
        description: publishForm.desc.trim(),
        category: getFinalCategory(),
        price: Number.parseFloat(publishForm.price) || undefined,
        condition:
          publishMode.value === "lend" ? publishForm.condition : undefined,
        maxDuration: publishForm.durationValue,
        durationUnit: publishForm.durationUnit,
        pickupMethod:
          publishMode.value === "lend" ? publishForm.pickupMethod : undefined,
        images: getImagesJson() || undefined,
      });
    } else {
      await publishHelp({
        userId: selectedResidentId.value,
        isProxy: true,
        title: publishForm.title.trim(),
        description: publishForm.desc.trim(),
        category: getFinalCategory(),
        isUrgent: publishForm.urgency === "紧急",
        timeStart: publishForm.enableTimeRange
          ? publishForm.startTime || undefined
          : undefined,
        timeEnd: publishForm.enableTimeRange
          ? publishForm.endTime || undefined
          : undefined,
        images: getImagesJson() || undefined,
      });
    }
    publishVisible.value = false;
    ElMessage.success("代发内容已发布");
    fetchContent();
  } catch {
    ElMessage.error("代发失败");
  } finally {
    publishSubmitting.value = false;
  }
}

// 住户检索
const residentVisible = ref(false);
const tempSelected = ref<number | null>(null);
const residentList = ref<any[]>([]);
const loadingResidents = ref(false);
const residentFilterType = ref("");
const residentFilterBuilding = ref<number | "">("");
const residentFilterUnit = ref<number | "">("");
const residentKeyword = ref("");

/**
 * 加载住户列表（支持筛选）。
 * 从后端分页拉取住户数据，供代发弹窗中选择目标住户使用。
 */
async function loadAllResidents(): Promise<void> {
  if (loadingResidents.value) return;
  loadingResidents.value = true;
  try {
    const params = {
      userType: residentFilterType.value || undefined,
      building_no: residentFilterBuilding.value || undefined,
      unit_no: residentFilterUnit.value || undefined,
      keyword: residentKeyword.value || undefined,
    };
    const res = await searchResidents(params);
    const data = unwrap<{ content: unknown[] }>(res);
    residentList.value = data?.content ? data.content : [];
  } catch (e) {
    residentList.value = [];
  } finally {
    loadingResidents.value = false;
  }
}

/** 上次住户搜索关键词，用于 Enter 键去重 */
let lastResidentKeyword = "";

/** 住户搜索框回车：关键词未变化时跳过，避免空内容触发的无意义重载 */
function onResidentSearchEnter(): void {
  const trimmed = residentKeyword.value.replace(/\s+/g, "");
  if (trimmed === lastResidentKeyword) return;
  lastResidentKeyword = trimmed;
  loadAllResidents();
}

/** 打开住户检索弹窗并加载列表 */
function openResidentSearch(): void {
  tempSelected.value = selectedResidentId.value;
  residentFilterType.value = "";
  residentFilterBuilding.value = "";
  residentFilterUnit.value = "";
  residentKeyword.value = "";
  lastResidentKeyword = "";
  residentVisible.value = true;
  loadAllResidents();
}

/** 住户行数据（来自 searchResidents API） */
interface ResidentRow {
  id: number;
  name: string;
  room: string;
  userType: string;
}

/**
 * 在住户检索弹窗中临时选中某住户。
 * @param r - 住户行数据
 */
function pickResident(r: ResidentRow): void {
  tempSelected.value = r.id;
  selectedResident.value = `${r.room}(${r.userType}) - ${displayName(r.name)}`;
}

/** 确认选择住户，关闭检索弹窗 */
function selectResident(): void {
  if (tempSelected.value) selectedResidentId.value = tempSelected.value;
  residentVisible.value = false;
}
</script>

<style scoped>
.tag {
  display: inline-flex;
  align-items: center;
  height: 20px;
  padding: 0 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
}
.tag-blue {
  background: rgba(0, 113, 227, 0.1);
  color: var(--accent);
}
.tag-red {
  background: rgba(255, 59, 48, 0.1);
  color: var(--red);
}
.tag-orange {
  background: rgba(255, 149, 0, 0.1);
  color: var(--orange);
}
.tag-green {
  background: rgba(52, 199, 89, 0.1);
  color: var(--green);
}
.tag-gray {
  background: var(--bg);
  color: var(--text-secondary);
}
.title-link {
  color: var(--accent);
  cursor: pointer;
  text-decoration: none;
}
.title-link:hover {
  text-decoration: underline;
}
.panel-empty {
  padding: 48px 20px;
  text-align: center;
  font-size: 13px;
  color: var(--text-tertiary);
}
:deep(.el-dialog__body) {
  padding-top: 0;
}
/* 发布者是第3列 */
:deep(.el-table__body tr td:nth-child(3)) {
  padding-left: 70px !important;
}
:deep(.el-table__header tr th:nth-child(3)) {
  padding-left: 70px !important;
}

/* 审核列表标题链接（无下划线） */
.moderation-title-link {
  color: var(--accent);
  cursor: pointer;
  text-decoration: none;
}

/* tab badge 计数 */
.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--orange);
  color: #fff;
  font-size: 11px;
  margin-left: 4px;
}
</style>
