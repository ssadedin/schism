//= require vue.js

Vue.use(window['vue-js-modal'].default)

var groupBy = window.groupBy;

var BP_TYPES = ['noise','deletion','insertion','microduplication',
    'duplication','inversion','complex sv','sv','contamination',
    'adapter','gcextreme',
    'chimeric read','multimapping','badref',
    'common','unknown'];

var DATA_COLUMNS = {
'xpos' : 0,
'Contig' : 1,
'Position' : 2,
'Partner' : 6,
'Samples' : 4,
'Sample Obs' : 3,
'Individual' : 7,
'Genes' : 8
}

var removeChrRegex = /^chr/;

function igv_url_for_sample(sample) {
    var base='http://localhost:60151/';
    if(window.bamFiles && bamFiles[sample]) {
        let breakpointPath = bamFiles[sample]
        if(components.BreakpointsView.bamFilePrefix) {
            breakpointPath = components.BreakpointsView.bamFilePrefix + '/' + breakpointPath.replace(/^.*\//, '')
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
        if(components.BreakpointsView.bamFilePrefix) {
            breakpointPath = components.BreakpointsView.bamFilePrefix + '/' + breakpointPath.replace(/^.*\//, '')
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
   { title: 'Position', data: 'start', render: function(data,type,row) {
       var sizeInfo = '';
       var partnerInfo = ''
       if(row.partner) {
           var chr = row.chr.replace(removeChrRegex,'');
           var pos = row.start;

           var partnerChrSplit = row.partner.split(':');
           var partnerChr = partnerChrSplit[0].replace(removeChrRegex,'');

           if(chr == partnerChr)  {
               var partnerPos = parseInt(partnerChrSplit[1],10);
               console.log('partnerPos = ' + partnerPos);
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

       return row.chr + ':' + row.start + partnerInfo
   }},
   { title: 'Individual', data: 'sample', className: 'sample' },
   { title: 'Samples', data: 'sample_count', className: 'sample_count' },
   { title: 'Sample Obs', data: 'depth', className: 'depth' },
   { title: 'Samples', data: 'samples', className: 'samples', render: function(data,type,row) {
           return row.samples.join(', ')
       }
   },
   { title: 'Genes', data: 'genes', render: function(data,type,row) {
           window.row = row;
           if(!row.genes)
               return '';
           
           return row.genes.map(function(g,i) {
               let url = 'http://www.genecards.org/cgi-bin/carddisp.pl?gene='+encodeURIComponent(g)+'&search='+g+'#diseases'
               let cds_dist = row.cdsdist[i];
               let distClass = compute_dist_class(cds_dist);
               var geneLists = '';
               /*
               if(GENE_LISTS[g.gene]) {
                   geneLists = GENE_LISTS[g.gene].map(function(gl) {
                       return '(<span class=geneListTag>' + gl + '</span>)'
                   }).join(' ');
               }
               */
               return '<a href="' + url + '" target=genecards class=genedist'+distClass+'>' + g + '</a> ' + geneLists;
           }).join(", ");
       }
   },
   { title: 'IGV', data: 'chr', render: function(data,type,row) {
           return igv_link('IGV', row.index, row.start, row.end);
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

Vue.component('BreakpointsView', {
    
//  constructor(props) {
////      super(props);
//      
//      var breakpoints = props.breakpoints;
//      this.state = {breakpoints: breakpoints, breakpointCount: 0, loaded: false};
//      this.displayOverview = this.displayOverview.bind(this);
//  }
   
  created: function() {
      
      console.log("Created breakpoints view");
      
      this.highlightedRow = null;
      
      var that = this;
      var breakpoints = model.breakpoints;
      $(breakpoints).on('breakpoints:change', () => { 
        console.log("Updated breakpoints: " + model.breakpoints.breakpoints.length);
        this.filterSamples(this)
      })
  },
  
  watch: {
      breakpoints: function() {
            console.log("Breakpoints updated!");
            
            window.bps = this.breakpoints;
            var me = this;
            var breakpointCount = this.breakpoints.length;
            if(breakpointCount > 0) {
                console.log('Displaying ' + breakpointCount + ' breakpoints')
                
                if(this.breakpointTable)
                    this.breakpointTable.destroy()
                
                this.breakpointTable = $('#breakpoint-table').DataTable( {
                    data: this.breakpoints,
//                    createdRow: function(row,data,dataIndex) { me.createBreakpointRow(row,data,dataIndex) },
                    columns: TABLE_COLUMNS,
                    pageLength: 15
                } );
                
                $('#breakpoint-table').css('width','100%')
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

    highlightRow: function(tr) {
        console.log('adding highlight to ' + tr);
        if(this.highlightedRow)
            $(this.highlightedRow).removeClass('highlight');
        $(tr).addClass('highlight');
        this.highlightedRow = tr;
    },
    
    methods: { 
        
        filterBreakpoint: function(excluded_samples, distThreshold, bp) {
                if((bp.depth <= this.obs_filter_threshold) || (bp.sample_count > this.sample_count_filter_threshold))
                    return false;
                
                if(excluded_samples.some(excl => {
                    if(excl instanceof RegExp) {
                        return bp.sample.match(excl) || bp.samples.some(s => s.match(excl))
                    }
                    else {
                        return (bp.sample == excl) || (bp.samples.indexOf(excl) > 0)
                    }
                }))
                    return false
                
//                if((excluded_samples.indexOf(bp.sample) >= 0) ||  excluded_samples.some( s => bp.samples.indexOf(s)>=0))
//                    return false;
                    
                return ((distThreshold === false) || (bp.cdsdist &&  bp.cdsdist.some(d => (d >= 0) && (d <= distThreshold))));
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
            
            this.breakpoints = model.breakpoints.breakpoints.filter(this.filterBreakpoint.bind(this,excluded_samples,distThreshold)) 
            console.log("There are now "+ self.breakpoints.length + " filtered breakpoints")
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
        }
    },
  
  
  data: function() {
      return {
          breakpoints: model.breakpoints,
          highlightedRow: null,
          obs_filter_levels: [1,2,3,4,5,6,7,8,9,10,15,20,30,50],
          sample_count_filter_levels: [1,2,3,4,5,6,7,8,9,10,15,20,30,50],
          obs_filter_threshold: 3,
          sample_count_filter_threshold: 10,
          breakpointTable: null,
          exclude_sample: '',
          bamFilePrefix: null,
          gene_proximity: "none",
          DIST_CLASS_DESC: DIST_CLASS_DESC
      }
  },
  
  template: `
      <div class='content_panel_wrapper'>
        <div class="container">
            <div id="form-container">
            
                <label for=exclude_sample>Exclude</label>
                <input v-model="exclude_sample" placeholder="Exclude samples" v-on:change="updateExcludedSamples">
            
                <label for=obs_filter_threshold>Min Reads</label>
                <select v-model="obs_filter_threshold" v-on:change="updateObsFilter">
                    <option v-for="option in obs_filter_levels" v-bind:value="option">
                    {{ option }}
                    </option>
                </select>
                
                <label for=obs_filter_threshold>Max Samples</label>
                
                <select v-model="sample_count_filter_threshold" v-on:change="updateSampleCountFilter">
                    <option v-for="option in sample_count_filter_levels" v-bind:value="option">
                    {{ option }}
                    </option>
                </select> 
                
                Gene Proximity
                <select v-model="gene_proximity" v-on:change="filterSamples">
                    <option v-for="option in Object.keys(DIST_CLASS_DESC)" v-bind:value="option">
                        {{DIST_CLASS_DESC[option]}}
                    </option>
                </select>
                
                <a href='#' v-on:click.prevent='configure'><span class='configure'>&#x2699;</span></a>
                
                <modal name='configuration-modal'>
                    <div class=content_panel_wrapper>
                        <h2>Configuration</h2>
                        <label class='left'>Prefix for Accessing BAM Files: </label>
                        <div class='rightFill'><input type='text' ref='bamFilePrefixInput' v-model='bamFilePrefix'></div>
                    </div>
                    
                </modal>
                
            </div>
        </div>
        
        <div class="container">
            <div id="search-controls-container">
            </div>
        </div>
        
        <div class="container">
            <div id="results-container"></div>
        </div>
          
        <table id='breakpoint-table' class='stripe'>
              <thead>
              </thead>
              <tbody>
              </tbody>
        </table>
      </div>
      `
})

Vue.component('SummaryView', {
    
    created: function() {
        console.log('Creating SummaryView Component')
        $(model.breakpoints).on('breakpoints:change', () => {
            this.breakpoints = model.breakpoints.breakpoints;
        })
    },
    
    data: function() {
        return {
            breakpoints: []
        }
    },
    
    template: `
        <div>
            <div className='panel_content_wrapper'>
                <h2>Overview</h2>
                <table className='overviewTable'>
                <thead>
                    <tr><th>Number of Breakpoints</th></tr>
                    <tr><td>{{ breakpoints.length }}</td></tr>
                </thead>
                <tbody>
                </tbody>
                </table>
                        
                <h2>Breakpoints</h2>

            </div>
        </div>
    `
})
