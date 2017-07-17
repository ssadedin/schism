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
                type: 'row',
                content: [{
                                type: 'stack',
                                content:[
//                                   {
//                                        type: 'component',
//                                        componentName: 'SummaryView',
//                                        componentState: { breakpoints: [] },
//                                        isClosable: false, 
//                                   },
                                   {
                                            type: 'component',
                                            title: 'Breakpoints',
                                            componentName: 'BreakpointsView',
                                            componentState: { label: 'B' }
                                   }
                               ]
                          }]
            }]
};

function mountVueComponent(container, id, componentName, props) {
    
    console.log('Mount vue component ' + componentName + ' in ' + id)
    
    container.getElement().html( `<div id='${id}'></div>` );
    let componentConstructor = Vue.component(componentName)
    let component = new componentConstructor(props)
    components[id] = component;
    setTimeout(function() {
        component.$mount('#'+id)
    }, 0) 
}

var layout = null;

function registerVueComponent(componentName) {
    layout.registerComponent(componentName, function( container, componentState) {
        mountVueComponent(container, componentName, componentName, componentState)
    })
}

$(document).ready(function() {
    
    window.model = {
        breakpoints : new Breakpoints({dataFiles: breakpoint_srcs})
    }
    
    model.breakpoints.load()
    
    layout = new GoldenLayout( config, $('#content')[0] );
   
    registerVueComponent('SummaryView')
    registerVueComponent('BreakpointsView')
    
    let footer = $('.footer')
    let footerHeight = footer.outerHeight()
    
    $('#content').height($(window).height() - footerHeight - 18 - $('#header').outerHeight())
    
    layout.init()
    console.log("Layout initialized");
    
})
