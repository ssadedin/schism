<template>
      <div v-if='cohort' class='content_panel_wrapper devuetify' >
         <app-header :title='"Breakpoints in " + cohort.name'>
            <v-toolbar-side-icon class="hidden-md-and-up"></v-toolbar-side-icon>
         </app-header>         
        <app-card>
            <div class="outer-container">
                <div id="form-container">
                
                    <label for=exclude_sample>Exclude</label>
                    <input v-model="exclude_sample" placeholder="Exclude samples" v-on:change="updateExcludedSamples">
                
                    <label for=obs_filter_threshold>Min Reads</label>
                    <select v-model="obs_filter_threshold" v-on:change="updateObsFilter">
                        <option v-for="option in obs_filter_levels" v-bind:value="option" v-bind:key="option">
                        {{ option }}
                        </option>
                    </select>
                    
                    <label for=obs_filter_threshold>Max Samples</label>
                    
                    <select v-model="sample_count_filter_threshold" v-on:change="updateSampleCountFilter">
                        <option v-for="option in sample_count_filter_levels" v-bind:value="option" v-bind:key="option">
                        {{ option }}
                        </option>
                    </select> 
                    
                    Gene Proximity
                    <select v-model="gene_proximity" v-on:change="filterSamples">
                        <option v-for="option in Object.keys(DIST_CLASS_DESC)" v-bind:value="option"  v-bind:key="option">
                            {{DIST_CLASS_DESC[option]}}
                        </option>
                    </select>
                    
                    <a href='#' v-on:click.prevent='configure'><span class='configure'>&#x2699;</span></a>
                    
                    <modal name='configuration-modal' @closed='configClose' title='Config' :show='false'>
                        <div class=content_panel_wrapper>
                            <h2>Configuration</h2>
                            <label class='left'>Prefix for Accessing BAM Files: </label>
                            <div class='rightFill'><input type='text' ref='bamFilePrefixInput' v-model='bamFilePrefix'></div>
                            <label class='left'>Gene List:</label>
                            <div class='rightFill'><textarea ref='geneListTextArea' v-model='geneList' rows=5 cols=40></textarea></div>
                        </div>
                    </modal>
                    
                    <modal name='tag-modal' :width='330' :height=140 @opened='noKeys' @closed='popKeys' title='Tag'>
                        <div class=content_panel_wrapper>
                            <h2>Tag</h2>
                            <label class=smallLeft>Tag to apply: </label>
                            <div class=smallRightFill><input type='text' ref='tagToAddInput' v-model='tagToAdd' v-on:keypress='onTagKey'></div>
                            <div class='dialogButtons'>
                                <button v-on:click='cancelSaveTag()'>Cancel</button>
                                <button v-on:click='saveTag()'>Save</button>
                            </div>
                        </div>
                    </modal> 
              </div>
            </div>
                
            <div class="outer-container">
                <div id="search-controls-container">
                </div>
            </div>
                
            <div class="outer-container">
                <div id="results-container"></div>
            </div>
                  
            <table id='breakpoint-table' class='stripe'>
                  <thead>
                  </thead>
                  <tbody>
                  </tbody>
            </table>
                
            <div v-if='highlightedBp'>
                <breakpoint-diagram :breakpoint='highlightedBp' :breakpoints='breakpoints' />
            </div>
      </app-card>
      </div>

</template>

<script>

import axios from 'axios'
import AppHeader from './AppHeader'
import AppCard from './AppCard'
import modal from 'vue-modal'
import $ from 'jquery'
import DataTable from 'datatables'

var BP_TYPES = ['noise','deletion','insertion','microduplication',
    'duplication','inversion','complex sv','sv','contamination',
    'adapter','gcextreme',
    'chimeric read','multimapping','badref',
    'common','unknown'];

var DATA_COLUMNS = {
'id' : 0,
'Contig' : 1,
'Position' : 2,
'Partner' : 6,
'Samples' : 4,
'Reads' : 3,
'Individual' : 7,
'Genes' : 8
}

var removeChrRegex = /^chr/;

function igv_url_for_sample(sample) {
    var base='http://localhost:60151/';
    if(window.bamFiles && bamFiles[sample]) {
        let breakpointPath = bamFiles[sample]
        
        let bamFilePrefix = components.BreakpointsView.bamFilePrefix || defaultBamFilePrefix
        
        if(bamFilePrefix) {
            breakpointPath = bamFilePrefix + '/' + breakpointPath.replace(/^.*\//, '')
        }
        return base + 'load?file='+encodeURIComponent(breakpointPath)+ '&';
    }
    else {
        return base + 'goto?';
    }
}

function igv_link(contents, bpIndex, start, end) {
    return `<a href="#" onclick="return go_igv(${bpIndex}, ${start}, ${end})">${contents}</a>` 
}

function go_igv(breakpointIndex, start /* optional */, end /* optional */) {
    var base='http://localhost:60151/';
    var bp = model.breakpoints.breakpoints[breakpointIndex];
    if(window.bamFiles && bamFiles[bp.sample]) {
        let breakpointPath = bamFiles[bp.sample]
        let bamFilePrefix = components.BreakpointsView.bamFilePrefix || defaultBamFilePrefix
        if(bamFilePrefix) {
            breakpointPath = bamFilePrefix + '/' + breakpointPath.replace(/^.*\//, '')
        }
        base += 'load?file='+encodeURIComponent(breakpointPath)+ '&';
    }
    else {
        base += 'goto?';
    }
    
    let url = base + 'locus='+bp.chr+ ':' + start +'-'+end;
    console.log("Navigate IGV using URL: " + url)
    $('#igv')[0].src = url;
}

let DIST_CLASS_DESC = {
        "none" : "Any",
        "far" : "Region (20kbp)",
        "inside" : "In Coding Region",
        "adjacent" : "Very Near (50bp)",
        "close" : "Near (500bp)",
}

let DIST_THRESHOLDS = {
    'close': 500,
    adjacent: 50,
    inside: 0,
}

function compute_dist_class(cds_dist) {
   var distClass = "far";
   if(cds_dist<0) {
       distClass="none";
   }
   else
   if(cds_dist==0) {
       distClass="inside"
   }
   else
   if(cds_dist<50) {
       distClass="adjacent"
   }               
   else
   if(cds_dist<500) {
       distClass="close"
   } 
   return distClass;
}

var TABLE_COLUMNS = [
   { 
       title: 'Tags', data: 'start', render: function(data,type,row) {
           let bpId = row.chr + ':' + row.pos
           let tags = model.tags;
           if(tags[bpId]) {
               let value = `<span class=tags>${tags[bpId]}</span>`;
               return value;
           }
           else
               return '';
       }
   },
   { title: 'Position', data: 'start', render: function(data,type,row) {
       var sizeInfo = '';
       var partnerInfo = ''
       if(row.partner) {
           var chr = row.chr.replace(removeChrRegex,'');
           var pos = row.pos;

           var partnerChrSplit = row.partner.split(':');
           var partnerChr = partnerChrSplit[0].replace(removeChrRegex,'');

           if(chr == partnerChr)  {
               var partnerPos = parseInt(partnerChrSplit[1],10);
               var eventSize = Math.abs(partnerPos-pos);
               if(eventSize > 1000) {
                   sizeInfo = ' <span class=largeEvent>(' + 
                       igv_link(`${eventSize} bp`, row.index, pos - 600, partnerPos+600) + 
                   ')</span>';
               }
               else {
                   sizeInfo = ' (' + eventSize + 'bp)';
               }
           }

           partnerInfo = ' -&gt; ' + row.partner + sizeInfo;
       }

       return row.chr + ':' + row.pos + partnerInfo
   }},
   { title: 'Individual', data: 'sample.sampleId', className: 'sample' },
   { title: 'Samples', data: 'sample_count', className: 'sample_count' },
   { title: 'Reads', data: 'depth', className: 'depth' },
   /*
   { title: 'Samples', data: 'samples', className: 'samples', render: function(data,type,row) {
           return row.samples.join(', ')
       }
   },
   */
   { title: 'Genes', data: 'genes', render: function(data,type,row) {
           window.row = row;
           let genes = row.annotations.genes
           if(!genes)
               return '';
           
           return genes.map(function(geneInfo,i) {
               let g = geneInfo.symbol
               let cds_dist = geneInfo.cdsDistance;
               
               let url = 'http://www.genecards.org/cgi-bin/carddisp.pl?gene='+encodeURIComponent(g)+'&search='+g+'#diseases'
               let distClass = compute_dist_class(cds_dist);
               var geneLists = '';
               /*
               if(model.geneList[g]) {
                   console.log('gene ' + g + ' has gene list')
                   geneLists = model.geneList[g].lists.map(function(gl) {
                       return '<span class=geneListTag>' + gl + '</span>'
                   }).join(' ');
               }
               */
               return '<a href="' + url + '" target=genecards class=genedist'+distClass+'>' + g + '</a> ' + geneLists;
           }).join(", ");
       }
   },
   { title: 'IGV', data: 'chr', render: function(data,type,row) {
           return igv_link('IGV', row.index, row.pos, row.end);
       }
   }
]

var TYPE_COLUMN = TABLE_COLUMNS.findIndex(function(col) {
    return col.title == 'Type';
});

var IGV_COLUMN = TABLE_COLUMNS.findIndex(function(col) {
    return col.title == 'IGV';
});

var INDIVIDUAL_COLUMN = TABLE_COLUMNS.findIndex(function(col) {
    return col.title == 'Individual';
});

var GENES_COLUMN = TABLE_COLUMNS.findIndex(function(col) {
    return col.title == 'Genes';
});

export default {
    
  props: ['cohortId'],
  
  components: {
      'app-header' : AppHeader,
      'app-card' : AppCard,
      'modal' : modal
  },
    
  mounted: function()  {
      
      model.fetchCohortDetails(this.cohortId, this)
      
      axios.get(`/cohort/${this.cohortId}/breakpoints`, {
         dataType: 'json' 
      }).then(req => {
          req.data.breakpoints.forEach(bp => {
              bp.chr = bp.breakpoint.chr
              bp.pos = bp.breakpoint.pos
              bp.samples = bp.breakpoint.samples
              bp.obs = bp.breakpoint.obs
              bp.depth = bp.total
              bp.sample_count = bp.breakpoint.sampleCount
              bp.annotations = bp.breakpoint.annotations
              console.log(bp)
          })
          this.rawBreakpoints = req.data.breakpoints
          window.breakpoints = this.rawBreakpoints;
          this.filterSamples(this)
      })
  },
    
  created: function() {
      
      console.log("Created breakpoints view");
      
      this.highlightedRow = null;
      this.highlightedBp = null;
      
      var that = this;
      var rawBreakpoints = this.rawBreakpoints;
      $(rawBreakpoints).on('breakpoints:change', () => { 
        console.log("Updated breakpoints: " + this.breakpoints.length);
        this.filterSamples(this)
      })
//      this.geneList = Object.values(model.geneList).map(gl => gl.gene).join(' ')
  },
  
  watch: {
      breakpoints: function() {
            console.log("Breakpoints updated!");
            
            window.bps = this.breakpoints;
            var me = this;
            var breakpointCount = this.breakpoints.length;
            if(breakpointCount > 0) {
                console.log('Displaying ' + breakpointCount + ' breakpoints')
                
                let oldSearchText = null;
                if(this.breakpointTable) {
                    oldSearchText = $('#breakpoint-table_filter input')[0].value;
                    this.breakpointTable.destroy()
                }
                
                this.breakpointTable = $('#breakpoint-table').DataTable( {
                    data: this.breakpoints,
                    createdRow: function(row,data,dataIndex) { me.createBreakpointRow(row,data,dataIndex) },
                    columns: TABLE_COLUMNS,
                    pageLength: 15,
                    order: [ [1, 'asc'], [2, 'asc'] ]
                } );
                
                $('#breakpoint-table').css('width','100%')
                
                if(oldSearchText) {
                    this.breakpointTable.search(oldSearchText).draw()
                    $('#breakpoint-table_filter input')[0].value = oldSearchText
                }
            }
            else {
                $('#breakpoint-table tbody').html('<tr><td>No breakpoints</td></tr>')
            }
            

//            else {
//                $('#breakpoints-table').html('Data is still loading ...');
//            }
//        
            /*
            var searchText = breakpoints.get('searchText');
            if(breakpointTable && searchText) {
                console.log("setting search text to " + searchText);
                breakpointTable.search(searchText).draw();
                $('#breakpoint-table_filter input')[0].value = searchText;
            }
            */
      }
  },
  
     /**
     * Adorn a row in the table with extra features
     */
      createBreakpointRow : function(row, data, dataIndex ) {
          
          /*
            var tds = row.getElementsByTagName('td');
            var me = this;
            $(tds[IGV_COLUMN]).find('a').click(function(e) { e.stopPropagation(); me.highlightRow(row); });
            $(tds[GENES_COLUMN]).find('a').click(function(e) { e.stopPropagation(); me.highlightRow(row); });
    
            var typeTd = $(tds[TYPE_COLUMN]);
            var breakpoint_id = data[DATA_COLUMNS.xpos];
            var individual_id = data[DATA_COLUMNS.Individual]
            typeTd.click(function() {
                if(typeTd.hasClass('editingType')) {
    
                }
                else {
                    me.highlightRow(row);
                    var sel = typeTd.html('<select id=' + breakpoint_id + '_type ' + '><option>Select</option>' + 
                        BP_TYPES.map(function(bp_type) { return '<option value="'+bp_type +'">' + bp_type + '</option>'}).join('\n')
                    )
                    typeTd.addClass('editingType');
                    typeTd.find('select').change(function() {
                        var bpType = this.options[this.selectedIndex].value;
                        data[TYPE_COLUMN] = bpType;
                        breakpoints.get('metadatas')[data[DATA_COLUMNS.xpos]] = bpType;
                        typeTd.html(bpType);
                        typeTd.removeClass('editingType');
                        $.post('../../breakpoint/' + breakpoint_id, { 'indiv_id' : individual_id, 'type' : bpType }, function(result) {
                            console.log('Breakpoint updated');
                        });
                    });
    
                    console.log('Created select: ' + sel)
                }
            })
            */
        },

    methods: { 
        
        addTag: function() {
            let tags = model.tags
            let bpId = this.highlightedBp.chr + ':' + this.highlightedBp.pos
            console.log("Save bp id " + bpId)
            this.$modal.show('tag-modal', {
                width: 300,
                height: 100
            })
            setTimeout(() => {
                if(tags[bpId])
                    this.$refs.tagToAddInput.value = tags[bpId]
                else
                    this.$refs.tagToAddInput.value = '';
                this.$refs.tagToAddInput.focus()
            }, 10)
        },
        
        noKeys: function() {
            console.log("NO KEYS")
            noKeys()
        },
        popKeys: function() {
            console.log("KEYS be back!")
            popKeys()
        },
        saveTag: function() {
            console.log('Saving tag');
            
            let bpId = this.highlightedBp.chr + ':' + this.highlightedBp.pos
            model.tags[bpId] = this.$refs.tagToAddInput.value
            model.save()
            this.$modal.hide('tag-modal')
            this.highlightedRow.getElementsByTagName('td')[0].innerHTML = 
                TABLE_COLUMNS[0].render(null, null, this.highlightedBp)
        },
        
        cancelSaveTag: function() {
            this.$modal.hide('tag-modal')
        },
        
        onTagKey: function(e) {
            window.e = e;
            console.log(e)
            if(e.code == "Enter") {
                this.saveTag()
                this.highlightRow
            }
        },
        
        greyBp : function() {
            console.log('GREY')
            let bpId = this.highlightedBp.chr + ':' + this.highlightedBp.pos
            model.greyed[bpId] = model.greyed[bpId] ? false : true
            model.save()
            
            if(model.greyed[bpId])
                $(this.highlightedRow).addClass('greyed')
            else
                $(this.highlightedRow).removeClass('greyed')
        },
        
        createBreakpointRow : function(tr, data, dataIndex ) {
          // console.log("Row created")
          
          let bp = this.breakpoints[dataIndex]
          let bpId = bp.chr + ':' + bp.pos
          if(model.greyed[bpId]) {
              console.log(`${bpId} is greyed!`)
              $(tr).addClass('greyed')
          }
          
         
          $(tr).click((evt) => {
              
              this.highlightRow(bp, tr, dataIndex)
              
            // Only open the detail if the user was NOT clicking on a link
              if(evt.target.tagName != 'A') {
                  
                let componentId = 'breakpoint_'+ bp.chr+'_'+ bp.pos
                if(components[componentId]) {
                    // Show the existing breakpoint tab
                    let existingComponent = components[componentId]
                    let diagramStack = window.layout.root.contentItems[0].contentItems[1]
                    let childElement = diagramStack.contentItems.find(child => child.container == existingComponent.container)
                    if(childElement != null) {
                        diagramStack.setActiveContentItem(childElement)
                        return
                    }
                }
                
                /*
                // Did not successfully locate the existing window: make a new one
                let contentWindow = { // Lower row - command results
                        type: 'component',
                        title: 'Breakpoint Detail',
                        componentName: 'BreakpointDiagram',
                        componentState: { id: componentId, breakpoint: bp, breakpoints: this.breakpoints }, 
                }
                layout.addLowerTab(contentWindow) 
                */
            }
              
          })
        },
        
        highlightRow: function(bp, tr, dataIndex) {
            console.log('adding highlight to ' + tr);
            if(this.highlightedRow)
                $(this.highlightedRow).removeClass('highlight');
            $(tr).addClass('highlight');
            this.highlightedRow = tr;
            this.highlightedBp = bp;
        },
        
        filterBreakpoint: function(excluded_samples, distThreshold, bp) {
                if((bp.depth <= this.obs_filter_threshold) || (bp.sample_count > this.sample_count_filter_threshold))
                    return false;
                
                if(excluded_samples.some(excl => {
                    if(excl instanceof RegExp) {
                        return bp.sample.match(excl) || bp.samples.some(s => s.match(excl))
                    }
                    else {
                        return (bp.sample.sample_id == excl) || (bp.samples.indexOf(excl) > 0)
                    }
                }))
                    return false
                
                return ((distThreshold === false) || (bp.annotations.genes.some(info => (info.cdsDistance >= 0) && (info.cdsDistance <= distThreshold))));
        },
        
        filterSamples: function() {
            console.log("dist category: " + this.gene_proximity)
            let distThreshold =  this.gene_proximity == 'Any' ? false : DIST_THRESHOLDS[this.gene_proximity]
            if(typeof distThreshold == 'undefined')
                distThreshold = false;
            
            console.log("Dist threshold = " + distThreshold)
            
            let self = this;
            let excluded_samples = self.exclude_sample.split(',')
                                       .map(x => x.trim())
                                       .map(x => x.startsWith("~") ? new RegExp(x.slice(1)) : x);
            
            this.breakpoints = this.rawBreakpoints.filter(this.filterBreakpoint.bind(this,excluded_samples,distThreshold)) 
            console.log("There are now "+ self.breakpoints.length + " filtered breakpoints")
            
            window.model.obs_filter_threshold = this.obs_filter_threshold
            window.model.sample_count_filter_threshold = this.sample_count_filter_threshold
            window.model.gene_proximity = this.gene_proximity
            
            window.model.save()
        },
        
        updateObsFilter : function() {
            console.log('Changing min sample obs to ' + this.obs_filter_threshold); 
            this.filterSamples()
        },
        updateSampleCountFilter : function() { 
            console.log('Changing max sample count to ' + this.sample_count_filter_threshold); 
            this.filterSamples()
            console.log("There are now "+ this.breakpoints.length + " breakpoints")
        },
        updateExcludedSamples : function() { 
            console.log('Changing excluded samples to ' + this.exclude_sample); 
            this.filterSamples()
        } ,
        configure: function() {
            this.$modal.show('configuration-modal')
            setTimeout(() => this.$refs.bamFilePrefixInput.focus(), 10)
        },
        configClose: function() {
            model.geneList = _.indexBy(this.geneList.split(' ').map(x => { return { gene: x.trim(), lists: ['Priority'] }}), x => x.gene )
            model.defaultBamFilePrefix = this.bamFilePrefix
            model.save()
        }
    },
  
  
  data: function() {
      return {
          
          cohort: null,
          
          /**
           * The unfiltered breakpoints
           */
          rawBreakpoints: [],
          
          /**
           * The set of breakpoints actually displayed
           */
          breakpoints: [],
          
          /**
           * Currently highlighted row
           */
          highlightedRow: null,
          
          highlightedBp: null,
          
          obs_filter_levels: [1,2,3,4,5,6,7,8,9,10,15,20,30,50],
          sample_count_filter_levels: [1,2,3,4,5,6,7,8,9,10,15,20,30,50,100],
          obs_filter_threshold: 3,
          sample_count_filter_threshold: 10,
          breakpointTable: null,
          exclude_sample: '',
          bamFilePrefix: model.defaultBamFilePrefix || '',
          gene_proximity: "none",
          geneList: '',
          DIST_CLASS_DESC: DIST_CLASS_DESC,
          tagToAdd: null
      }
  },
}
</script>
