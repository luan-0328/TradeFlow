<script setup lang="ts">
import {text} from '../data'
import type {DataRow} from '@tradeflow/ui'
defineProps<{value:DataRow}>()
</script>
<template><dl class="record-detail"><template v-for="(value,key) in value" :key="key"><dt>{{key}}</dt><dd>{{text(value)}}</dd></template></dl></template>
