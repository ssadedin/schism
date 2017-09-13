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

import groovy.sql.GroovyRowResult
import groovy.sql.Sql

import groovy.transform.CompileStatic
import groovy.util.logging.Log

/**
 * Functions for manaaging and querying a set of breakpoint databases for information about 
 * breakpoints.
 * 
 * @author Simon Sadedin
 */
@Log
class BreakpointDatabaseSet {
    
    boolean warnedAboutNoOverlap = false
    
	List<Sql> databaseConnections
    
    Regions databases = new Regions()
    
    List<String> excludeSamples = []
    
    String chrPrefix = null
    
    BreakpointDatabaseSet(List<String> dbFiles, Map<String,Integer> contigs) {
        
        log.info "Opening databases ..."
        List<Sql> dbs = dbFiles.collect {  dbFile ->
            Properties props = new Properties()
            props.setProperty("open_mode", "1"); 
            Sql db = Sql.newInstance("jdbc:sqlite:${dbFile}", props)
            db.cacheConnection = true
            db.cacheStatements = true
            db
        }
		
		this.databaseConnections = dbs
        
        log.info "Determining database ranges ..."
        this.databases = determineDatabaseRanges(dbs, contigs)
    }
    
    @CompileStatic
    Regions determineDatabaseRanges(List<Sql> databases, Map<String,Integer> contigs) {
        
        this.chrPrefix = contigs*.key.any { it.startsWith('chr') } ? "chr" : ""
        
        Regions dbRegions = new Regions()
        for(Sql db in databases) {
            GroovyRowResult minmax = null
            synchronized(db) { 
                minmax = db.firstRow("select min(id) as min_bp, max(id) as max_bp from breakpoint;")
            }
            Region minRegion = XPos.parsePos((long)minmax.min_bp)
            Region maxRegion = XPos.parsePos((long)minmax.max_bp)
            
            log.info "Database $db spans $minRegion - $maxRegion"
            
            // Add first region
            if(minRegion.chr == maxRegion.chr)
                dbRegions.addRegion(dbRegion(minRegion.chr, minRegion.from,maxRegion.to, db))
            else
                dbRegions.addRegion(dbRegion(minRegion.chr, minRegion.from, contigs[chrPrefix+minRegion.chr], db))
            
            // Add middle chromosomes - these span the whole chromosome each
            int minChrInt = XPos.chrToInt(minRegion.chr)
            int maxChrInt = XPos.chrToInt(maxRegion.chr)
            for(i in minChrInt..<maxChrInt) {
                def chr = chrPrefix + XPos.intToChr(i)
                if(contigs[chr])
                    dbRegions.addRegion(dbRegion(chr, 0, contigs[chr], db))
            }
            
            // Add last chromosome (only up to end of last region)
            dbRegions.addRegion(dbRegion(maxRegion.chr, 0,maxRegion.to, db))
        }
        return dbRegions
    }
    
    Region dbRegion(String chr, int from, int to, Sql db) {
        if(chrPrefix && !chr.startsWith(chrPrefix))
            chr = chrPrefix + chr
            
        Region r = new Region(chr, from..to)
        r.db = db
        r
    }
    
    
    @CompileStatic
    Object collectFromDbs(String chr, int pos, Closure c) {
        List<Sql> dbs = (List<Sql>)databases.getOverlaps(chr, pos, pos+1).collect { r ->
            GRange gr = (GRange)r
            Expando e = (Expando)gr.extra
            e.getProperty('db')
        }.unique {System.identityHashCode(it)}
        
        if(!dbs && !warnedAboutNoOverlap) {
            log.info "WARNING: No provided databases overlap breakpoint $chr:$pos"
            warnedAboutNoOverlap = true
        }
            
        return dbs.collect { db ->
            synchronized(db) {
                c(db)
            }
        }
    }
    
    
    @CompileStatic
    int getBreakpointFrequency(String chr, int pos, String excludeSample) {
        List<Sql> dbs = (List<Sql>)databases.getOverlaps(chr, pos, pos+1).collect { r ->
            GRange gr = (GRange)r
            Expando e = (Expando)gr.extra
            e.getProperty('db')
        }.unique {System.identityHashCode(it)}
        
        if(!dbs) {
            if(!warnedAboutNoOverlap) {
                log.info "WARNING: No provided databases overlap breakpoint $chr:$pos"
                warnedAboutNoOverlap = true
            }
            return 0
        }
        
            
        return (int)dbs.collect { db ->
            getDbBreakpointFrequency(db, chr,pos, excludeSample)
        }.sum()?:0
    }
    
    @CompileStatic
    int getDbBreakpointFrequency(Sql db, String chr, int pos, String excludeSample) {
        long breakpointId = XPos.computePos(chr, pos)
        synchronized(db) {
            def dbBreakpoint = db.firstRow("select sample_count from breakpoint where id = $breakpointId")        
            if(dbBreakpoint == null)
                return 0
            else {
                def excludeResult
                if(excludeSamples) {
                    db.firstRow("""
                        select count(1) as exclude_count from breakpoint_observation 
                        where id = $breakpointId 
                        and sample in (""" + "${excludeSamples.join("','")},$excludeSample)" + """
                    """)
                }
                else {
                    excludeResult = db.firstRow("""
                       select count(1) as exclude_count from breakpoint_observation where id = $breakpointId and sample=$excludeSample
                    """)        
                }
                
                int excludeCount = excludeResult ? (int)excludeResult.exclude_count : 0
                
                return ((int)dbBreakpoint.sample_count) - excludeCount
            }
        }
    }
    
	/**
	 * Close database connections
	 */
	void close() {
		if(this.databaseConnections) {
			databaseConnections.each { db ->
				try {
					db.close()
				}
				catch(Exception e) {
					// Ignore	
					log.warning("Failed to close database connection: " + e)
				}
			}
		}
	}
    
    
}
