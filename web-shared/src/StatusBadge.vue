<script setup lang="ts">
import {statusLabel,statusTone} from './format'
defineProps<{status:string}>()
</script>
<template><span class="tf-badge" :class="`tf-badge-${statusTone(status)}`">{{statusLabel(status)}}</span></template>
