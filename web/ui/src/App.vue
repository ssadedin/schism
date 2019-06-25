<template>

  <v-app id="inspire" ref='vapp'>

    <v-snackbar :timeout="2000" top v-model="snackbar" ref='snackbar'>
          {{model.snackbarMessage}}
          <v-btn flat color="blue" @click.native="snackbar = false">Close</v-btn>
    </v-snackbar>
    
    <v-content>
      <v-container fluid>
         <router-view></router-view>
      </v-container>
    </v-content>
    
  </v-app>
</template>

<script>
import AppHeader from './AppHeader'

export default {
  name: 'app',
  components: {
      'app-header' : AppHeader
  },
  
  data () {
    return {
      msg: 'Hello World!!!',
      snackbar: false,
      model: model
    }
  }
}
</script>

<style lang="scss">
#app {
  font-family: 'Avenir', Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  text-align: center;
  color: #2c3e50;
  margin-top: 60px;
}

h1, h2 {
  font-weight: normal;
}

ul {
  list-style-type: none;
  padding: 0;
}

li {
  display: inline-block;
  margin: 0 10px;
}

a {
  color: #42b983;
}
</style>
