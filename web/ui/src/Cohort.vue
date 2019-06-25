<template>
<div>
       <app-header title='Cohort'>                                               
          <v-btn flat v-on:click.prevent=viewBreakpoints>View Breakpoints</v-btn> 
          <v-btn flat v-on:click=addBreakpoints>Add Breakpoints</v-btn>           
          <v-btn flat v-on:click=manageCohort>Manage</v-btn>                      
       </app-header>                                                             
       <app-card subtitle='Samples'>
              <v-data-table
                  v-bind:headers="headers"
                  :items="cohortSamples"
                  class="elevation-1"
              >
                  <template slot="items" slot-scope="props">
                        <tr v-on:click='openSample(props.item)'>
                          <td class="text-xs-left">{{ sampleIdFor(props.item) }}</td>
                          <td class="text-xs-center">{{ props.item.sex }}</td>
                          <td class="text-xs-center">{{ props.item.family_name }}</td>
                          <td class="text-xs-center">{{ fatherId(props.item)}}</td>
                          <td class="text-xs-center">{{ motherId(props.item)}}</td>
                        </tr>
                  </template>
              </v-data-table>       
        </app-card>
    </div>
</template>

<script>

import AppHeader from './AppHeader'
import AppCard from './AppCard'

export default {
    
    
    props: [
        'id'
    ],
    
    components: {
        'app-header' : AppHeader,
        'app-card' : AppCard
    },
    
    mounted: function() {
        model.fetchCohortSamples(this.id)
        model.fetchCohortDetails(this.id, this)
    },
    
    methods: {
        newCohort() {
        
        },
        
        sampleIdFor(sample) {
            console.log(sample)
            return sample.sampleId
        },
        
        openSample(sample) {
            alert('Open sample ' + sample.id)
            router.push(`/cohort/${this.id}/${sample.id}`)
        },
        
        manageCohort() {
            alert("Not implemented yet")
        },
        
        addBreakpoints() {
            this.$router.push(`/cohort/${this.id}/add-breakpoints`)
        },
        
        viewBreakpoints() {
            this.$router.push(`/cohort/${this.id}/breakpoints`)
        },
        
        fatherId: function(sample) {
            return sample.father ? sample.father.sampleId : ''
        },
        motherId: function(sample) {
            return sample.mother ? sample.mother.sampleId : ''
        },
        
    },
    
    computed: {
        cohortSamples: function() {
           return model.cohortSamples[this.id] 
        },
        
        details: function() {
            return {
                Name: this.cohort.name, 
                Description: this.cohort.description, 
                "Number of Breakpoints": this.cohort.summary && this.cohort.summary.numberOfBreakpoints           
            }
                
        },
    },
    
    data: function() { return {
        headers: [
            { text: 'Sample Id', value: 'sampleId', align: 'left' },
            { text: 'Sex', value: 'sex', align: 'center' },
            { text: 'Family', value: 'FAMILY_NAME', align: 'center'}, 
            { text: 'Father', value: 'FATHER_sampleId', align: 'center'},
            { text: 'Mother', value: 'MOTHER_sampleId', align: 'center'},
        ],
        
        model: model,
        
        cohort: null
    
    }},
}
</script>

<style>

</style>

