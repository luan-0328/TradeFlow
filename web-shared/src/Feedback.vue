<script setup lang="ts">defineProps<{error?:string;loading?:boolean}>()</script>
<template><div class="tf-feedback" aria-live="polite"><p v-if="error" role="alert" class="tf-error">{{error}}</p><p v-else-if="loading" role="status">正在读取，请稍候…</p></div></template>
