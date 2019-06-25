<template>
  <div>
    <app-header title='Schism'>
      <v-btn flat v-on:click='importCohort'>Import</v-btn>
    </app-header>
      
    <h4>Cohorts</h4>
    <v-data-table
          v-bind:headers="headers"
          :items="model.cohorts"
          class="elevation-1"
        >
        <template slot="items" slot-scope="props">
            <tr v-on:click='openCohort(props.item)'>
              <td>{{ props.item.name }}</td>
              <td class="text-xs-right">{{ props.item.description }}</td>
            </tr>
        </template>
      </v-data-table>        
    </div>
</template>               

<script>
import AppHeader from './AppHeader'

export default {
    
    components: {
        'app-header' : AppHeader
    },
       
    mounted: function() {
        console.log('Mounted overview')
        model.fetchCohorts()
    },
    
    methods: {
        openCohort(cohort) {
            this.$router.push(`/cohort/${cohort.id}`)
        },
        
        addCohort() {
            this.$router.push(`/cohort/create`)
        },

        importCohort() {
            console.log('Importing cohort')
        }
    },
    
    data: function() { return {
        headers: [
            {
                text: 'Name',
                value: 'name'
            },
            {
                text: 'Description',
                value: 'description'
            }
        ],
        model: model
    }},
}
</script>