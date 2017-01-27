import java.sql.SQLException;

import com.j256.ormlite.dao.Dao
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.field.DatabaseField
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.DatabaseTable
import com.j256.ormlite.table.TableUtils

import groovy.transform.CompileStatic;
import groovy.transform.ToString;
import groovy.util.logging.Log4j;

@DatabaseTable(tableName='breakpoint')
class Breakpoint {
    
    public static Dao DAO = null
    
    Breakpoint() {
    }
    
    @DatabaseField(id=true, useGetSet=true)
    public long id  
    
    @CompileStatic
    long getId() {
        XPos.computeId(this.chr, this.pos)
    }
   
    @CompileStatic
    void setId(long id) {
        this.id = id
    }
    
    @DatabaseField(canBeNull = false, index=true)
    int chr
    
    @DatabaseField(canBeNull = false, index=true)
    int pos
    
    @DatabaseField(canBeNull = false, columnName='sample_count')
    int sampleCount
    
    @DatabaseField(canBeNull = false, columnName='obs')
    int obs
      
}
