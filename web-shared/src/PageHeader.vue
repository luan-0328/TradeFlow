<script setup lang="ts">defineProps<{title:string;description?:string}>()</script>
<template><header class="tf-page-header"><div><h1>{{title}}</h1><p v-if="description">{{description}}</p></div><div class="tf-page-actions"><slot name="actions"/></div></header></template>
