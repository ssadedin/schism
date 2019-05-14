package schism.domain

import com.fasterxml.jackson.annotation.JsonIgnore

import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.transform.CompileStatic

@CompileStatic
class Cohort {
    
    Long id
    
    String name
    
    String description
    
    @JsonIgnore
    List<Sample> samples
    
    Cohort(GroovyRowResult row) {
        this.id = (Long)row.ID
        this.name = row.NAME
        this.description = row.DESCRIPTION
    }
    
    List<Sample> getSamples() {
        List<Sample> samples = DB.db.rows("""
             select s.*, f.id as family_id, f.name as family_name
             from sample s 
             INNER JOIN COHORT_SAMPLES cs ON cs.SAMPLE_ID = s.id
             INNER JOIN cohort c ON c.id = cs.COHORT_ID
             INNER JOIN family f ON s.FAMILY_ID = f.id
        """).collect {
            new Sample(it)
        }
    }
    
    static constraints = {
    }
    
    static List<Cohort> findAllByUser(User user) {

    }
    
    static List<Cohort> getByUser(User user, Long cohortId) {
        /*
        Cohort.where {
            id == cohortId
            users {
                id == user.id
            }
        }.get()
        */
    }
    
   static Cohort query(Long id) {
       GroovyRowResult row = DB.db.firstRow("select * from cohort where id = $id")
       return new Cohort(row)
   } 
}
