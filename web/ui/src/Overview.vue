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
        window.console.log('Mounted overview')
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
            window.console.log('Importing cohort')
            this.$router.push('/import')
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