import Vue from 'vue'
// import App from './App.vue'

import axios from 'axios'

import Vuetify from 'vuetify'
import 'vuetify/dist/vuetify.min.css'
import VueRouter from 'vue-router'

Vue.use(VueRouter)
Vue.use(Vuetify)

import Overview from './Overview.vue'
import Cohort from './Cohort.vue'
import SampleDetail from './SampleDetail.vue'
import App from './App.vue'
import BreakpointsView from './Breakpoints'
import ImportDatabase from './ImportDatabase'

class Model {
    
    constructor() {
        this.cohorts = []
        this.cohortSamples = {};
        this.tags = {}
        this.greyed = {}
    }

    fetchCohorts() {
        console.log('Fetching cohorts')
        axios.get('/cohort')
             .then((req) => {
                 console.log('Received: ', req.data)
                 this.cohorts = req.data.cohorts
             })   
    }
    
    fetchCohortDetails(cohortId, component) {
        axios.get(`/cohort/${cohortId}/summary`, {
            dataType: 'json'
        }).then( (req) => {
            let data = req.data
            console.log(data.summary)
            component.cohort = data.cohort
            Vue.set(component.cohort, 'summary', data.summary)
        })            
    }
    
    fetchCohortSamples(cohortId) {
        axios.get(`/cohort/${cohortId}/samples`)
             .then( (req) => {
                Vue.set(this.cohortSamples, cohortId, req.data.samples)
             })    
    }
    
    post(url, data) {
        let done = null
        
        let result = {
            done: function(fn) {
                done = fn;
            }
        }
        
        $.post({
            url: url,
            data: JSON.stringify(data),
            contentType: 'application/json',
            dataType: 'json'
        }).done((data) => {
            if(data.status != 'ok') {
                alert('There was a problem with your request:\n\n' + data.error)
            }
            else {
                if(done) {
                    done(data)
                }
            }
        })         
        
        return result
    }
    
    save() {
        // todo
    }
}


var routes = [
    { path: '/', component: Overview, props: true},
    // { path: '/cohort/create', component: Vue.component('add-cohort'), props: true},
    { path: '/cohort/:cohortId/breakpoints', component: BreakpointsView, props: true},
    { path: '/cohort/:id', component: Cohort, props: true},
    { path: '/import', component: ImportDatabase, props: true},
    // { path: '/cohort/:id/add-breakpoints', component: Vue.component('add-breakpoints'), props: true},
    // { path: '/cohort/:cohortId/:id', component: Vue.component('SampleDetail'), props: true},
] 

var router = new VueRouter({routes})
window.router = router;

window.model = new Model()

new Vue({
  el: '#app',
  components: {
    'Overview' : Overview,
    'Cohort' : Cohort
  },
  router: new VueRouter({routes}),
  render: h => h(App)
})