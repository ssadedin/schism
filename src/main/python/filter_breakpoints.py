import sys
import numpy as np
import pandas as pd
import sqlite3

import argparse

parser = argparse.ArgumentParser(prog="filter_breakpoints", description="Remove low quality breakpoints from the breakpoint database")
parser.add_argument('-db', '--database', help="SQLite breakpoint database", required=True)
parser.add_argument('-o', '--outfile', help="GZipped TSV file to write as output", required=True)

options = parser.parse_args(sys.argv[1:])

print "Database is %s" % options.database
print "Output file is %s " % options.outfile

with sqlite3.connect(options.database) as db:
    breakpoints = pd.read_sql_query("select * from breakpointobservation", db)

print "Found %d breakpoints" % breakpoints.shape[0]    

