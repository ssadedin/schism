package schism.domain

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty

import groovy.sql.GroovyRowResult
import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static DB.getDb

@CompileStatic
class Sample {
    
    static Logger log = LoggerFactory.getLogger('Sample')
    
    Long id
    
    String sampleId
    
    String sex
    
    Family family
    
    Sample mother
    
    Sample father
    
    List<Phenotype> phenotypes = null
    
    @JsonIgnore
    List<Cohort> cohorts = null
    
    Sample(Long id) {
        this(db.firstRow("select * from sample where id = $id"))
    }
    
    Sample(GroovyRowResult row) {
        this.id = (Long) row.ID
        this.sampleId = row.SAMPLE_ID
        this.sex = row.SEX
    }
    
    List<Cohort> getCohorts() {
        if(this.cohorts == null) {
            db.rows("""
                select * from cohort c inner join cohort_samples cs on cs.cohort id = c.id and cs.sample_id = $id
            """).collect { GroovyRowResult row ->
                new Cohort(row)
            }
        }
    }
    
    Sample getMother() {
        GroovyRowResult row = db.firstRow("select mother.* from sample mother inner join sample me on me.mother_id = mother.id and me.id = $id")
        if(row) {
            return new Sample(row)
        }
        else {
            return null
        }
    }
    
    Sample getFather() {
        GroovyRowResult row = db.firstRow("select father.* from sample father inner join sample me on me.father_id = father.id and me.id = $id")
        if(row) {
            return new Sample(row)
        }
        else {
            return null
        }
    }
   
//    static registerConverter() {
//        JSON.registerObjectMarshaller(Sample) { Sample sample ->
//            [
//                id: sample.id,
//                sampleId: sample.sampleId,
//                sex: sample.sex,
//                mother: sample.mother,
//                father: sample.father,
//                family: [
//                    id: sample.family.id,
//                    name: sample.family.name
//                ]
//            ]
//        }
//    }
//    

}
