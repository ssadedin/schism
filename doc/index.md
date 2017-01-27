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

## How does Schism identify variation?

Schism treats any consecutive sequence of mismatches between a genomic read 
and the reference sequence as an event of interest. For lack of a better term, 
these are referred to as "breakpoints". This does not mean the genome is "broken",
rather just that the sequence in the read departs from the reference sequence.

## Building

Schism requires only Java 1.7+ to build and run. Please use the following commands:

```
git checkout http://github.com/ssadedin/schism
cd schism
./gradlew jar
```

## Running Schism

As mentioned above, there are two steps to using Schism. First, create a control database, and second
run query to compare a sample of interest to that control database.

_NOTE_: All the commands below assume you are using a command prompt with the current directory
set as the Schism install directory. Schism can be run from anywhere, however, it just makes
the commands shorter for these examples!

### Create a Control Database

Creating a control database is performed using the builddb command, found in the `bin` folder. Here
is how it looks:

```
./bin/builddb -db control.db bam_file_1.bam bam_file_2.bam ....
```

This command will scan all the major chromosomes in every BAM file supplied. 

__Note__: Schism can use CRAM files as well as BAM files. For this to work, you should set
the reference sequence that was used to compress the CRAM files as the `SCHISM_REF` environment
variable, eg:

```
export SCHISM_REF="/path/to/the/reference/sequence.fasta"
```

#### Using a Mask

Some regions of the genome can be particularly slow to process, require excessive memory, and 
may yield unreliable results. It can be sensible to simply mask these regions out from processing.
Schism include a suitable mask BED file for most purposes, and this can be applied with the `-L` 
option:

```
./bin/builddb -L ./resources/mask.bed  -db control.db bam_file_1.bam bam_file_2.bam ....
```

### Find Rare or Novel Breakpoints 

Once you have a control database to compare against, you can run `findbp` to look for interesting
breakpoints that are present in your sample, but not in the control database:

```bash
./bin/findbp \
   -mindp 6 \
   -maxsc 5  \
   -mask ./resources/mask.bed \
   -db controls.db \
   -bam my_sample.bam
```

In this command, we ask Schism to report breakpoints that are found in no more than 5 samples altogether, and also having
at least 6 reads supporting the same breakpoint. We apply the mask as mentioned above, and ask it to scan the whole
BAM file.

### Restricting the Search

Scanning a whole BAM file is not always necessary. Schism lets you directly search a gene or a list of genes. This
is convenient if you are looking to screen a particular gene that you already think may be responsible for 
a phenotype in a particular sample. It is also useful combine this with the "-padding" option to add 
upstream and downstream regions of the gene to scan, in case an event located nearby interferes with the gene
in question, without beginning or ending within the gene:

```
./bin/findbp \
   -mindp 6 \
   -maxsc 5  \
   -mask ./resources/mask.bed \
   -gene DMD \
   -gene LMNA \
   -db controls.db \
   -bam my_sample.bam
```

_Note_: the gene locations are determined from RefSeq gene annotations. 

### Adding Reference Information

When a breakpoint is found, it can be informative to know if the reference sequence adjacent
to the breakpoint is concordant with the mismatches from a second breakpoint. Such 
concordance can support interpretation as a deletion, duplication or other events where two 
breakpoints are evident. To allow for this, add the `-ref` option. You can specify the 
reference sequence directly, or if the SCHISM_REF variable is set, set this to `auto`.

```
./bin/findbp \
   -ref auto \
   -mindp 6 \
   -maxsc 5  \
   -mask ./resources/mask.bed \
   -gene DMD \
   -gene LMNA \
   -db controls.db \
   -bam my_sample.bam
```

### Interpreting Output

Schism writes output in tab separated form. If the -o option is used the output is written to a file, 
otherise it is printed to the screen (stdout). An example of output (formatted as a table) is shown
below:


| chr | start   | end | sample | depth  | sample_count  | cscore | partner | genes | cdsdist |
| --- | ------- | --- | ------ | ------ | ------------- | ------ | ------- | ----- | ------- |
|12|20998242|20998243|TEST_SAMPLE|3|1|1||SLCO1B3|9720|
|12|20998257|20998258|TEST_SAMPLE|8|1|1||SLCO1B3|9705|
|12|21017366|21017167|TEST_SAMPLE|18|1|0.996|12:21036794|SLCO1B3|1377|
|12|21036794|21036795|TEST_SAMPLE|17|1|1|12:21017366|SLCO1B3|257|
|12|21049156|21049157|TEST_SAMPLE|4|1|0.978||SLCO1B3|2214|

In this case, we can see that there is a breakpoint at 12:21017166 which links to
another breakpoint at 12:21036794, 19.6kbp upstream. Such an event is suggestive of 
a deletion event, but other interpretations could be possible. A gene (SLCO1B3) is annotated
as being close to the event, and the `cdsdist` column tells us that the breakpoint is 1377bp from
the coding sequence of SLCO1B3 at one end, and 257bp from the coding sequence at the other. This informs
us that the event could be important because it is near to coding sequence for this gene.
   
## FAQ

**Wait, how do you know the variation is real if you don't interpret it at all?**

While Schism does screen out some common known forms of artefactual variation, it does
not attempt to screen out all forms of non-real variation. Observing an event 
in Schism's control database is thus **not** intended to imply that the variation is real. However 
if it is observed frequently then it can be interpreted to imply that it is too common 
to be causative of a rare disease, **regardless of whether it is real**. Effective use of Schism 
is thus dependent on construction of a sufficiently large control database to screen out common
real variants as well as common false positive artefacts.



