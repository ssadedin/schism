<template>
      <v-toolbar class=mb-2>
            <v-toolbar-title>{{title}}</v-toolbar-title>
            <v-spacer></v-spacer>
            <v-toolbar-side-icon class="hidden-md-and-up"></v-toolbar-side-icon>
            <v-toolbar-items class="hidden-sm-and-down">
              <slot></slot>
              <v-btn flat v-on:click='logout'>Logout</v-btn>
            </v-toolbar-items>
      </v-toolbar>         
</template>

<script>
export default {
    props: ['title'],
    methods: {
        logout() {
            window.location='/logout'
        }
    },
}
</script>
