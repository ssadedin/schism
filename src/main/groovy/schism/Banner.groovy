// vim: shiftwidth=4:ts=4:expandtab:cindent
/////////////////////////////////////////////////////////////////////////////////
//
// This file is part of Schism.
// 
// Schism is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, under version 3 of the License, subject
// to additional terms compatible with the GNU General Public License version 3,
// specified in the LICENSE file that is part of the Schism distribution.
//
// Schism is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of 
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with Schism.  If not, see <http://www.gnu.org/licenses/>.
//
/////////////////////////////////////////////////////////////////////////////////

package schism

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
