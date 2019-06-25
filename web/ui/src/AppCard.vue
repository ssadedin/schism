<template>
    <v-card>
        <v-card-title v-if='subtitle'>
              <h4>{{subtitle}}</h4>
        </v-card-title>
        <v-card-text>
            <slot></slot>
       </v-card-text>
    </v-card> 
</template>

<script>

export default {
    props: ['title','subtitle'], 
}
</script>
