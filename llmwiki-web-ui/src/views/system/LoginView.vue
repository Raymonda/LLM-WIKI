<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { login, register } from '@/api/auth'


const { t } = useI18n()


const router = useRouter()
const authStore = useAuthStore()
const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMsg = ref('')
const isRegister = ref(false)

const canvasRef = ref<HTMLCanvasElement | null>(null)
let animationId: number | null = null
let nodes: { x: number; y: number; vx: number; vy: number; radius: number; opacity: number }[] = []
let edges: { from: number; to: number; opacity: number }[] = []
let newNodeTimer: number | null = null

function initGraph() {
  nodes = []
  edges = []
  for (let i = 0; i < 20; i++) {
    nodes.push({
      x: Math.random() * window.innerWidth,
      y: Math.random() * window.innerHeight,
      vx: (Math.random() - 0.5) * 0.3,
      vy: (Math.random() - 0.5) * 0.3,
      radius: 3 + Math.random() * 4,
      opacity: 0.3 + Math.random() * 0.5,
    })
  }
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      const dx = nodes[i].x - nodes[j].x
      const dy = nodes[i].y - nodes[j].y
      const dist = Math.sqrt(dx * dx + dy * dy)
      if (dist < 200) {
        edges.push({ from: i, to: j, opacity: 0.1 + Math.random() * 0.15 })
      }
    }
  }
}

function addNode() {
  const newNode = {
    x: Math.random() * window.innerWidth,
    y: Math.random() * window.innerHeight,
    vx: (Math.random() - 0.5) * 0.3,
    vy: (Math.random() - 0.5) * 0.3,
    radius: 3 + Math.random() * 4,
    opacity: 0,
  }
  const idx = nodes.length
  nodes.push(newNode)
  for (let i = 0; i < nodes.length - 1; i++) {
    const dx = nodes[i].x - newNode.x
    const dy = nodes[i].y - newNode.y
    const dist = Math.sqrt(dx * dx + dy * dy)
    if (dist < 250) {
      edges.push({ from: i, to: idx, opacity: 0 })
    }
  }
}

function animate() {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  canvas.width = window.innerWidth
  canvas.height = window.innerHeight

  ctx.clearRect(0, 0, canvas.width, canvas.height)

  nodes.forEach(node => {
    node.x += node.vx
    node.y += node.vy
    if (node.x < 0 || node.x > canvas.width) node.vx *= -1
    if (node.y < 0 || node.y > canvas.height) node.vy *= -1
    if (node.opacity < 0.8) node.opacity += 0.005
  })

  edges.forEach(edge => {
    if (edge.opacity < 0.2) edge.opacity += 0.003
    const from = nodes[edge.from]
    const to = nodes[edge.to]
    if (!from || !to) return
    ctx.beginPath()
    ctx.moveTo(from.x, from.y)
    ctx.lineTo(to.x, to.y)
    ctx.strokeStyle = `rgba(94, 106, 210, ${edge.opacity})`
    ctx.lineWidth = 1
    ctx.stroke()
  })

  nodes.forEach(node => {
    ctx.beginPath()
    ctx.arc(node.x, node.y, node.radius, 0, Math.PI * 2)
    ctx.fillStyle = `rgba(94, 106, 210, ${node.opacity})`
    ctx.fill()
    ctx.beginPath()
    ctx.arc(node.x, node.y, node.radius + 4, 0, Math.PI * 2)
    ctx.fillStyle = `rgba(94, 106, 210, ${node.opacity * 0.2})`
    ctx.fill()
  })

  animationId = requestAnimationFrame(animate)
}

onMounted(() => {
  initGraph()
  animate()
  newNodeTimer = window.setInterval(() => {
    if (nodes.length < 50) addNode()
  }, 3000)
})

onUnmounted(() => {
  if (animationId) cancelAnimationFrame(animationId)
  if (newNodeTimer) clearInterval(newNodeTimer)
})

async function handleLogin() {
  loading.value = true
  errorMsg.value = ''
  try {
    const result = isRegister.value
      ? await register(username.value, password.value)
      : await login(username.value, password.value)
    authStore.setAuth(result.token, result.user)
    router.push('/')
  } catch (e: any) {
    errorMsg.value = e.message || t('auth.operationFailed')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <canvas ref="canvasRef" class="login-page__canvas"></canvas>

    <div class="login-page__content">
      <div class="login-page__brand">
        <div class="login-page__logo">
          <img src="/logo-v2.png" alt="Zilio" class="login-page__logo-img" />
          <span class="login-page__logo-text">{{ t('common.brandName') }}</span>
        </div>
        <h2 class="login-page__tagline">{{ t('auth.tagline') }}</h2>
        <p class="login-page__description">{{ t('auth.description') }}</p>
      </div>

      <div class="login-card">
        <form class="login-card__form" @submit.prevent="handleLogin">
          <div class="login-card__field">
            <label class="login-card__label">{{ t('auth.username') }}</label>
            <input
              v-model="username"
              type="text"
              class="login-card__input"
              :placeholder="t('auth.usernamePlaceholder')"
              autocomplete="username"
            />
          </div>
          <div class="login-card__field">
            <label class="login-card__label">{{ t('auth.password') }}</label>
            <input
              v-model="password"
              type="password"
              class="login-card__input"
              :placeholder="t('auth.passwordPlaceholder')"
              autocomplete="current-password"
            />
          </div>
          <button type="submit" class="login-card__submit" :disabled="loading">
            {{ loading ? (isRegister ? t('auth.registering') : t('auth.loggingIn')) : (isRegister ? t('auth.registerAndEnter') : t('auth.enterKB')) }}
          </button>
          <div v-if="errorMsg" class="login-card__error">{{ errorMsg }}</div>
          <button type="button" class="login-card__switch" @click="isRegister = !isRegister; errorMsg = ''">
            {{ isRegister ? t('auth.hasAccountLogin') : t('auth.noAccountRegister') }}
          </button>
        </form>

      </div>

      <div class="login-page__features">
        <div class="login-page__feature">
          <span class="login-page__feature-dot"></span>
          <span>{{ t('auth.feature1') }}</span>
        </div>
        <div class="login-page__feature">
          <span class="login-page__feature-dot"></span>
          <span>{{ t('auth.feature2') }}</span>
        </div>
        <div class="login-page__feature">
          <span class="login-page__feature-dot"></span>
          <span>{{ t('auth.feature3') }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-primary);
  position: relative;
  overflow: hidden;
}

.login-page__canvas {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 0;
}

.login-page__content {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-6);
}

.login-page__brand {
  text-align: center;
}

.login-page__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  margin-bottom: var(--space-4);
}

.login-page__logo-img {
  height: 42px;
  width: auto;
  object-fit: contain;
  vertical-align: middle;
}

.login-page__logo-text {
  font-size: var(--font-h1);
  font-weight: var(--weight-bold);
  color: var(--text-primary);
  letter-spacing: -0.02em;
}

.login-page__tagline {
  font-size: var(--font-h2);
  font-weight: var(--weight-semibold);
  color: var(--text-primary);
  margin-bottom: var(--space-2);
}

.login-page__description {
  font-size: var(--font-body);
  color: var(--text-secondary);
  max-width: 360px;
}

.login-card {
  background: var(--surface-card);
  border: 1px solid var(--border-default);
  border-radius: var(--radius-lg);
  padding: var(--space-6);
  width: 380px;
  box-shadow: var(--shadow-sm);
}

.login-card__form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.login-card__field {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}

.login-card__label {
  font-size: var(--font-body-sm);
  font-weight: var(--weight-medium);
  color: var(--text-secondary);
}

.login-card__input {
  height: 44px;
  background: var(--input-bg);
  border: 1px solid var(--input-border);
  border-radius: var(--radius-md);
  padding: var(--space-2) var(--space-3);
  color: var(--text-primary);
  font-size: var(--font-body);
  outline: none;
  transition: border-color var(--transition-fast);
}

.login-card__input:focus {
  border-color: var(--accent-primary);
  box-shadow: 0 0 0 3px rgba(94, 106, 210, 0.1);
}

.login-card__input::placeholder {
  color: var(--text-tertiary);
}

.login-card__submit {
  height: 44px;
  background: var(--accent-primary);
  color: var(--text-on-accent);
  border: none;
  border-radius: var(--radius-md);
  font-size: var(--font-body);
  font-weight: var(--weight-medium);
  cursor: pointer;
  transition: opacity var(--transition-fast);
  margin-top: var(--space-2);
}

.login-card__submit:hover:not(:disabled) {
  opacity: 0.9;
}

.login-card__submit:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.login-card__error {
  color: var(--error);
  font-size: var(--font-body-sm);
  text-align: center;
  padding: var(--space-2) 0;
}

.login-card__switch {
  background: none;
  border: none;
  color: var(--accent-primary);
  font-size: var(--font-body-sm);
  cursor: pointer;
  text-align: center;
  padding: var(--space-2) 0;
}

.login-card__switch:hover {
  opacity: 0.8;
}

.login-page__features {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  max-width: 380px;
}

.login-page__feature {
  display: flex;
  align-items: center;
  gap: var(--space-3);
  font-size: var(--font-body-sm);
  color: var(--text-secondary);
}

.login-page__feature-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-full);
  background: var(--accent-primary);
  opacity: 0.6;
}

[data-theme="dark"] .login-page__logo-img {
  filter: drop-shadow(0 0 6px rgba(255, 255, 255, 0.12));
}
</style>