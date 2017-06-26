//= require vue.js

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

var BAM_FILES = [];

var removeChrRegex = /^chr/;

function igv_url_for_sample(sample) {
    var base='http://localhost:60151/';
    if(BAM_FILES[sample]) {
        return base + 'load?file='+encodeURIComponent(BAM_FILES[sample]) + '&';
    }
    else {
        return base + 'goto?';
    }
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
                   '<a href="' + igv_url_for_sample(row.sample) + 'locus='+chr+ ':' + (pos-600) +'-'+(partnerPos+600)+ '" target=igv>' +
                                eventSize +  'bp' + '</a>)</span>';
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
   { title: 'Genes', data: 'genes', render: function(data,type,row) {
           window.row = row;
           if(!row.genes)
               return '';
           
           return row.genes.map(function(g,i) {
               let url = 'http://www.genecards.org/cgi-bin/carddisp.pl?gene='+encodeURIComponent(g)+'&search='+g+'#diseases'
               let cds_dist = row.cdsdist[i];
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
           return '<a href="'+igv_url_for_sample(row.sample) 
                             + 'locus='+row.chr + ':' + 
                  (row.start-300) +'-'+(row.end+300)+ '" target=igv>IGV</a>';
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
        this.breakpoints = model.breakpoints.breakpoints;
      })
  },
  
  watch: {
      breakpoints: function() {
            console.log("Breakpoints updated!");
            
            window.bps = this.breakpoints;
            var me = this;
            var breakpointCount = this.breakpoints.length;
            var breakpointTable = null;
            if(breakpointCount > 0) {
                console.log('Displaying ' + breakpointCount + ' breakpoints')
                breakpointTable = $('#breakpoint-table').DataTable( {
                    data: this.breakpoints,
//                    createdRow: function(row,data,dataIndex) { me.createBreakpointRow(row,data,dataIndex) },
                    columns: TABLE_COLUMNS,
                    pageLength: 20
                } );
                
                $('#breakpoint-table').css('width','100%')
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
    
    events: { // todo
        'change #sample_obs' : function() { 
            var obs = parseInt($('#sample_obs').val(),10);
            console.log('Changing min sample obs to ' + obs); 
            breakpoints.set('minSampleObs',obs)
        },
        'change #max_sample_count' : function() { 
            var maxSampleCount = parseInt($('#max_sample_count').val(),10);
            console.log('Changing max sample count to ' + maxSampleCount); 
            breakpoints.set('maxSampleCount',maxSampleCount)
        } 
    },
  
  
  data: function() {
      return {
          breakpoints: model.breakpoints,
          highlightedRow: null
      }
  },
  
  template: `
      <div>
        <div class="container">
            <div id="form-container"></div>
        </div>
        
        <div class="container">
            <div id="search-controls-container"></div>
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