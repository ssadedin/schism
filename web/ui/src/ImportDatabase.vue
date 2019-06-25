<template>
<div>
       <app-header title='Import Database'>                                               
          <v-btn flat v-on:click='$router.push("/cohorts")'>Home</v-btn>                      
       </app-header>                                                             
       <app-card subtitle='Details'>
           <v-form v-model='valid'>
               <v-text-field v-model='name' :rules='nameRules' label='The name of the cohort to create (must be unique)'></v-text-field>
               <v-combobox v-model='path' :items='pendingImports'/>
               <v-btn :disabled='!valid' @click='startImport'>Load</v-btn>
           </v-form>
       </app-card>
    </div>
</template>

<script>
import AppHeader from './AppHeader'
import AppCard from './AppCard'

import Vue from 'vue'
import Vuetify from 'vuetify'

import axios from 'axios'

Vue.use(Vuetify)

export default {
    
    props: [
    ],
    
    components: {
        'app-header' : AppHeader,
        'app-card' : AppCard
    },
    
    mounted: function() {
        axios.get('/import/pending')
             .then(req => {
                 window.console.log('pending imports: ')
                 window.console.log(req.data)
                 this.pendingImports = req.data
             })
             .catch(r => {
                 alert('Failed to load pending imports: ' + r.responseText)
             })
    },
    
    methods: {
        startImport() {
            axios.post('/import/init', {
                path: this.path
            })
            .then(req => {
                alert('It worked')
            })
            .catch(req => {
                window.console.log('Failed to start import:')
                window.console.log(req)                
                window.failedreq = req;
                alert('Failed to start import: ' + req.message)
            })
        }
    },
    
    computed: {
    },
    
    data: function() { return {
        
        name: null,
        nameRules: [ n => !!n || 'Please enter the name of the cohort'  ],

        path: null,
        pathRules: [ p => !!p || 'Please select a Database to import from the import folder'],
        
        pendingImports: null,
       
        model: model,
        
        valid: false,
        
        cohort: null
    
    }},
}
</script>

<style>

</style>

