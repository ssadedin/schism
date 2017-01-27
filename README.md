# Schism

## What is Schism? 

Schism is a tool to identify **unusual** variations in genomic reads, without
imposing any preconceived model of the nature of that variation.

Schism falls into two essential parts:

 * A tool to build a database of controls that captures 
   systematic variation
 * A tool to scan a genome and find variations in a test sample that are absent or 
   under-represented in the control database
   
## Why Schism?

There are many tools available for finding variation in human genomes. These tools 
do a wonderful job and many are extremely sensitive for the variation they are designed
to detect. So why another tool? Even though there are so many tools, each tool is usually
designed with a particular kind of genetic variation in mind and is tuned for that variation.
However, there still
remains a question: is there variation still remaining that is *not* being detected?
Schism is an attempt to answer this question by creating a database
of genomic mismatches that is unbiased by any requirement for the variation to conform to
a particular model. The goal of this approach is to allow the question,
"Is there anything unusual in my data" to be asked, without imposing a bias of the form 
the unusual variation takes. 

Because of its unbiased approach, Schism can find variation that is too complex, strange
or having too weak a signal to be detected by other tools, while also being sensitive
to conventional structural variation as well.

## How does Schism identify variation?

Schism treats any consecutive sequence of mismatches between a genomic read 
and the reference sequence as an event of interest. For lack of a better term, 
these are referred to as "breakpoints". This does not mean the genome is "broken",
rather just that the sequence in the read departs from the reference sequence.

## What kind of data does Schism work on?

Schism is mainly meant for whole genome data. However it can also work well
with exome data, for detecting events that fall into the covered regions.

## Sounds good, how do I use it?

Have a look at the [documentation](doc/index.md).

