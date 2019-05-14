package schism.domain

import javax.sql.DataSource

import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

/**
 * A simple per-thread database connection
 * <p>
 * This allows domain classes to do things like look up related classes etc.
 * 
 * @author simon.sadedin
 */
@CompileStatic
class DB {
    
    static ThreadLocal<Sql> sql = new ThreadLocal<Sql>()
    
    static Object from(DataSource ds, @ClosureParams(value=SimpleType, options='groovy.sql.Sql') Closure c) {
        try {
            Sql s = new Sql(ds)
            this.sql.set(s)
            return c(db)
        }
        finally {
            this.sql.get().close()
        }
    }
    
    static Sql getDb() {
        return sql.get()
    }
}
