/**
 * 
 */
if (typeof jQuery !== 'undefined') {
    (function($) {
        $(document).ajaxStart(function() {
            $('#spinner').fadeIn();
        }).ajaxStop(function() {
            $('#spinner').fadeOut();
        });
    })(jQuery);
}

/**
 * Load data assumed to be in array form from the given list of 
 * sources and call the callback when finished. The loaded data
 * is assumed to be in the form:
 * 
 * <code>
 * js_load_data = &gt;json&lt;
 * </code>
 * 
 * @param callback
 * @param srcs
 * @param results
 * @returns
 */
function loadJs(callback, srcs, results) {
    
    console.log("Loading data from: " + srcs.join(','));
   
    var oldScriptElement = document.getElementById('js_load_script');
    if(oldScriptElement)
        oldScriptElement.parentNode.removeChild(oldScriptElement);
    
    let src = srcs.pop();
    
    console.log(`Loading data from src ${src}`);

    const script = document.createElement("script");
    script.id = 'js_load_script';
    script.src = src;
    script.async = true;
    
    if(!results)
        results = [];
    
    let mergeResults =  () => {  
        results = results.concat(js_load_data);
    };
    
    if(srcs.length > 0) {
        script.onload = () => {
            mergeResults()
            loadCnvs(callback, srcs, results);
        }; 
    }
    else {
        script.onload = () => {
            mergeResults()
            callback(results)
        };
    }
    
    console.log("data from " + script.src);
    document.body.appendChild(script); 
}

function loadAndCall(srcs, fn) {
    console.log("loadAndCall");
    loadJs(fn, srcs.map(b => b)) 
}

var components = {};

var config = {
    content: [
        {
            type: 'column',
            isClosable: false,
            content: [ 
                {   
                    type: 'row', // Top row - BreakpointsView
                    content:[
                        { 
                            type: 'stack',
                            content:[{
                                type: 'component',
                                title: 'Breakpoints',
                                componentName: 'BreakpointsView',
                                componentState: { }
                            }]
                        }]
                } // Note: after this will be added breakpoint detail views
        ]
  }]
};

function getMainColumn() {
   return window.layout.root.contentItems[0]; 
}

function addLowerTab(contentWindow) {
    let mainColumn = getMainColumn()
            
    // If there is no 2nd child, we add a stack to the main column
    if(mainColumn.contentItems.length == 1) {
        mainColumn.addChild({
            type: 'stack',
            content: [contentWindow]
        })
    }
    else {
        mainColumn.contentItems[1].addChild(contentWindow);
    }
}


function mountVueComponent(container, id, componentName, props) {
    
    console.log('Mount vue component ' + componentName + ' in ' + id)
    
    container.getElement().html( `<div id='${id}'></div>` );
    let componentConstructor = Vue.component(componentName)
    let component = new componentConstructor({data:props})
    component.container = container;
    components[id] = component;
    setTimeout(function() {
        component.$mount('#'+id)
    }, 0) 
}

var layout = null;

/**
 * Registers a Vue component with GoldenLayout so that GoldenLayout can
 * create it on demand. This implementation relies on each GoldenLayout 
 * component containing an 'id' property in its state, which is used to ensure
 * the Vue instance binds to a unique, identifiable DOM element.
 * 
 * @param componentName
 * @returns
 */
function registerVueComponent(componentName) {
    layout.registerComponent(componentName, function(container, componentState) {
        let id = componentState.id || componentName;
        mountVueComponent(container, id, componentName, componentState)
    })
}

function getBreakpointView() {
    return components.BreakpointsView
}


BREAKPOINT_LIST_SHORTCUT_KEYS = {
        84: function() {
           let bpView = getBreakpointView()
           if(bpView.highlightedRow) {
               let bp = bpView.highlightedBp
               console.log('Tagging breakpoint: ' + bp.chr + ':' + bp.start )
               bpView.addTag()
           }
        },
        
        70: function() {
           getBreakpointView().greyBp()
        }
}

SHORTCUT_KEYS = [BREAKPOINT_LIST_SHORTCUT_KEYS]

function noKeys() {
    SHORTCUT_KEYS.push({})
}

function popKeys() {
    SHORTCUT_KEYS.pop({})
}

function keyHandler(e) {
    console.log('key: ' + e.keyCode)
    if(_.last(SHORTCUT_KEYS)[e.keyCode]) {
        _.last(SHORTCUT_KEYS)[e.keyCode]()
    }
}

$(document).ready(function() {
    
    window.model = {
        breakpoints : new Breakpoints({dataFiles: breakpoint_srcs}),
        defaultBamFilePrefix : null,
        genes: [],
        id: location.host + ':' + location.pathname,
        tags: {},
        greyed: {},
        save: function() {
            console.log('Saving to: ' + 'schism-'+model.id)
            store.set('schism-'+model.id, {
                tags: window.model.tags,
                greyed: window.model.greyed
            })
        },
        load: function() {
            Object.assign(window.model, store.get('schism-'+model.id) || {})
        }
    }
    
    model.load()
    
    model.breakpoints.load()
    
    layout = new GoldenLayout( config, $('#content')[0] );
    
    registerVueComponent('SummaryView')
    registerVueComponent('BreakpointsView')
    registerVueComponent('BreakpointDiagram')
   
    let footer = $('.footer')
    let footerHeight = footer.outerHeight()
    
    $('#content').height($(window).height() - footerHeight - 18 - $('#header').outerHeight())
    
    layout.init()
    
    $(document.body).keyup(keyHandler);
    
    console.log("Layout initialized");
    
})
