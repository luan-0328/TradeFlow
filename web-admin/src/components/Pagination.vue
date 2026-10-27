<script setup lang="ts">
defineProps<{page:number;count:number;hasNext:boolean;loading?:boolean;total?:number|null}>()
defineEmits<{change:[delta:number]}>()
</script>
<template><div class="pagination"><span class="muted">当前页 {{count}} 条<span v-if="total!=null"> · 共 {{total}} 条</span></span><div><ElButton :disabled="page<=1||loading" @click="$emit('change',-1)">上一页</ElButton><span class="page-number">第 {{page}} 页</span><ElButton :disabled="!hasNext||loading" @click="$emit('change',1)">下一页</ElButton></div></div></template>
