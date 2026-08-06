<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatRemaining } from '../progressModel'

const { t } = useI18n()

type BarStatus = 'running' | 'waiting' | 'done' | 'failed' | 'paused'

const props = withDefaults(
  defineProps<{
    progress: number
    remainingMs?: number
    paused?: boolean
    status?: BarStatus
    waitingHint?: string
  }>(),
  {
    remainingMs: 0,
    paused: false,
    status: 'running',
    waitingHint: '',
  },
)

const clamped = computed(() => {
  const v = Number.isFinite(props.progress) ? props.progress : 0
  return Math.max(0, Math.min(1, v))
})

const percentText = computed(() => `${Math.floor(clamped.value * 100)}%`)

const widthStyle = computed(() => ({ width: `${clamped.value * 100}%` }))

const rightText = computed(() => {
  if (props.status === 'done') return t('ingest.statusDone')
  if (props.status === 'failed') return t('ingest.statusFailed')
  if (props.status === 'paused') return t('ingest.statusPaused')
  if (props.paused || props.status === 'waiting') return props.waitingHint || t('ingest.waitingHint')
  return formatRemaining(props.remainingMs)
})

const rootClass = computed(() => [
  'ingest-progress-bar',
  `ingest-progress-bar--${props.status}`,
  {
    'ingest-progress-bar--paused': props.paused,
  },
])
</script>

<template>
  <div :class="rootClass" role="progressbar" :aria-valuenow="Math.floor(clamped * 100)" aria-valuemin="0" aria-valuemax="100">
    <div class="ingest-progress-bar__track">
      <div class="ingest-progress-bar__fill" :style="widthStyle">
        <div class="ingest-progress-bar__stripe" />
      </div>
    </div>
    <div class="ingest-progress-bar__meta">
      <span class="ingest-progress-bar__percent">{{ percentText }}</span>
      <span class="ingest-progress-bar__right">{{ rightText }}</span>
    </div>
  </div>
</template>

<style scoped>
.ingest-progress-bar {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  width: 100%;
}

.ingest-progress-bar__track {
  position: relative;
  width: 100%;
  height: 10px;
  background: var(--accent-light);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.ingest-progress-bar__fill {
  position: relative;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--accent-primary) 0%, var(--success) 100%);
  transition: width 400ms ease-out;
  will-change: width;
  overflow: hidden;
}

.ingest-progress-bar__stripe {
  position: absolute;
  inset: 0;
  background-image: linear-gradient(
    45deg,
    rgba(255, 255, 255, 0.18) 0%,
    rgba(255, 255, 255, 0.18) 25%,
    transparent 25%,
    transparent 50%,
    rgba(255, 255, 255, 0.18) 50%,
    rgba(255, 255, 255, 0.18) 75%,
    transparent 75%,
    transparent 100%
  );
  background-size: 24px 24px;
  opacity: 0;
  animation: ingest-progress-stripe 1.2s linear infinite;
}

.ingest-progress-bar--running .ingest-progress-bar__stripe {
  opacity: 1;
}

.ingest-progress-bar--paused .ingest-progress-bar__fill,
.ingest-progress-bar--waiting .ingest-progress-bar__fill {
  background: linear-gradient(90deg, var(--accent-primary) 0%, var(--warning) 100%);
  animation: ingest-progress-breath 2.4s ease-in-out infinite;
}

.ingest-progress-bar--paused .ingest-progress-bar__stripe,
.ingest-progress-bar--waiting .ingest-progress-bar__stripe {
  opacity: 1;
  animation-duration: 2s;
}

.ingest-progress-bar--done .ingest-progress-bar__fill {
  background: linear-gradient(90deg, var(--success) 0%, var(--success) 100%);
}

.ingest-progress-bar--done .ingest-progress-bar__stripe {
  opacity: 0;
}

.ingest-progress-bar--failed .ingest-progress-bar__fill {
  background: linear-gradient(90deg, var(--error) 0%, var(--error) 100%);
}

.ingest-progress-bar--failed .ingest-progress-bar__stripe {
  opacity: 0;
}

.ingest-progress-bar__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.ingest-progress-bar__percent {
  font-weight: 600;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}

.ingest-progress-bar--paused .ingest-progress-bar__right,
.ingest-progress-bar--waiting .ingest-progress-bar__right {
  color: var(--warning);
  font-weight: 500;
}

.ingest-progress-bar--failed .ingest-progress-bar__right {
  color: var(--error);
}

.ingest-progress-bar--done .ingest-progress-bar__right {
  color: var(--success);
}

@keyframes ingest-progress-stripe {
  from {
    background-position: 0 0;
  }
  to {
    background-position: 24px 0;
  }
}

@keyframes ingest-progress-breath {
  0%, 100% {
    opacity: 0.85;
  }
  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ingest-progress-bar__fill {
    transition: none;
  }
  .ingest-progress-bar__stripe {
    animation: none;
  }
  .ingest-progress-bar--paused .ingest-progress-bar__fill,
  .ingest-progress-bar--waiting .ingest-progress-bar__fill {
    animation: none;
  }
}
</style>
