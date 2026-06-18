<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login } from '../api/wcs'
import { auth } from '../store/auth'

const router = useRouter()
const userName = ref('admin')
const password = ref('1')
const error = ref('')
const loading = ref(false)

async function submit() {
  error.value = ''
  loading.value = true
  try {
    const res = await login(userName.value, password.value)
    auth.setSession(res.access_token, res.user_name)
    router.push('/dashboard')
  } catch (e: any) {
    error.value = e?.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <form class="panel login-card" @submit.prevent="submit">
      <h2>CayleyWCS</h2>
      <p class="sub">仓库控制系统 · 多协议设备对接</p>
      <div class="field">
        <label>用户名</label>
        <input v-model="userName" autocomplete="username" />
      </div>
      <div class="field">
        <label>密码</label>
        <input v-model="password" type="password" autocomplete="current-password" />
      </div>
      <p v-if="error" class="err-msg">{{ error }}</p>
      <button class="primary login-btn" :disabled="loading" type="submit">
        {{ loading ? '登录中…' : '登 录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login-wrap { height: 100%; display: flex; align-items: center; justify-content: center; }
.login-card { width: 340px; padding: 28px; }
.login-card h2 { margin: 0; }
.sub { color: var(--muted); margin: 4px 0 20px; }
.login-btn { width: 100%; margin-top: 8px; }
.err-msg { color: var(--err); font-size: 13px; }
</style>
