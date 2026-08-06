<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  BookOpen, Upload, MessageCircle, ShieldCheck,
  Activity, FileText, Zap, ArrowRight,
  GitPullRequest, Eye, CheckCircle2, XCircle,
  History, Layers, Settings, TrendingUp
} from 'lucide-vue-next'
import { getPageStats } from '@/api/wiki'
import { countSources } from '@/api/source'
import {
  getTokenUsageByType, getSchemaGovernanceHealth, getTokenUsageTrend,
  type TokenUsageInfo, type SchemaGovernanceHealth, type TokenUsageTrendItem
} from '@/api/harness'

const { t } = useI18n()

const entityCount = ref(0)
const sourceCount = ref(0)
const healthyCount = ref(0)
const needsUpdateCount = ref(0)
const hasProblemsCount = ref(0)
const unknownCount = ref(0)

const token = ref<TokenUsageInfo>({
  totalTokens: 0, ingestTokens: 0, queryTokens: 0, lintTokens: 0,
  budget: 0, remaining: 0, usagePercent: 0, alertLevel: 'normal'
})
const tokenLoading = ref(true)

const trendData = ref<TokenUsageTrendItem[]>([])
const trendLoading = ref(true)

const gov = ref<SchemaGovernanceHealth | null>(null)
const govLoading = ref(true)

const healthScore = computed(() => {
  const total = healthyCount.value + needsUpdateCount.value + hasProblemsCount.value + unknownCount.value
  if (total === 0) return '--'
  const score = Math.round((healthyCount.value / total) * 100)
  return score + '%'
})

const tokenPercent = computed(() => {
  if (!token.value.budget) return 0
  return Math.min(100, (token.value.totalTokens / token.value.budget) * 100)
})

const tokenBarColor = computed(() => {
  if (tokenPercent.value >= 90) return 'var(--error)'
  if (tokenPercent.value >= 70) return 'var(--warning)'
  return 'var(--accent-primary)'
})

const tokenAlertLabel = computed(() => {
  if (token.value.alertLevel === 'exceeded') return t('dashboard.alertExceeded')
  if (token.value.alertLevel === 'warning') return t('dashboard.alertWarning')
  return ''
})

const stats = computed(() => [
  { icon: BookOpen, labelKey: 'dashboard.statEntities', value: String(entityCount.value), color: 'var(--accent-primary)' },
  { icon: FileText, labelKey: 'dashboard.statSources', value: String(sourceCount.value), color: 'var(--success)' },
  { icon: ShieldCheck, labelKey: 'dashboard.statHealth', value: healthScore.value, color: 'var(--info)' },
  { icon: GitPullRequest, labelKey: 'dashboard.statPending', value: String(gov.value?.pendingCount ?? 0), color: 'var(--warning)' },
])

const quickActions = [
  { icon: Upload, labelKey: 'dashboard.actionAddSource', path: '/ingest', color: 'var(--accent-primary)' },
  { icon: MessageCircle, labelKey: 'dashboard.actionQuery', path: '/search', color: 'var(--info)' },
  { icon: ShieldCheck, labelKey: 'dashboard.actionLint', path: '/lint', color: 'var(--warning)' },
]

const govCards = computed(() => {
  const g = gov.value
  return [
    { icon: GitPullRequest, labelKey: 'dashboard.govPending', value: g?.pendingCount ?? 0, color: 'var(--warning)', hintKey: 'dashboard.govPendingHint' },
    { icon: Eye, labelKey: 'dashboard.govObserving', value: g?.observingCount ?? 0, color: 'var(--info)', hintKey: 'dashboard.govObservingHint' },
    { icon: CheckCircle2, labelKey: 'dashboard.govAccepted', value: g?.acceptedCount ?? 0, color: 'var(--success)', hintKey: 'dashboard.govAcceptedHint' },
    { icon: XCircle, labelKey: 'dashboard.govRejected', value: g?.rejectedCount ?? 0, color: 'var(--text-tertiary)', hintKey: 'dashboard.govRejectedHint' },
    { icon: History, labelKey: 'dashboard.govIgnored', value: g?.ignoredCount ?? 0, color: 'var(--text-tertiary)', hintKey: 'dashboard.govIgnoredHint' },
    { icon: Layers, labelKey: 'dashboard.govSuperseded', value: g?.supersededCount ?? 0, color: 'var(--text-tertiary)', hintKey: 'dashboard.govSupersededHint' },
  ]
})

const matchRate = computed(() => {
  const g = gov.value
  if (!g || !g.gatekeeperEvaluated) return null
  return Math.round(Number(g.gatekeeperMatchRate ?? 0) * 100)
})

const matchRateColor = computed(() => {
  const r = matchRate.value
  if (r === null) return 'var(--text-tertiary)'
  if (r >= 80) return 'var(--success)'
  if (r >= 60) return 'var(--info)'
  if (r >= 40) return 'var(--warning)'
  return 'var(--error)'
})

const migrationSummary = computed(() => {
  const g = gov.value
  if (!g) return null
  const out = g.migrationOutdatedCount ?? 0
  const un = g.migrationUntaggedCount ?? 0
  return {
    version: g.currentVersionNumber ?? 0,
    outdated: out,
    untagged: un,
    stale: out + un,
  }
})

const trendSeries = computed(() => {
  const colors: Record<string, string> = {
    ingest: 'var(--accent-primary)',
    query: 'var(--info)',
    lint: 'var(--warning)',
    schema: 'var(--success)',
    modify: 'var(--error)',
  }
  const labelKeys: Record<string, string> = {
    ingest: 'dashboard.trendIngest',
    query: 'dashboard.trendQuery',
    lint: 'dashboard.trendLint',
    schema: 'dashboard.trendSchema',
    modify: 'dashboard.trendModify',
  }
  const keys: (keyof TokenUsageTrendItem)[] = ['ingestTokens', 'queryTokens', 'lintTokens', 'schemaTokens', 'modifyTokens']
  const nameKeys = ['ingest', 'query', 'lint', 'schema', 'modify']
  return nameKeys.map((name, i) => ({
    name,
    labelKey: labelKeys[name],
    color: colors[name],
    data: trendData.value.map(d => (d[keys[i]] as number) || 0),
  }))
})

const trendMax = computed(() => {
  let max = 0
  for (const s of trendSeries.value) {
    for (const v of s.data) {
      if (v > max) max = v
    }
  }
  return max || 1
})

const trendDates = computed(() => trendData.value.map(d => d.date.slice(5))) // MM-DD

const trendHoverIndex = ref<number | null>(null)

const trendPointX = (i: number) => {
  const px = 40, chartW = 480
  return px + (trendData.value.length > 1 ? (i / (trendData.value.length - 1)) * chartW : chartW / 2)
}

const trendPointY = (v: number) => {
  const py = 10, chartH = 150
  return py + chartH - (v / trendMax.value) * chartH
}

function trendPath(data: number[]): string {
  if (data.length === 0) return ''
  const w = 560
  const h = 160
  const px = 40
  const py = 10
  const chartW = w - px * 2
  const chartH = h - py * 2
  const max = trendMax.value
  const points = data.map((v, i) => {
    const x = px + (data.length > 1 ? (i / (data.length - 1)) * chartW : chartW / 2)
    const y = py + chartH - (v / max) * chartH
    return `${x},${y}`
  })
  return 'M' + points.join(' L')
}

onMounted(async () => {
  try {
    const stats = await getPageStats()
    entityCount.value = stats.entityCount
    healthyCount.value = stats.healthyCount
    needsUpdateCount.value = stats.needsUpdateCount
    hasProblemsCount.value = stats.hasProblemsCount
    unknownCount.value = stats.unknownCount
  } catch (e) { console.error(e) }
  try {
    const result = await countSources()
    sourceCount.value = result.count
  } catch (e) { console.error(e) }
  try {
    token.value = await getTokenUsageByType()
  } catch (e) { console.error(e) }
  finally { tokenLoading.value = false }
  try {
    trendData.value = await getTokenUsageTrend(30)
  } catch (e) { console.error(e) }
  finally { trendLoading.value = false }
  try {
    gov.value = await getSchemaGovernanceHealth()
  } catch (e) { console.error(e) }
  finally { govLoading.value = false }
})
</script>

<template>
  <div class="dashboard">
    <h1 class="dashboard__title">{{ t('dashboard.pageTitle') }}</h1>

    <div class="dashboard__stats">
      <div v-for="stat in stats" :key="stat.labelKey" class="dashboard__stat-card">
        <component :is="stat.icon" :size="24" :style="{ color: stat.color }" />
        <div class="dashboard__stat-info">
          <span class="dashboard__stat-value tabular-nums">{{ stat.value }}</span>
          <span class="dashboard__stat-label">{{ t(stat.labelKey) }}</span>
        </div>
      </div>
    </div>

    <!-- 健康度分布 -->
    <div class="dashboard__section">
      <h2 class="dashboard__section-title">
        <ShieldCheck :size="18" class="dashboard__section-icon" />
        {{ t('dashboard.healthDistribution') }}
      </h2>
      <div class="dashboard__health-bar">
        <div v-if="entityCount === 0" class="dashboard__health-empty">{{ t('dashboard.noPages') }}</div>
        <template v-else>
          <div
            v-if="healthyCount"
            class="dashboard__health-seg dashboard__health-seg--green"
            :style="{ flex: healthyCount }"
            :title="`${t('dashboard.healthHealthy')} ${healthyCount}`"
          >{{ healthyCount }}</div>
          <div
            v-if="needsUpdateCount"
            class="dashboard__health-seg dashboard__health-seg--yellow"
            :style="{ flex: needsUpdateCount }"
            :title="`${t('dashboard.healthNeedsUpdate')} ${needsUpdateCount}`"
          >{{ needsUpdateCount }}</div>
          <div
            v-if="hasProblemsCount"
            class="dashboard__health-seg dashboard__health-seg--red"
            :style="{ flex: hasProblemsCount }"
            :title="`${t('dashboard.healthHasProblems')} ${hasProblemsCount}`"
          >{{ hasProblemsCount }}</div>
          <div
            v-if="unknownCount"
            class="dashboard__health-seg dashboard__health-seg--gray"
            :style="{ flex: unknownCount }"
            :title="`${t('dashboard.healthUnknown')} ${unknownCount}`"
          >{{ unknownCount }}</div>
        </template>
      </div>
      <div class="dashboard__health-legend">
        <span><i class="dot dot-green"></i>{{ t('dashboard.healthHealthy') }} {{ healthyCount }}</span>
        <span><i class="dot dot-yellow"></i>{{ t('dashboard.healthNeedsUpdate') }} {{ needsUpdateCount }}</span>
        <span><i class="dot dot-red"></i>{{ t('dashboard.healthHasProblems') }} {{ hasProblemsCount }}</span>
        <span><i class="dot dot-gray"></i>{{ t('dashboard.healthUnknown') }} {{ unknownCount }}</span>
      </div>
    </div>

    <!-- Schema 共治健康 -->
    <div class="dashboard__section">
      <h2 class="dashboard__section-title">
        <GitPullRequest :size="18" class="dashboard__section-icon" />
        {{ t('dashboard.governanceOverview') }}
        <router-link to="/system" class="dashboard__section-link">
          <Settings :size="14" />
          {{ t('dashboard.governanceDetail') }}
        </router-link>
      </h2>

      <div v-if="govLoading" class="dashboard__gov-loading">{{ t('dashboard.loadingEllipsis') }}</div>
      <template v-else>
        <div class="dashboard__gov-grid">
          <div
            v-for="card in govCards"
            :key="card.labelKey"
            class="dashboard__gov-card"
            :title="t(card.hintKey)"
          >
            <component :is="card.icon" :size="18" :style="{ color: card.color }" />
            <div class="dashboard__gov-card-info">
              <span class="dashboard__gov-card-value tabular-nums">{{ card.value }}</span>
              <span class="dashboard__gov-card-label">{{ t(card.labelKey) }}</span>
            </div>
          </div>
        </div>

        <div class="dashboard__gov-row">
          <div class="dashboard__gov-panel">
            <div class="dashboard__gov-panel-title">{{ t('dashboard.autoMatchRate') }}</div>
            <div class="dashboard__gov-match">
              <div
                class="dashboard__gov-match-value tabular-nums"
                :style="{ color: matchRateColor }"
              >
                {{ matchRate === null ? '--' : matchRate + '%' }}
              </div>
              <div class="dashboard__gov-match-sub">
                <span class="tabular-nums">{{ gov?.gatekeeperMatched ?? 0 }}</span>
                /
                <span class="tabular-nums">{{ gov?.gatekeeperEvaluated ?? 0 }}</span>
                {{ t('dashboard.matchRateSub') }}
              </div>
            </div>
            <div class="dashboard__gov-panel-hint">
              {{ t('dashboard.matchRateHint') }}
            </div>
          </div>

          <div class="dashboard__gov-panel">
            <div class="dashboard__gov-panel-title">{{ t('dashboard.schemaMigration') }}</div>
            <div class="dashboard__gov-migration">
              <div class="dashboard__gov-migration-row">
                <span class="dashboard__gov-migration-label">{{ t('dashboard.currentVersion') }}</span>
                <span class="tabular-nums">v{{ migrationSummary?.version ?? 0 }}</span>
              </div>
              <div class="dashboard__gov-migration-row">
                <span class="dashboard__gov-migration-label">{{ t('dashboard.pendingUpgrade') }}</span>
                <span
                  class="tabular-nums"
                  :class="{ 'text-warning': (migrationSummary?.stale ?? 0) > 0 }"
                >{{ migrationSummary?.stale ?? 0 }}</span>
              </div>
              <div class="dashboard__gov-migration-row dashboard__gov-migration-row--sub">
                <span class="dashboard__gov-migration-label">{{ t('dashboard.outdatedVersion') }}</span>
                <span class="tabular-nums">{{ migrationSummary?.outdated ?? 0 }}</span>
              </div>
              <div class="dashboard__gov-migration-row dashboard__gov-migration-row--sub">
                <span class="dashboard__gov-migration-label">{{ t('dashboard.untaggedVersion') }}</span>
                <span class="tabular-nums">{{ migrationSummary?.untagged ?? 0 }}</span>
              </div>
            </div>
            <router-link to="/system" class="dashboard__gov-panel-link">
              {{ t('dashboard.viewMigrationDetail') }}
              <ArrowRight :size="12" />
            </router-link>
          </div>
        </div>
      </template>
    </div>

    <div class="dashboard__row">
      <div class="dashboard__token-section">
        <h2 class="dashboard__section-title">
          <Zap :size="18" class="dashboard__section-icon" />
          {{ t('dashboard.runtimeOverview') }}
          <span v-if="tokenAlertLabel" class="dashboard__token-alert" :class="`dashboard__token-alert--${token.alertLevel}`">
            {{ tokenAlertLabel }}
          </span>
        </h2>
        <div v-if="tokenLoading" class="dashboard__gov-loading">{{ t('dashboard.loadingEllipsis') }}</div>
        <template v-else>
          <div class="dashboard__token-bar">
            <div class="dashboard__token-progress">
              <div
                class="dashboard__token-fill"
                :style="{ width: `${tokenPercent}%`, background: tokenBarColor }"
              ></div>
            </div>
            <div class="dashboard__token-info">
              <span class="tabular-nums">{{ token.totalTokens.toLocaleString() }}</span>
              <span>
                /
                <template v-if="token.budget">
                  {{ token.budget.toLocaleString() }} {{ t('dashboard.refValue') }}
                </template>
                <template v-else>{{ t('dashboard.noRefValue') }}</template>
              </span>
            </div>
          </div>
          <div class="dashboard__token-breakdown">
            <div class="dashboard__token-item">
              <span class="dashboard__token-item-label">{{ t('dashboard.tokenIngest') }}</span>
              <span class="dashboard__token-item-value tabular-nums">
                {{ token.ingestTokens.toLocaleString() }}
              </span>
            </div>
            <div class="dashboard__token-item">
              <span class="dashboard__token-item-label">{{ t('dashboard.tokenQuery') }}</span>
              <span class="dashboard__token-item-value tabular-nums">
                {{ token.queryTokens.toLocaleString() }}
              </span>
            </div>
            <div class="dashboard__token-item">
              <span class="dashboard__token-item-label">{{ t('dashboard.tokenLint') }}</span>
              <span class="dashboard__token-item-value tabular-nums">
                {{ token.lintTokens.toLocaleString() }}
              </span>
            </div>
            <div class="dashboard__token-item dashboard__token-item--remain">
              <span class="dashboard__token-item-label">{{ t('dashboard.tokenRemaining') }}</span>
              <span class="dashboard__token-item-value tabular-nums" :class="{ 'dashboard__token-item-value--warning': token.alertLevel === 'warning', 'dashboard__token-item-value--exceeded': token.alertLevel === 'exceeded' }">
                {{ token.remaining.toLocaleString() }}
              </span>
            </div>
          </div>
        </template>
      </div>

      <div class="dashboard__quick-section">
        <h2 class="dashboard__section-title">{{ t('dashboard.quickActions') }}</h2>
        <div class="dashboard__quick-actions">
          <router-link
            v-for="action in quickActions"
            :key="action.path"
            :to="action.path"
            class="dashboard__quick-card"
          >
            <component :is="action.icon" :size="24" :style="{ color: action.color }" />
            <span class="dashboard__quick-label">{{ t(action.labelKey) }}</span>
            <ArrowRight :size="16" class="dashboard__quick-arrow" />
          </router-link>
        </div>
      </div>
    </div>

    <!-- Token 消耗趋势 -->
    <div class="dashboard__section">
      <h2 class="dashboard__section-title">
        <TrendingUp :size="18" class="dashboard__section-icon" />
        {{ t('dashboard.trendTitle') }}
      </h2>
      <div v-if="trendLoading" class="dashboard__gov-loading">{{ t('dashboard.loadingEllipsis') }}</div>
      <template v-else>
        <div class="dashboard__trend-chart" @mouseleave="trendHoverIndex = null">
          <svg viewBox="0 0 560 180" preserveAspectRatio="xMidYMid meet" class="dashboard__trend-svg">
            <!-- Y-axis labels -->
            <text x="36" y="15" text-anchor="end" class="dashboard__trend-axis">{{ trendMax.toLocaleString() }}</text>
            <text x="36" y="90" text-anchor="end" class="dashboard__trend-axis">{{ Math.round(trendMax / 2).toLocaleString() }}</text>
            <text x="36" y="165" text-anchor="end" class="dashboard__trend-axis">0</text>
            <!-- Grid lines -->
            <line x1="40" y1="10" x2="520" y2="10" class="dashboard__trend-grid" />
            <line x1="40" y1="85" x2="520" y2="85" class="dashboard__trend-grid" />
            <line x1="40" y1="160" x2="520" y2="160" class="dashboard__trend-grid" />
            <!-- Lines -->
            <path
              v-for="series in trendSeries"
              :key="series.name"
              :d="trendPath(series.data)"
              fill="none"
              :stroke="series.color"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
            <!-- Data point dots on hover -->
            <template v-if="trendHoverIndex !== null">
              <line
                :x1="trendPointX(trendHoverIndex)"
                y1="10"
                :x2="trendPointX(trendHoverIndex)"
                y2="160"
                class="dashboard__trend-hover-line"
              />
              <circle
                v-for="series in trendSeries"
                :key="'dot-' + series.name"
                :cx="trendPointX(trendHoverIndex)"
                :cy="trendPointY(series.data[trendHoverIndex] || 0)"
                r="3.5"
                :fill="series.color"
                stroke="var(--bg-primary)"
                stroke-width="1.5"
              />
            </template>
            <!-- Hover zones (invisible strips for each data point) -->
            <rect
              v-for="(_, idx) in trendData"
              :key="'zone-' + idx"
              :x="trendPointX(idx) - (trendData.length > 1 ? 480 / (trendData.length - 1) / 2 : 240)"
              y="0"
              :width="trendData.length > 1 ? 480 / (trendData.length - 1) : 480"
              height="165"
              fill="transparent"
              class="dashboard__trend-hover-zone"
              @mouseenter="trendHoverIndex = idx"
            />
            <!-- X-axis dates -->
            <text
              v-for="(date, idx) in trendDates"
              :key="idx"
              :x="40 + (trendDates.length > 1 ? (idx / (trendDates.length - 1)) * 480 : 240)"
              y="177"
              text-anchor="middle"
              class="dashboard__trend-axis"
              v-show="idx % Math.ceil(trendDates.length / 6) === 0"
            >{{ date }}</text>
            <!-- Empty state overlay -->
            <text v-if="trendData.length === 0" x="280" y="95" text-anchor="middle" class="dashboard__trend-empty-text">{{ t('dashboard.trendEmpty') }}</text>
          </svg>
          <!-- Tooltip -->
          <div
            v-if="trendHoverIndex !== null && trendData[trendHoverIndex]"
            class="dashboard__trend-tooltip"
            :style="{
              left: (trendPointX(trendHoverIndex) / 560 * 100) + '%',
              top: '8px',
              transform: trendPointX(trendHoverIndex) > 400 ? 'translateX(-100%)' : trendPointX(trendHoverIndex) < 160 ? 'translateX(0)' : 'translateX(-50%)'
            }"
          >
            <div class="dashboard__trend-tooltip-date">{{ trendData[trendHoverIndex].date }}</div>
            <div v-for="series in trendSeries" :key="series.name" class="dashboard__trend-tooltip-row">
              <span class="dashboard__trend-tooltip-dot" :style="{ background: series.color }"></span>
              <span class="dashboard__trend-tooltip-label">{{ t(series.labelKey) }}</span>
              <span class="dashboard__trend-tooltip-value tabular-nums">{{ (series.data[trendHoverIndex] || 0).toLocaleString() }}</span>
            </div>
          </div>
        </div>
        <div class="dashboard__trend-legend">
          <span v-for="series in trendSeries" :key="series.name" class="dashboard__trend-legend-item">
            <i class="dashboard__trend-legend-dot" :style="{ background: series.color }"></i>
            {{ t(series.labelKey) }}
          </span>
        </div>
      </template>
    </div>

    <div class="dashboard__section">
      <h2 class="dashboard__section-title">
        <Activity :size="18" class="dashboard__section-icon" />
        {{ t('dashboard.recentActivity') }}
      </h2>
      <div class="dashboard__empty-state">
        <p>{{ t('dashboard.noExecutions') }}</p>
        <p class="dashboard__empty-hint">{{ t('dashboard.noExecutionsHint') }}</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dashboard {
  max-width: 1040px;
}

.dashboard__title {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  margin-bottom: var(--space-6);
}

.dashboard__stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-4);
  margin-bottom: var(--space-6);
}

.dashboard__stat-card {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  display: flex;
  align-items: center;
  gap: var(--space-3);
  box-shadow: var(--shadow-sm);
}

.dashboard__stat-info {
  display: flex;
  flex-direction: column;
}

.dashboard__stat-value {
  font-size: var(--font-h2);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
}

.dashboard__stat-label {
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.dashboard__section {
  margin-bottom: var(--space-6);
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  box-shadow: var(--shadow-sm);
}

.dashboard__section-title {
  font-size: var(--font-h3);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-4);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.dashboard__section-icon {
  color: var(--accent-primary);
}

.dashboard__section-link {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  color: var(--accent-primary);
  text-decoration: none;
  font-weight: var(--weight-medium);
}

.dashboard__section-link:hover {
  text-decoration: underline;
}

/* 健康度分布条 */
.dashboard__health-bar {
  display: flex;
  height: 14px;
  border-radius: var(--radius-full);
  overflow: hidden;
  background: var(--bg-tertiary);
  margin-bottom: var(--space-3);
}

.dashboard__health-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.dashboard__health-seg {
  color: var(--text-on-accent);
  font-size: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
}

.dashboard__health-seg--green { background: var(--success); }
.dashboard__health-seg--yellow { background: var(--warning); }
.dashboard__health-seg--red { background: var(--error); }
.dashboard__health-seg--gray { background: var(--text-tertiary); }

.dashboard__health-legend {
  display: flex;
  gap: var(--space-4);
  flex-wrap: wrap;
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.dashboard__health-legend .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: var(--space-1);
  vertical-align: middle;
}
.dot-green { background: var(--success); }
.dot-yellow { background: var(--warning); }
.dot-red { background: var(--error); }
.dot-gray { background: var(--text-tertiary); }

/* 共治卡片 */
.dashboard__gov-loading {
  color: var(--text-tertiary);
  font-size: var(--font-body-sm);
  padding: var(--space-3) 0;
}

.dashboard__gov-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.dashboard__gov-card {
  background: var(--bg-tertiary);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  display: flex;
  align-items: center;
  gap: var(--space-2);
}

.dashboard__gov-card-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.dashboard__gov-card-value {
  font-size: var(--font-h3);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  line-height: 1.1;
}

.dashboard__gov-card-label {
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.dashboard__gov-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-4);
}

.dashboard__gov-panel {
  background: var(--bg-tertiary);
  border-radius: var(--radius-md);
  padding: var(--space-4);
}

.dashboard__gov-panel-title {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-semibold);
  color: var(--text-secondary);
  margin-bottom: var(--space-3);
}

.dashboard__gov-match {
  display: flex;
  align-items: baseline;
  gap: var(--space-3);
  margin-bottom: var(--space-2);
}

.dashboard__gov-match-value {
  font-size: 36px;
  font-weight: var(--weight-bold);
  line-height: 1;
}

.dashboard__gov-match-sub {
  font-size: var(--font-caption);
  color: var(--text-secondary);
}

.dashboard__gov-panel-hint {
  font-size: var(--font-caption);
  color: var(--text-tertiary);
}

.dashboard__gov-migration {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.dashboard__gov-migration-row {
  display: flex;
  justify-content: space-between;
  font-size: var(--font-body-sm);
  color: var(--text-primary);
}

.dashboard__gov-migration-row--sub {
  font-size: var(--font-caption);
  color: var(--text-secondary);
  padding-left: var(--space-3);
}

.dashboard__gov-migration-label {
  color: var(--text-secondary);
}

.text-warning { color: var(--warning); font-weight: var(--weight-semibold); }

.dashboard__gov-panel-link {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  font-size: var(--font-caption);
  color: var(--accent-primary);
  text-decoration: none;
  font-weight: var(--weight-medium);
}

.dashboard__gov-panel-link:hover { text-decoration: underline; }

/* Token & 快捷操作 */
.dashboard__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-6);
  margin-bottom: var(--space-6);
}

.dashboard__token-section,
.dashboard__quick-section {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  box-shadow: var(--shadow-sm);
}

.dashboard__token-bar {
  margin-bottom: var(--space-4);
}

.dashboard__token-progress {
  height: 10px;
  background: var(--bg-tertiary);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.dashboard__token-fill {
  height: 100%;
  border-radius: var(--radius-full);
  transition: width var(--transition-normal), background var(--transition-normal);
}

.dashboard__token-info {
  display: flex;
  justify-content: space-between;
  font-size: var(--font-caption);
  color: var(--text-secondary);
  margin-top: var(--space-2);
}

.dashboard__token-breakdown {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.dashboard__token-item {
  display: flex;
  justify-content: space-between;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.dashboard__token-item--remain {
  border-top: 1px dashed var(--border-default);
  padding-top: var(--space-2);
  margin-top: var(--space-1);
}

.dashboard__token-item-value {
  color: var(--text-primary);
  font-weight: var(--weight-medium);
}

.dashboard__token-item-value--warning { color: var(--warning); font-weight: var(--weight-semibold); }
.dashboard__token-item-value--exceeded { color: var(--error); font-weight: var(--weight-semibold); }

.dashboard__token-alert {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  padding: var(--space-1) var(--space-2);
  border-radius: var(--radius-md);
  margin-left: var(--space-2);
}

.dashboard__token-alert--warning {
  background: var(--warning);
  color: var(--text-on-accent);
}

.dashboard__token-alert--exceeded {
  background: var(--error);
  color: var(--text-on-accent);
}

.dashboard__quick-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.dashboard__quick-card {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  background: var(--bg-tertiary);
  color: var(--text-primary);
  text-decoration: none;
  transition: all var(--transition-fast);
}

.dashboard__quick-card:hover {
  background: var(--accent-light);
}

.dashboard__quick-label {
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  flex: 1;
}

.dashboard__quick-arrow {
  color: var(--text-tertiary);
}

.dashboard__empty-state {
  padding: var(--space-6);
  text-align: center;
}

.dashboard__empty-state p {
  color: var(--text-tertiary);
  font-size: var(--font-body);
}

.dashboard__empty-hint {
  font-size: var(--font-body-sm);
  margin-top: var(--space-2);
}

@media (max-width: 960px) {
  .dashboard__stats { grid-template-columns: repeat(2, 1fr); }
  .dashboard__gov-grid { grid-template-columns: repeat(3, 1fr); }
  .dashboard__gov-row { grid-template-columns: 1fr; }
  .dashboard__row { grid-template-columns: 1fr; }
}

/* Trend chart */
.dashboard__trend-chart {
  margin-top: var(--space-3);
  border: 1px solid var(--border-secondary);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  background: var(--bg-primary);
  position: relative;
}

.dashboard__trend-svg {
  width: 100%;
  height: auto;
  max-height: 200px;
}

.dashboard__trend-axis {
  font-size: 10px;
  fill: var(--text-tertiary);
}

.dashboard__trend-grid {
  stroke: var(--border-secondary);
  stroke-width: 0.5;
  stroke-dasharray: 3 3;
}

.dashboard__trend-legend {
  display: flex;
  gap: var(--space-4);
  flex-wrap: wrap;
  margin-top: var(--space-3);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.dashboard__trend-legend-item {
  display: flex;
  align-items: center;
  gap: var(--space-1);
}

.dashboard__trend-legend-dot {
  display: inline-block;
  width: 10px;
  height: 3px;
  border-radius: 2px;
}

.dashboard__trend-empty-text {
  font-size: 12px;
  fill: var(--text-tertiary);
}

/* Trend chart hover interaction */
.dashboard__trend-hover-zone {
  cursor: crosshair;
}

.dashboard__trend-hover-line {
  stroke: var(--border-default);
  stroke-width: 1;
  stroke-dasharray: 3 2;
  pointer-events: none;
}

.dashboard__trend-tooltip {
  position: absolute;
  z-index: 10;
  pointer-events: none;
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md, 0 4px 12px rgba(0,0,0,.08));
  padding: var(--space-2) var(--space-3);
  min-width: 160px;
}

.dashboard__trend-tooltip-date {
  font-size: var(--font-caption);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-1);
  white-space: nowrap;
}

.dashboard__trend-tooltip-row {
  display: flex;
  align-items: center;
  gap: var(--space-1);
  font-size: 11px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.dashboard__trend-tooltip-dot {
  display: inline-block;
  width: 8px;
  height: 3px;
  border-radius: 2px;
  flex-shrink: 0;
}

.dashboard__trend-tooltip-label {
  flex: 1;
  white-space: nowrap;
}

.dashboard__trend-tooltip-value {
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-left: var(--space-2);
}
</style>
