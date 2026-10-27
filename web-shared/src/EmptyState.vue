<script setup lang="ts">defineProps<{title:string;description?:string}>()</script>
<template><div class="tf-empty"><strong>{{title}}</strong><p v-if="description">{{description}}</p><slot/></div></template>
