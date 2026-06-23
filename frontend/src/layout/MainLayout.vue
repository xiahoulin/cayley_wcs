<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { auth } from '../store/auth'

const nav = [
  { to: '/dashboard', label: '实时看板' },
  { to: '/protocol', label: '协议管理' },
  { to: '/application', label: '应用管理' },
  { to: '/binding', label: '绑定授权' },
  { to: '/connection', label: '连接监控' },
]
</script>

<template>
  <div class="shell">
    <aside class="side">
      <div class="brand">CayleyWCS</div>
      <nav>
        <RouterLink v-for="n in nav" :key="n.to" :to="n.to" class="nav-item" active-class="active">
          {{ n.label }}
        </RouterLink>
      </nav>
    </aside>
    <main class="main">
      <header class="topbar">
        <div class="spacer"></div>
        <span class="user">{{ auth.userName || 'admin' }}</span>
        <button @click="auth.logout()">退出</button>
      </header>
      <section class="content">
        <RouterView />
      </section>
    </main>
  </div>
</template>

<style scoped>
.shell { display: flex; height: 100%; }
.side { width: 200px; background: #1f2730; color: #cfd6de; display: flex; flex-direction: column; }
.brand { padding: 18px 16px; font-size: 18px; font-weight: 700; color: #fff; letter-spacing: 1px; }
.nav-item { display: block; padding: 11px 16px; color: #cfd6de; border-left: 3px solid transparent; }
.nav-item:hover { background: #283039; }
.nav-item.active { background: #283039; color: #fff; border-left-color: var(--primary); }
.main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.topbar { display: flex; align-items: center; gap: 12px; padding: 10px 16px; background: var(--panel); border-bottom: 1px solid var(--border); }
.user { color: var(--muted); }
.content { padding: 16px; overflow: auto; }
</style>
