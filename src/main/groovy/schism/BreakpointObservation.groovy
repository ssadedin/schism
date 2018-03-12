package schism
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


import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.DatabaseTable
import com.j256.ormlite.table.TableUtils

import groovy.transform.ToString;
import groovy.util.logging.Log4j;

@DatabaseTable(tableName='breakpoint_observation')
@ToString
class BreakpointObservation {
    
    public static Dao DAO = null
    
    /*
    @DatabaseField(generatedId = true)
    long id
    */
    
    @DatabaseField(canBeNull=false, index=true, columnName='bp_id')
    long bpId
    
    @DatabaseField(canBeNull = false)
    String sample
    
    @DatabaseField(canBeNull = false, columnName='read_name')
    String readName
}
