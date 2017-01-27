
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
