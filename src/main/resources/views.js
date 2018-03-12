//= require cohort-views.js
//= require vue-editor.js

Vue.component('app-header', {
    props: ['title'],
    methods: {
        logout() {
            window.location='/logout'
        }
    },
    
    template: `
            <v-toolbar class=mb-2>
                <v-toolbar-title>{{title}}</v-toolbar-title>
                <v-spacer></v-spacer>
                <v-toolbar-side-icon class="hidden-md-and-up"></v-toolbar-side-icon>
                <v-toolbar-items class="hidden-sm-and-down">
                  <slot></slot>
                  <v-btn flat v-on:click='logout'>Logout</v-btn>
                </v-toolbar-items>
          </v-toolbar>         
    `
})


Vue.component('app-card',{
    props: ['title','subtitle'],
    template: `
        <v-card>
            <v-card-title v-if='subtitle'>
                  <h4>{{subtitle}}</h4>
            </v-card-title>
            <v-card-text>
                <slot></slot>
           </v-card-text>
        </v-card> 
        
    `
})

Vue.component('overview', {
    
    mounted: function() {
        console.log('Mounted overview')
        model.fetchCohorts()
    },
    
    methods: {
        openCohort: function(cohort) {
            /*
            layout.showUpperTab({
                componentName: 'Cohort',
                'title': cohort.name,
                id: cohort.name + '-Cohort',
                componentState: {
                    cohort: cohort
                }
            })
            */
            
//            app.navigateTo(`cohort/${cohort.id}`, { cohort: cohort })
            router.push(`/cohort/${cohort.id}`)
//            vue.currentProps = { cohort: cohort }
//            vue.currentView = 'cohort'
        },
        
        addCohort: function() {
            router.push(`/cohort/create`)
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
    
    template: `
        <div>
          <app-header title='Schism'>
              <v-btn flat v-on:click=addCohort>Add Cohort</v-btn>
          </app-header>
        <br>
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
    `
})