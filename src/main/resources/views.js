//= require vue.js

Vue.use(window['vue-js-modal'].default)

var groupBy = window.groupBy;

/************************ Breakpoint Diagrams **********************************/

let markerId = 0
Vue.component('BreakpointDiagram',{
    
    props: ['breakpoint'],
    
    created: function() {
        // Don't know why I have to set this here?
        // VueJS should do it for me if the prop is provided?
//        this.breakpoint = this.$options.breakpoint;
    },
    
    mounted: function() {
        
        let bp = this.breakpoint;
        
        window.bp = bp;
        
        // Start by ordering the genes by start position
        let genes = bp.genes.map(g => { return { gene: g, info: model.breakpoints.genes[g] }})
                            .filter(g => g.info.exons.length>0)
                            .map((geneAndInfo,i) => {
                                let g = geneAndInfo.info;
                                return {
                                    gene: geneAndInfo.gene,
                                    index: i,
                                    start: g.exons[0].from,
                                    end: g.exons[g.exons.length-1].to,
                                    exons: g.exons,
                                    strand: g.strand
                                }
                            })
                            
        genes = _.sortBy(genes, g => g.start)
        genes.forEach(g => g.exons.forEach(e => e.gene = g))
                            
        let xPadding = 80;
        
        let plotLayout = {
            intronSize : 10,
            xPadding: xPadding,
            width: 1280,
            xOffset : xPadding / 2,
            baselineY : 50,
            exonHeight : 14,
            labelFontSize: 9, // Font size in px
            xScale : null, // populated later
            geneGap : 30,
            geneY: 100,
            geneColors: ['#3a3','#5c5'],
            breakpointHeight: 20,
            genomePadding: 100 // Bases upstream / downstream to display
        }
        
        this.computeScales(bp, genes, plotLayout)
        
        console.log("Domains are: " + plotLayout.domains);
        console.log("Ranges are: " + plotLayout.ranges);

        var xScale = d3.scale.linear()
            .domain(plotLayout.domains)
            .range(plotLayout.ranges);
        
        plotLayout.xScale = xScale;
        
        var yScale = d3.scale.linear()
                 .domain([-0.25,2.5])
                 .range([0,200]);
  
        let starts = genes.map(g => g.start)
        let widths = genes.map(g => g.end - g.start)
        
        let xs = starts.map(xScale)
        let ys = starts.map(s => yScale(0))
        let heights = starts.map(s => yScale(1))
        
        let svg = d3.select(this.$refs.svg)
        
        this.addEndMarker(svg, plotLayout)
        window.svg = svg;
        window.xScale = plotLayout.xScale;
        
        this.drawMidline(plotLayout, svg)
        this.drawGenes(plotLayout, svg, genes)
        this.drawBreakpoints(plotLayout, svg, [bp])
        this.drawFrame(plotLayout, svg)
    },
    
    methods: {
        
        drawMidline: function(plotLayout, svg) {
            
            let frameHeight = 150
            let frameBaselineY = plotLayout.baselineY-35 
            let midlineY = plotLayout.geneY
            
            svg.append('line')
               .attr('x1', 2)
               .attr('y1', midlineY)
               .attr('x2', plotLayout.width)
               .attr('y2', midlineY)
               .attr('class', 'breakpointFrame')
        },
        
        drawFrame: function(plotLayout, svg) {
            
            let domains = plotLayout.domains
            let ranges = plotLayout.ranges
            let xScale = plotLayout.xScale;
            
            let frameHeight = 150
            let frameBaselineY = plotLayout.baselineY-35
                
            svg.append('rect')
               .attr('x', 2)
               .attr('y', frameBaselineY)
               .attr('width', plotLayout.width-2)
               .attr('height', frameHeight)
               .attr('class', 'breakpointFrame')
               .attr('rx',2)
               .attr('ry',2)
               
           let tickBaseline = frameBaselineY-5+frameHeight
           
           // Mark 10 positions along the frame
           let tickRange = [domains[0],domains[domains.length-1]]
           let tickWidth = tickRange[1] - tickRange[0]
           console.log("Tick width = " + tickWidth)
            
           let tickGaps = [10,50,100,200,500,1000,5000,10000,20000,50000,100000,200000,500000].reverse()
            
           let tickGapIndex = _.findIndex(tickGaps, gap => 10 * gap < tickWidth)
           if(tickGapIndex < 0) {
               console.log("WARNING: unable to find a good tick interval for labeling genome graph")
               return
           }
            
           let tickGap = tickGaps[tickGapIndex]
           
           // Round the minimum to the same resolution as the tick gap interval
           let tickMin = Math.floor(domains[0] / tickGap) * tickGap
           let tickMax = tickRange[1] - tickGap
           
           console.log("Tick gap index = " + tickGapIndex + " tick width = " + tickGap + " tick min = " + tickMin)
           let tickIndices = []
           let i = 0
           while(tickMin + (i*tickGap) < tickMax) {
               tickIndices.push(i++)
           }
           
           // Draw the tick marks
           svg.selectAll('path.frameTicks')
              .data(tickIndices)
              .enter()
              .append('path')
              .attr('d', tickNum => { return `M ${xScale(tickMin + tickNum*tickGap)} ${tickBaseline-2} L ${xScale(tickMin+tickNum*tickGap)} ${tickBaseline+5}` } )
              .attr('class','frameTick')
              
              
           // Draw the tick labels
           svg.selectAll('text.tickLabel')
              .data(tickIndices)
              .enter()
              .append('text')
              .attr('x', tickNum => xScale(tickMin + tickNum*tickGap) - 20)
              .attr('y', tickBaseline+15)
              .text( tickNum => tickMin + tickNum*tickGap)
              .attr('class', 'tickLabel')
  
          svg.append('text')
             .attr('x', (ranges[1] + ranges[0]) / 2)
             .attr('y', tickBaseline+35)
             .attr('class', 'tickChromosomeLabel')
             .text('Chromosome ' + this.breakpoint.chr.replace('chr',''))
        },
        
        
        
        addEndMarker: function(svg, plotLayout) {
            plotLayout.arrowMarkerId = 'arrow-'+markerId++;
            
            svg.append("svg:defs").append("svg:marker")
               .attr("id", plotLayout.arrowMarkerId)
               .attr("refX", 6)
               .attr("refY", 3)
               .attr("markerWidth", 30)
               .attr("markerHeight", 30)
               .attr("orient", "auto")
               .append("path")
               .attr("d", "M 0 0 6 3 0 6 1.5 3")
               .attr("class", "partnerArrows")
        },
        
        drawBreakpoints: function(plotLayout, svg, breakpoints) {
            
            let xScale = plotLayout.xScale;
            let domains = plotLayout.domains
            
            let samples = breakpoints.map(bp => bp.sample)
            let chrs = breakpoints.map(bp => bp.chr)
            
            let newBreakPointEls = 
                svg.selectAll('rect.breakpoint') 
                   .data(breakpoints)
                   .enter()
                   
            let secondaryBreakpoints = model.breakpoints.breakpoints.filter(bp => {
                if(samples.indexOf(bp.sample) < 0)
                    return false
                    
                if(chrs.indexOf(bp.chr) < 0)
                    return false
                     
                return (bp.start > domains[0]) && (bp.end < domains[domains.length-1])
            })
            
            console.log(`Drawing ${secondaryBreakpoints.length} secondary breakpoints`)
            
            let secondaryBreakPointEls = 
                svg.selectAll('rect.otherbreakpoint') 
                   .data(secondaryBreakpoints)
                   .enter()
            
            this.appendBreakpointSymbol(plotLayout, secondaryBreakPointEls, {color: '#f77'})
            this.appendBreakpointSymbol(plotLayout, newBreakPointEls, {color: '#b22'})
            
            // Link any secondary breakpoints to the primary
            let secondaryBreakpointIndex = _.indexBy(secondaryBreakpoints.filter(bp => bp.partner != ""), 'partner')
            
            let partners = breakpoints.filter(bp => bp.partner != "")
                                      .concat(secondaryBreakpoints.filter(sbp => sbp.partner != ""))
            
            
            console.log(`Drawing ${partners.length} partners`)
            
            let yOffset = 0;
            
            let inDomains = function(x) {
                return x > domains[0] && x < domains[domains.length-1]
            }
           
            // Draw links to partners
            svg.selectAll('path.partnerLink')
               .data(partners)
               .enter()
               .append('path')
               .attr('class','partnerLink')
               .attr('d', bp => {
                   let [chr,pos] = bp.partner.split(':')
                   let linkY = plotLayout.baselineY -1
                   if(chr == bp.chr) {
                       if(inDomains(bp.start) && !inDomains(pos)) {
                           return `M ${xScale(bp.start)} ${linkY} L ${xScale(bp.start)+Math.sign(pos - bp.start)*30} ${linkY}`
                       }
                       else
                       if(!inDomains(bp.start) && inDomains(pos)) {
                           return `M ${xScale(bp.start)} ${linkY} L ${xScale(bp.start)+Math.sign(bp.start - pos)*30} ${linkY}`
                       }                       
                       else {
                           return `M ${xScale(bp.start)} ${linkY} Q ${(xScale(pos) + xScale(bp.start))/2} ${linkY-30-(yOffset++)} ${xScale(pos)} ${linkY}`
                       }
                   }
                   else {
                       console.log(`Partner ${bp.partner} on different chromosome: ${chr}`)
                   }
               })
               .attr('marker-end', `url(#${plotLayout.arrowMarkerId})`)
        },
          
        appendBreakpointSymbol(plotLayout, d3Els, props) {
              d3Els.append('rect')
                   .attr('x', (bp) => xScale(bp.start) -1)
                   .attr('width', (bp) => 2)
                   .attr('y', (bp) => plotLayout.baselineY)
                   .attr('height', (bp) => plotLayout.breakpointHeight)
                   .attr('style', (g,i) => `stroke:${props.color};stroke-width:1;fill:${props.color};`)
                   .attr('rx',2)
                   .attr('ry',2)
        },
        
        drawGenes: function(plotLayout, svg, genes) {
                
            let allExons = [].concat.apply([],genes.map(g => g.exons))
            
            let geneY = plotLayout.geneY
            let exonHeight = 15
            
            // debug
            // window.genes = genes;
            window.exons = allExons;

            let geneColors = plotLayout.geneColors
            
            svg.selectAll('rect.geneRect')
               .data(genes).enter()
               .append('rect')
                  .attr('x',(g) => { 
                      console.log("gene " + g.gene + " start: " + g.start + ", exons[0].to="+xScale(g.exons[0].to)); 
                      return xScale(g.exons[0].to);
                   })
                  .attr('class','geneRect')
                   .attr('y',(g) => geneY-2)
                   .attr('width',(g) => { 
                       let w = xScale(g.exons[g.exons.length-1].from) - xScale(g.exons[0].to)
                       return w;
                   }) 
                   .attr('height',(g) => 4)
                   .attr('style', (g,i) => `stroke:${geneColors[i%2]};stroke-width:1;fill:${geneColors[i%2]};`)
                   .attr('rx',2)
                   .attr('ry',2)
                   
                   
            genes.forEach(gene => {
                if(gene.strand != null) {
                    let strandSymbol = (gene.strand == '+') ? "▶" : "◀"
                    
                    console.log('gene strand for ' + gene.gene)
                    
                    // Put a right facing arrow in between each exon
                    svg.selectAll(gene.gene+'.geneArrows')
                       .data(gene.exons.slice(0,-1))
                       .enter()
                       .append('text')
                          .attr('x',(exon,i) => { return (xScale(exon.to) + xScale(gene.exons[i+1].from))/2 - 6 })
                          .attr('y',(exon,i) => geneY+4)
                          .attr('class','geneStrandArrow')
                          .attr('style',(e,i) => { 
                               let color=geneColors[e.gene.index%geneColors.length]; 
                               return `stroke:${color};stroke-width:1;fill:${color};`
                          }) 
                          .text(exon => strandSymbol)
                }
            })
                   
            let geneLabels = Object.values(_.groupBy(genes, g => Math.round((g.start + g.end / 2)/10)))
            
            svg.selectAll('text.geneLabel')
               .data(geneLabels).enter()
               .append('text')
                  .attr('x',(genes) => { return (xScale(genes[0].end) + xScale(genes[0].start))/2 })
                  .attr('y',(genes) => geneY+25)
                  .attr('class','geneLabel')
                  .text(genes => genes.map(g => g.gene).join(", "))
            
            svg.selectAll('rect.exonRect')
               .data(allExons).enter()
               .append('rect')
                  .attr('x',(e) => { 
                      return xScale(e.from);
                   })
                  .attr('class','exonRect')
                   .attr('y',(e) => geneY-exonHeight)
                   .attr('width',(e) => xScale(e.to) - xScale(e.from))
                   .attr('height',(e) => exonHeight*2)
                   .attr('style',(e,i) => { 
                       let color=geneColors[e.gene.index%geneColors.length]; 
                       return `stroke:;stroke-width:1;fill:${color};`
                    })
                   .attr('rx',2)
                   .attr('ry',2)
        },
        
        computeScales: function(bp, genes, plotLayout) {
            let sum = (x) => x.reduce((acc,n) => acc+n, 0)
            
            
            // Find the minimum of the exons and the bp
            let minPos = bp.start - 500
            let maxPos = bp.start + 500
            if(genes.length > 0) {
                minPos = Math.min(bp.start, genes[0].start)
                maxPos = Math.max(bp.start, genes[genes.length-1].end)
            }
            
            let width = plotLayout.width - plotLayout.xPadding 
            
            let domains = [minPos-plotLayout.genomePadding, maxPos + plotLayout.genomePadding]
            let ranges = [plotLayout.xPadding, plotLayout.width - plotLayout.xPadding]
            Object.assign(plotLayout,{
                domains : domains,
                ranges: ranges
            })
        },
        
        /**
         * Not currently used - shows exons at fixed size and scales introns
         * to fit in gaps. Since most breakpoints are actually in introns I don't
         * think this is as useful, but there may still be a case for using a different scale
         * for introns vs exons
         */
        computeScalesExpandedExons: function(bp, genes, plotLayout) {
            
            let sum = (x) => x.reduce((acc,n) => acc+n, 0)
            
            let bases = sum(genes.map((g) => sum(g.exons.map(e => e.to - e.from))))
            let numIntrons = sum(genes.map(g => g.exons.length-1))
            
            let domains = [];
            
            // We construct the x-scale piecewise with gaps between each
            // target region
            let ranges = [];
            let geneGap = plotLayout.geneGap
            let widthForExonicBases = plotLayout.width - plotLayout.xPadding - plotLayout.intronSize*numIntrons - geneGap*(genes.length-1)
            window.widthForExonicBases = widthForExonicBases;
            let scaleFactor = (widthForExonicBases) / bases;
            let prevEnd = plotLayout.xOffset;
            let prev = null
            
            if(bp.start < genes[0].exons.from) {
                domains.push(bp.start)
                ranges.push()
                prev = {
                    start: bp,
                    end : genes[0].exons.to
                }
            }
                
            genes.forEach(function(g) { 
               // Add a gap between genes
               if(prev != null) {
                    domains.push(prev.end)
                    domains.push(g.start)
                    
                    ranges.push(prevEnd)
                    ranges.push(prevEnd+geneGap)
                    prevEnd = prevEnd+geneGap 
                }
               
                g.exons.forEach(e => { domains.push(e.from); domains.push(e.to); })
                
                g.exons.forEach(e => {
                    ranges.push(prevEnd); 
                    var targetEnd = prevEnd + (scaleFactor*(e.to-e.from));
                    ranges.push(targetEnd); 
                    prevEnd = targetEnd + plotLayout.intronSize;
                }) 
                prev = g
            });
            plotLayout.ranges = ranges;
            plotLayout.domains = domains;
        }
    },
    
    data: function() {
        return {
            breakpoint: null
        }
    },
    
    template: `
    <div>
        <h3>Breakpoint at {{breakpoint.chr}}:{{breakpoint.start}}</h3>
        <svg ref='svg' style='height: 300px; width: 1280px'></svg>
    </div>
    `
})

/************************ Breakpoint Table **********************************/

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
           let bpId = row.chr + ':' + row.start
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
           var pos = row.start;

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
               if(model.geneList[g]) {
                   console.log('gene ' + g + ' has gene list')
                   geneLists = model.geneList[g].lists.map(function(gl) {
                       return '<span class=geneListTag>' + gl + '</span>'
                   }).join(' ');
               }
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

Vue.component('BreakpointTagDialog', {
    data: function() {
        return {
            show: false
        }
    }
})



Vue.component('BreakpointsView', {
    
  created: function() {
      
      console.log("Created breakpoints view");
      
      this.highlightedRow = null;
      this.highlightedBp = null;
      
      var that = this;
      var breakpoints = model.breakpoints;
      $(breakpoints).on('breakpoints:change', () => { 
        console.log("Updated breakpoints: " + model.breakpoints.breakpoints.length);
        this.filterSamples(this)
      })
      this.geneList = Object.values(model.geneList).map(gl => gl.gene).join(' ')
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
            let bpId = this.highlightedBp.chr + ':' + this.highlightedBp.start
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
            
            let bpId = this.highlightedBp.chr + ':' + this.highlightedBp.start
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
            let bpId = this.highlightedBp.chr + ':' + this.highlightedBp.start
            model.greyed[bpId] = model.greyed[bpId] ? false : true
            model.save()
            
            if(model.greyed[bpId])
                $(this.highlightedRow).addClass('greyed')
            else
                $(this.highlightedRow).removeClass('greyed')
        },
        
        createBreakpointRow : function(tr, data, dataIndex ) {
          console.log("Row created")
          
          let bp = this.breakpoints[dataIndex]
          let bpId = bp.chr + ':' + bp.start
          if(model.greyed[bpId]) {
              console.log(`${bpId} is greyed!`)
              $(tr).addClass('greyed')
          }
          
         
          $(tr).click((evt) => {
              
              this.highlightRow(bp, tr, dataIndex)
              
            // Only open the detail if the user was NOT clicking on a link
              if(evt.target.tagName != 'A') {
                  
                let componentId = 'breakpoint_'+ bp.chr+'_'+ bp.start
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
                
                // Did not successfully locate the existing window: make a new one
                let contentWindow = { // Lower row - command results
                        type: 'component',
                        title: 'Breakpoint Detail',
                        componentName: 'BreakpointDiagram',
                        componentState: { id: componentId, breakpoint: bp }, 
                }
                addLowerTab(contentWindow) 
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
                        return (bp.sample == excl) || (bp.samples.indexOf(excl) > 0)
                    }
                }))
                    return false
                
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
          breakpoints: model.breakpoints,
          highlightedRow: null,
          highlightedBp: null,
          obs_filter_levels: [1,2,3,4,5,6,7,8,9,10,15,20,30,50],
          sample_count_filter_levels: [1,2,3,4,5,6,7,8,9,10,15,20,30,50],
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
  
  template: `
      <div class='content_panel_wrapper' >
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
                
                <modal name='configuration-modal' @closed='configClose'>
                    <div class=content_panel_wrapper>
                        <h2>Configuration</h2>
                        <label class='left'>Prefix for Accessing BAM Files: </label>
                        <div class='rightFill'><input type='text' ref='bamFilePrefixInput' v-model='bamFilePrefix'></div>
                        <label class='left'>Gene List:</label>
                        <div class='rightFill'><textarea ref='geneListTextArea' v-model='geneList' rows=5 cols=40></textarea></div>
                    </div>
                    
                </modal>
                
                <modal name='tag-modal' :width='330' :height=140 @opened='noKeys' @closed='popKeys'>
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
