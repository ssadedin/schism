

class Banner {
    static void banner() {
        
        if(System.properties['os.name']?.contains("Mac")) {
        
        System.err.println """
███████╗ ██████╗██╗  ██╗██╗███████╗███╗   ███╗
██╔════╝██╔════╝██║  ██║██║██╔════╝████╗ ████║
███████╗██║     ███████║██║███████╗██╔████╔██║
╚════██║██║     ██╔══██║██║╚════██║██║╚██╔╝██║
███████║╚██████╗██║  ██║██║███████║██║ ╚═╝ ██║
╚══════╝ ╚═════╝╚═╝  ╚═╝╚═╝╚══════╝╚═╝     ╚═╝

Schism finds meaningful breakpoints in genomic data.                                             
"""
        }
        else {
        System.err.println """
 ____       _     _
/ ___|  ___| |__ (_)___ _ __ ___
\\___ \\ / __| '_ \\| / __| '_ ` _ \\
 ___) | (__| | | | \\__ \\ | | | | |
|____/ \\___|_| |_|_|___/_| |_| |_|
                                          
Schism finds meaningful breakpoints in genomic data.                                             
"""
        }
        
    }
}
